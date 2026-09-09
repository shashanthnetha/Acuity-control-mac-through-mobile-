package com.macky.client

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macky.client.discovery.DiscoveredServer
import com.macky.client.discovery.MdnsDiscoveryManager
import com.macky.client.identity.DeviceIdentityManager
import com.macky.client.storage.SavedConnection
import com.macky.client.storage.SavedConnectionStore
import com.macky.client.webrtc.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class ScreenState {
    object Connect : ScreenState()
    data class Stream(val roomCode: String) : ScreenState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val identityManager = DeviceIdentityManager(application)
    val discoveryManager = MdnsDiscoveryManager(application, viewModelScope)
    val savedStore = SavedConnectionStore(application)

    val signalingClient = SignalingClient(viewModelScope)
    val webRtcClient = WebRtcClient(application.applicationContext, signalingClient, viewModelScope)

    // Discovered and saved connections
    val discoveredServers: StateFlow<List<DiscoveredServer>> = discoveryManager.discoveredServers
    val savedConnections: StateFlow<List<SavedConnection>> = savedStore.savedConnections

    // User-editable signaling server URL (Tailscale / LAN / Emulator)
    private val _serverUrl = MutableStateFlow("ws://10.0.2.2:8000")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _roomCode = MutableStateFlow("")
    val roomCode: StateFlow<String> = _roomCode.asStateFlow()

    private val _screenState = MutableStateFlow<ScreenState>(ScreenState.Connect)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isScanningQr = MutableStateFlow(false)
    val isScanningQr: StateFlow<Boolean> = _isScanningQr.asStateFlow()

    // Host Approval Security Gate
    private val _isWaitingForApproval = MutableStateFlow(false)
    val isWaitingForApproval: StateFlow<Boolean> = _isWaitingForApproval.asStateFlow()

    // Fetching / Connecting status
    private val _isFetchingRoom = MutableStateFlow(false)
    val isFetchingRoom: StateFlow<Boolean> = _isFetchingRoom.asStateFlow()

    private val prefs = application.getSharedPreferences("acuity_settings", android.content.Context.MODE_PRIVATE)
    private val _isLowBandwidthMode = MutableStateFlow(prefs.getBoolean("pref_low_bandwidth", false))
    val isLowBandwidthMode: StateFlow<Boolean> = _isLowBandwidthMode.asStateFlow()

    val fileTransferState = webRtcClient.fileTransferManager.transferState
    val clipboardReceived = webRtcClient.clipboardReceived

    val signalingState = signalingClient.state
    val peerState = webRtcClient.connectionState
    val stats = webRtcClient.stats
    val isInputReady = webRtcClient.isInputReady
    val reconnectAttemptCount = signalingClient.reconnectAttemptCount

    fun sendInputEvent(event: com.macky.client.webrtc.models.InputEvent): Boolean {
        return webRtcClient.sendInputEvent(event)
    }

    fun toggleLowBandwidthMode() {
        val newValue = !_isLowBandwidthMode.value
        _isLowBandwidthMode.value = newValue
        prefs.edit().putBoolean("pref_low_bandwidth", newValue).apply()
        if (webRtcClient.isInputReady.value) {
            webRtcClient.sendLowBandwidthConfig(newValue)
        }
    }

    fun sendClipboardText(text: String): Boolean {
        return webRtcClient.sendClipboardText(text)
    }

    fun sendFile(uri: android.net.Uri) {
        webRtcClient.fileTransferManager.sendFile(uri)
    }

    fun cancelFileTransfer() {
        webRtcClient.fileTransferManager.cancelTransfer()
    }

    fun resetFileTransferState() {
        webRtcClient.fileTransferManager.resetState()
    }

    init {
        // Start mDNS discovery on launch
        discoveryManager.startDiscovery()

        // Sync low-bandwidth state to Mac when input channel opens
        viewModelScope.launch {
            webRtcClient.isInputReady.collect { ready ->
                if (ready && _isLowBandwidthMode.value) {
                    webRtcClient.sendLowBandwidthConfig(true)
                }
            }
        }


        viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Connected -> {
                        _errorMessage.value = null
                    }
                    is SignalingEvent.ApprovalPending -> {
                        _isWaitingForApproval.value = true
                    }
                    is SignalingEvent.OfferReceived -> {
                        _isWaitingForApproval.value = false
                    }
                    is SignalingEvent.ApprovalDenied -> {
                        _isWaitingForApproval.value = false
                        _errorMessage.value = event.message
                        disconnect()
                    }
                    is SignalingEvent.ErrorReceived -> {
                        _isWaitingForApproval.value = false
                        _errorMessage.value = event.message
                    }
                    is SignalingEvent.PeerLeft -> {
                        _errorMessage.value = "Host disconnected from room."
                    }
                    else -> Unit
                }
            }
        }
    }

    fun updateServerUrl(url: String) {
        _serverUrl.value = url
    }

    fun updateRoomCode(code: String) {
        _roomCode.value = extractRoomCode(code)
    }

    fun startScanningQr() {
        _isScanningQr.value = true
    }

    fun stopScanningQr() {
        _isScanningQr.value = false
    }

    fun onQrCodeScanned(content: String) {
        _isScanningQr.value = false
        val parsed = parseQrContent(content)

        // 1. If QR contains a reachable host/port, update serverUrl
        if (parsed.serverUrl != null) {
            _serverUrl.value = parsed.serverUrl
        } else if (_serverUrl.value.contains("10.0.2.2") || _serverUrl.value.contains("localhost")) {
            // Fallback: If current serverUrl is still emulator/localhost default, check Bonjour discovered servers
            val firstDiscovered = discoveredServers.value.firstOrNull()
            if (firstDiscovered != null) {
                _serverUrl.value = firstDiscovered.serverUrl
            }
        }

        // 2. Set room code and connect
        if (parsed.roomCode.length == 6) {
            _roomCode.value = parsed.roomCode
            connect()
        } else {
            _errorMessage.value = "Scanned QR code did not contain a valid 6-character room code."
        }
    }

    /**
     * Connects with manual input fields.
     */
    fun connect() {
        val code = _roomCode.value.trim().uppercase()
        if (code.length != 6) {
            _errorMessage.value = "Room code must be exactly 6 characters."
            return
        }

        // Prevent redundant connections if already connecting/connected to the same room
        if (_screenState.value is ScreenState.Stream &&
            (_screenState.value as ScreenState.Stream).roomCode == code &&
            (signalingState.value == SignalingState.CONNECTING || signalingState.value == SignalingState.CONNECTED)) {
            android.util.Log.i("MainViewModel", "Already connecting/connected to room $code, skipping duplicate connect()")
            return
        }

        _errorMessage.value = null
        _isWaitingForApproval.value = false
        _screenState.value = ScreenState.Stream(code)

        // Pause discovery while streaming to save battery/bandwidth
        discoveryManager.stopDiscovery()

        // Initialize PeerConnection and initiate WebSocket signaling with hardware KeyStore identity
        webRtcClient.createPeerConnection()
        signalingClient.connect(
            serverUrl = _serverUrl.value,
            roomCode = code,
            identityManager = identityManager
        )
    }

    /**
     * Part A: Zero-config 1-tap connection to an mDNS-discovered Mac agent.
     */
    fun connectToDiscovered(server: DiscoveredServer) {
        _serverUrl.value = server.serverUrl
        _roomCode.value = server.roomCode.ifBlank { "" }

        if (server.roomCode.length == 6) {
            connect()
        } else {
            // If room code wasn't in TXT record, auto-fetch from GET /current-room
            connectToSaved(
                SavedConnection(
                    name = server.name,
                    serverUrl = server.serverUrl
                )
            )
        }
    }

    /**
     * Part B: 1-tap connection to a saved host (e.g. Tailscale / fixed IP).
     * Dynamically fetches live room code from GET /current-room.
     */
    fun connectToSaved(saved: SavedConnection) {
        _errorMessage.value = null
        _isFetchingRoom.value = true

        viewModelScope.launch {
            val result = savedStore.fetchCurrentRoom(saved.serverUrl)
            _isFetchingRoom.value = false

            if (result.isSuccess) {
                val liveRoomCode = result.getOrThrow()
                _serverUrl.value = saved.serverUrl
                _roomCode.value = liveRoomCode
                connect()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                _errorMessage.value = "Failed to connect to '${saved.name}': $error"
            }
        }
    }

    fun saveConnection(name: String, serverUrl: String) {
        savedStore.saveConnection(name, serverUrl)
    }

    fun deleteSavedConnection(id: String) {
        savedStore.deleteConnection(id)
    }

    fun disconnect() {
        signalingClient.disconnect()
        webRtcClient.disconnect()
        _isWaitingForApproval.value = false
        _screenState.value = ScreenState.Connect
        // Resume local network discovery
        discoveryManager.startDiscovery()
    }

    data class ParsedQrResult(
        val serverUrl: String?,
        val roomCode: String
    )

    private fun parseQrContent(input: String): ParsedQrResult {
        val trimmed = input.trim()

        // Case 1: URL with query parameter (e.g. http://192.168.29.99:8000/?room=HSRTSQ or http://...:8080/test-viewer.html?room=HSRTSQ)
        if (trimmed.contains("room=")) {
            val code = trimmed.substringAfter("room=").substringBefore("&").take(6).uppercase()
            val urlWithoutQuery = trimmed.substringBefore("?")
            val hostPort = try {
                val uri = java.net.URI(urlWithoutQuery)
                val host = uri.host
                val port = if (uri.port != -1) uri.port else 8000
                if (!host.isNullOrBlank() && host != "localhost" && host != "127.0.0.1") {
                    // Map legacy test-viewer port 8080 to signaling port 8000
                    val targetPort = if (port == 8080) 8000 else port
                    "http://$host:$targetPort"
                } else null
            } catch (e: Exception) {
                null
            }
            return ParsedQrResult(serverUrl = hostPort, roomCode = code)
        }

        // Case 2: WebSocket URL (e.g. ws://192.168.29.99:8000/ws/HSRTSQ)
        if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
            try {
                val uri = java.net.URI(trimmed)
                val host = uri.host
                val port = if (uri.port != -1) uri.port else 8000
                val path = uri.path ?: ""
                val code = path.substringAfterLast("/").take(6).uppercase()
                val server = if (!host.isNullOrBlank() && host != "localhost" && host != "127.0.0.1") {
                    "http://$host:$port"
                } else null
                return ParsedQrResult(serverUrl = server, roomCode = code)
            } catch (e: Exception) {
                // fallback below
            }
        }

        // Case 3: Plain 6-character room code (e.g. "HSRTSQ")
        val plainCode = trimmed.take(6).uppercase()
        return ParsedQrResult(serverUrl = null, roomCode = plainCode)
    }

    private fun extractRoomCode(input: String): String {
        return parseQrContent(input).roomCode
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stopDiscovery()
        webRtcClient.release()
    }
}
