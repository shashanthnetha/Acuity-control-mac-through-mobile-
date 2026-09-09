package com.macky.client.webrtc

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.DataChannel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private const val TAG = "FileTransferManager"

data class FileTransferProgress(
    val isActive: Boolean = false,
    val isSending: Boolean = false,
    val fileName: String = "",
    val totalBytes: Long = 0L,
    val progress: Float = 0f,
    val isComplete: Boolean = false,
    val error: String? = null,
    val savedPath: String? = null
)

class FileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val MAX_FILE_SIZE = 200L * 1024 * 1024 // 200MB
        const val CHUNK_SIZE = 16 * 1024             // 16KB
    }

    private val _transferState = MutableStateFlow(FileTransferProgress())
    val transferState: StateFlow<FileTransferProgress> = _transferState.asStateFlow()

    private var dataChannel: DataChannel? = null
    private var sendJob: Job? = null

    // Incoming file state
    private var incomingTempFile: File? = null
    private var incomingFileName: String = ""
    private var incomingFileSize: Long = 0L
    private var incomingMimeType: String = ""
    private var incomingTotalChunks: Int = 0
    private var incomingReceivedChunks: Int = 0
    private var incomingDigest: MessageDigest? = null
    private var incomingFileOutputStream: FileOutputStream? = null

    fun attachDataChannel(channel: DataChannel?) {
        this.dataChannel = channel
        Log.i(TAG, "Attached files DataChannel (state: ${channel?.state()})")
    }

    // MARK: - Outgoing Transfers

    fun sendFile(uri: Uri) {
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            _transferState.value = FileTransferProgress(
                isActive = false,
                error = "File transfer channel not connected."
            )
            return
        }

        if (_transferState.value.isActive) {
            _transferState.value = _transferState.value.copy(error = "Transfer already in progress.")
            return
        }

        sendJob?.cancel()
        sendJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Resolve file details
                val (fileName, fileSize, mimeType) = resolveUriDetails(uri)
                if (fileSize > MAX_FILE_SIZE) {
                    _transferState.value = FileTransferProgress(
                        isActive = false,
                        error = "File exceeds 200MB limit (${fileSize / (1024 * 1024)}MB)."
                    )
                    return@launch
                }

                val totalChunks = ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

                _transferState.value = FileTransferProgress(
                    isActive = true,
                    isSending = true,
                    fileName = fileName,
                    totalBytes = fileSize,
                    progress = 0f
                )

                // 2. Send START control message
                val startMsg = JSONObject().apply {
                    put("type", "start")
                    put("name", fileName)
                    put("size", fileSize)
                    put("mimeType", mimeType)
                    put("totalChunks", totalChunks)
                }.toString()

                sendControlMessage(startMsg)

                // 3. Stream chunks with 4-byte big-endian Int32 index prefix
                val digest = MessageDigest.getInstance("SHA-256")
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open input stream for URI: $uri")

                val buffer = ByteArray(CHUNK_SIZE)
                var chunkIndex = 0

                inputStream.use { stream ->
                    while (isActive) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead <= 0) break

                        digest.update(buffer, 0, bytesRead)

                        // 4 bytes header + chunk bytes
                        val packet = ByteBuffer.allocate(4 + bytesRead).order(ByteOrder.BIG_ENDIAN).apply {
                            putInt(chunkIndex)
                            put(buffer, 0, bytesRead)
                        }.array()

                        val webRtcBuffer = DataChannel.Buffer(ByteBuffer.wrap(packet), true)
                        val sent = dc.send(webRtcBuffer)
                        if (!sent) {
                            delay(10)
                        }

                        chunkIndex++
                        val progress = chunkIndex.toFloat() / totalChunks.toFloat()
                        _transferState.value = _transferState.value.copy(progress = progress.coerceIn(0f, 1f))

                        if (chunkIndex % 4 == 0) {
                            delay(1)
                        }
                    }
                }

                // 4. Send COMPLETE control message
                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                val completeMsg = JSONObject().apply {
                    put("type", "complete")
                    put("checksum", checksum)
                }.toString()

                sendControlMessage(completeMsg)

                _transferState.value = _transferState.value.copy(
                    isActive = false,
                    isComplete = true,
                    progress = 1f
                )
                Log.i(TAG, "Sent file '$fileName' successfully with checksum: $checksum")

            } catch (e: Exception) {
                Log.e(TAG, "File sending failed", e)
                sendControlMessage("{\"type\":\"cancel\"}")
                _transferState.value = FileTransferProgress(
                    isActive = false,
                    error = "Failed to send file: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }

    fun cancelTransfer() {
        sendJob?.cancel()
        sendJob = null
        sendControlMessage("{\"type\":\"cancel\"}")
        cleanupIncoming()
        _transferState.value = FileTransferProgress(isActive = false, error = "Transfer cancelled.")
    }

    private fun sendControlMessage(text: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false)
        return dc.send(buffer)
    }

    // MARK: - Incoming Transfers

    fun handleIncomingControl(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "start" -> {
                    val name = json.getString("name")
                    val size = json.getLong("size")
                    val mime = json.optString("mimeType", "application/octet-stream")
                    val totalChunks = json.getInt("totalChunks")
                    startIncoming(name, size, mime, totalChunks)
                }
                "complete" -> {
                    val checksum = json.getString("checksum")
                    finishIncoming(checksum)
                }
                "cancel" -> {
                    cleanupIncoming()
                    _transferState.value = FileTransferProgress(isActive = false, error = "Transfer cancelled by sender.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming file control: $text", e)
        }
    }

    fun handleIncomingChunk(data: ByteArray) {
        if (data.size < 4) return
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val chunkIndex = buffer.int
        val payloadLen = buffer.remaining()
        val payload = ByteArray(payloadLen)
        buffer.get(payload)

        try {
            incomingFileOutputStream?.write(payload)
            incomingDigest?.update(payload)
            incomingReceivedChunks++

            val progress = if (incomingTotalChunks > 0) {
                incomingReceivedChunks.toFloat() / incomingTotalChunks.toFloat()
            } else 0f

            _transferState.value = _transferState.value.copy(progress = progress.coerceIn(0f, 1f))
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing incoming file chunk $chunkIndex", e)
            cleanupIncoming()
            _transferState.value = FileTransferProgress(isActive = false, error = "Failed to write file chunk.")
        }
    }

    private fun startIncoming(name: String, size: Long, mimeType: String, totalChunks: Int) {
        cleanupIncoming()

        if (size > MAX_FILE_SIZE) {
            sendControlMessage("{\"type\":\"cancel\"}")
            _transferState.value = FileTransferProgress(
                isActive = false,
                error = "Incoming file exceeds 200MB limit."
            )
            return
        }

        try {
            val tempDir = File(context.cacheDir, "acuity_transfers").apply { mkdirs() }
            val tempFile = File(tempDir, "incoming_${System.currentTimeMillis()}.tmp")

            incomingTempFile = tempFile
            incomingFileName = name
            incomingFileSize = size
            incomingMimeType = mimeType
            incomingTotalChunks = totalChunks
            incomingReceivedChunks = 0
            incomingDigest = MessageDigest.getInstance("SHA-256")
            incomingFileOutputStream = FileOutputStream(tempFile)

            _transferState.value = FileTransferProgress(
                isActive = true,
                isSending = false,
                fileName = name,
                totalBytes = size,
                progress = 0f
            )
            Log.i(TAG, "Starting incoming file transfer: '$name' ($size bytes, $totalChunks chunks)")
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating incoming file", e)
            _transferState.value = FileTransferProgress(isActive = false, error = "Cannot initiate incoming transfer.")
        }
    }

    private fun finishIncoming(expectedChecksum: String) {
        val tempFile = incomingTempFile ?: return
        try {
            incomingFileOutputStream?.flush()
            incomingFileOutputStream?.close()
            incomingFileOutputStream = null

            val calculatedChecksum = incomingDigest?.digest()?.joinToString("") { "%02x".format(it) } ?: ""

            if (!calculatedChecksum.equals(expectedChecksum, ignoreCase = true)) {
                Log.e(TAG, "Checksum mismatch! Expected: $expectedChecksum, Actual: $calculatedChecksum")
                tempFile.delete()
                _transferState.value = FileTransferProgress(
                    isActive = false,
                    error = "Checksum verification failed. File may be corrupted."
                )
                return
            }

            // Save verified file to Downloads/Acuity
            val savedPath = saveToDownloads(incomingFileName, incomingMimeType, tempFile)
            tempFile.delete()

            _transferState.value = FileTransferProgress(
                isActive = false,
                isComplete = true,
                fileName = incomingFileName,
                totalBytes = incomingFileSize,
                progress = 1f,
                savedPath = savedPath
            )
            Log.i(TAG, "Incoming file successfully saved to $savedPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed finishing incoming file", e)
            tempFile.delete()
            _transferState.value = FileTransferProgress(isActive = false, error = "Failed to save received file.")
        } finally {
            cleanupIncoming()
        }
    }

    private fun saveToDownloads(fileName: String, mimeType: String, tempFile: File): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Acuity")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to create MediaStore entry in Downloads")

            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { input ->
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            "Downloads/Acuity/$fileName"
        } else {
            val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Acuity").apply { mkdirs() }
            val destFile = File(downloadsDir, fileName)
            tempFile.copyTo(destFile, overwrite = true)
            destFile.absolutePath
        }
    }

    private fun cleanupIncoming() {
        try {
            incomingFileOutputStream?.close()
        } catch (_: Exception) {}
        incomingFileOutputStream = null
        incomingDigest = null
        incomingTempFile = null
    }

    private fun resolveUriDetails(uri: Uri): Triple<String, Long, String> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex) ?: name
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        return Triple(name, size, mime)
    }

    fun resetState() {
        _transferState.value = FileTransferProgress()
    }
}
