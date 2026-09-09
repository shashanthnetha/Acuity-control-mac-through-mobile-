package com.macky.client.webrtc

import android.util.Log
import com.macky.client.webrtc.models.SignalingEnvelope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SignalingClient"

sealed class SignalingEvent {
    object Connected : SignalingEvent()
    data class OfferReceived(val sdp: String) : SignalingEvent()
    data class CandidateReceived(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int) : SignalingEvent()
    data class PeerJoined(val role: String) : SignalingEvent()
    data class PeerLeft(val role: String) : SignalingEvent()
    object ApprovalPending : SignalingEvent()
    data class ApprovalDenied(val message: String) : SignalingEvent()
    data class ErrorReceived(val message: String) : SignalingEvent()
    data class Disconnected(val reason: String) : SignalingEvent()
}

enum class SignalingState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * Robust OkHttp WebSocket client matching the Macky signaling protocol.
 * Includes automatic reconnect with exponential backoff (1s, 2s, 4s, 8s, capped at 15s for ~2 min).
 * Passes device identity for host-side authorization.
 */
class SignalingClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var currentRoomCode: String? = null
    private var currentServerUrl: String? = null
    private var identityManager: com.macky.client.identity.DeviceIdentityManager? = null

    private val isExplicitDisconnect = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 12 // 1s, 2s, 4s, 8s, 15s x 8 = ~135s (give up after ~2 min)

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(SignalingState.DISCONNECTED)
    val state: StateFlow<SignalingState> = _state.asStateFlow()

    private val _reconnectAttemptCount = MutableStateFlow(0)
    val reconnectAttemptCount: StateFlow<Int> = _reconnectAttemptCount.asStateFlow()

    val isReconnecting: StateFlow<Boolean> = MutableStateFlow(false).apply {
        // Will be updated when state changes
    }

    fun connect(
        serverUrl: String,
        roomCode: String,
        identityManager: com.macky.client.identity.DeviceIdentityManager? = null
    ) {
        disconnect()

        this.currentServerUrl = serverUrl.trim().removeSuffix("/")
        this.currentRoomCode = roomCode.trim().uppercase()
        this.identityManager = identityManager
        this.isExplicitDisconnect.set(false)
        this.reconnectAttempt = 0
        this._reconnectAttemptCount.value = 0

        initiateConnection()
    }

    private fun initiateConnection() {
        val serverUrl = currentServerUrl ?: return
        val roomCode = currentRoomCode ?: return

        // Normalize WebSocket protocol
        val base = when {
            serverUrl.startsWith("http://") -> "ws://" + serverUrl.removePrefix("http://")
            serverUrl.startsWith("https://") -> "wss://" + serverUrl.removePrefix("https://")
            serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://") -> serverUrl
            else -> "ws://$serverUrl"
        }

        // Clean WebSocket URL without query parameters for device identity (Requirement 3)
        val wsUrl = "$base/ws/$roomCode?role=viewer"

        Log.i(TAG, "Connecting to signaling server at: $wsUrl")
        _state.value = if (reconnectAttempt > 0) SignalingState.RECONNECTING else SignalingState.CONNECTING

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully to room: $roomCode")
                reconnectAttempt = 0
                _reconnectAttemptCount.value = 0
                _state.value = SignalingState.CONNECTED
                scope.launch {
                    _events.emit(SignalingEvent.Connected)
                }

                // Immediately transmit device identity over signaling envelope (not query params)
                identityManager?.let { idMgr ->
                    val identityEnvelope = SignalingEnvelope.createDeviceIdentity(
                        deviceId = idMgr.deviceId,
                        deviceName = idMgr.deviceName,
                        publicKey = idMgr.publicKeyBase64
                    )
                    Log.i(TAG, "Transmitting device-identity payload: fp=${idMgr.deviceId.take(12)}..., name='${idMgr.deviceName}'")
                    webSocket.send(identityEnvelope.toJson())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing (code: $code, reason: $reason)")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed (code: $code, reason: $reason)")
                _state.value = SignalingState.DISCONNECTED
                scope.launch {
                    _events.emit(SignalingEvent.Disconnected(reason))
                }
                if (!isExplicitDisconnect.get() && code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _state.value = SignalingState.FAILED
                scope.launch {
                    _events.emit(SignalingEvent.ErrorReceived("Connection failure: ${t.localizedMessage}"))
                }
                if (!isExplicitDisconnect.get()) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun handleIncomingMessage(json: String) {
        try {
            val envelope = SignalingEnvelope.fromJson(json)
            when (envelope.type) {
                "offer" -> {
                    val sdpPayload = envelope.getSdpPayload()
                    if (sdpPayload != null) {
                        scope.launch {
                            _events.emit(SignalingEvent.OfferReceived(sdpPayload.sdp))
                        }
                    }
                }
                "ice-candidate" -> {
                    val candidatePayload = envelope.getCandidatePayload()
                    if (candidatePayload != null) {
                        scope.launch {
                            _events.emit(
                                SignalingEvent.CandidateReceived(
                                    candidate = candidatePayload.candidate,
                                    sdpMid = candidatePayload.sdpMid,
                                    sdpMLineIndex = candidatePayload.sdpMLineIndex
                                )
                            )
                        }
                    }
                }
                "peer-joined" -> {
                    val peer = envelope.getPeerEventPayload()
                    scope.launch {
                        _events.emit(SignalingEvent.PeerJoined(peer?.role ?: "peer"))
                    }
                }
                "peer-left" -> {
                    val peer = envelope.getPeerEventPayload()
                    scope.launch {
                        _events.emit(SignalingEvent.PeerLeft(peer?.role ?: "peer"))
                    }
                }
                "approval-pending" -> {
                    Log.i(TAG, "Host approval is pending on Mac...")
                    scope.launch {
                        _events.emit(SignalingEvent.ApprovalPending)
                    }
                }
                "auth-challenge" -> {
                    val challenge = envelope.getAuthChallengePayload()
                    if (challenge != null) {
                        val nonceBase64 = challenge.nonce
                        Log.i(TAG, "Received auth-challenge nonce from Mac host: $nonceBase64")
                        try {
                            val nonceBytes = try {
                                android.util.Base64.decode(nonceBase64, android.util.Base64.NO_WRAP)
                            } catch (e: Exception) {
                                java.util.Base64.getDecoder().decode(nonceBase64)
                            }
                            val signatureBase64 = identityManager?.signNonce(nonceBytes) ?: ""
                            val responseEnvelope = SignalingEnvelope.createAuthResponse(
                                nonce = nonceBase64,
                                signature = signatureBase64
                            )
                            Log.i(TAG, "Sending auth-response signed with hardware-backed KeyStore key...")
                            webSocket?.send(responseEnvelope.toJson())
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to sign auth challenge nonce: ${e.message}", e)
                            scope.launch {
                                _events.emit(SignalingEvent.ErrorReceived("Authentication error: failed to sign host challenge"))
                            }
                        }
                    }
                }
                "error" -> {
                    val error = envelope.getErrorPayload()
                    val msg = error?.message ?: "Unknown signaling error"
                    val code = error?.code
                    Log.w(TAG, "Received signaling error: $msg (code: $code)")
                    scope.launch {
                        if (code == "APPROVAL_DENIED") {
                            _events.emit(SignalingEvent.ApprovalDenied(msg))
                        } else if (code == "AUTH_FAILED" || code == "AUTH_TIMEOUT") {
                            _events.emit(SignalingEvent.ErrorReceived("Host rejected connection: $msg"))
                        } else {
                            _events.emit(SignalingEvent.ErrorReceived(msg))
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "Unhandled signaling message type: ${envelope.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing signaling message: ${e.message}", e)
        }
    }

    fun sendAnswer(sdp: String): Boolean {
        val envelope = SignalingEnvelope.createAnswer(sdp)
        val json = envelope.toJson()
        Log.i(TAG, "Sending SDP Answer over signaling...")
        return webSocket?.send(json) ?: false
    }

    fun sendCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int): Boolean {
        val envelope = SignalingEnvelope.createCandidate(candidate, sdpMid, sdpMLineIndex)
        val json = envelope.toJson()
        Log.d(TAG, "Sending ICE Candidate: $candidate")
        return webSocket?.send(json) ?: false
    }

    private fun scheduleReconnect() {
        if (isExplicitDisconnect.get()) return

        if (reconnectAttempt >= maxReconnectAttempts) {
            Log.e(TAG, "Exceeded maximum reconnect attempts ($maxReconnectAttempts / ~2 minutes). Giving up.")
            _state.value = SignalingState.FAILED
            _reconnectAttemptCount.value = 0
            scope.launch {
                _events.emit(SignalingEvent.ErrorReceived("Connection lost. Reconnect timed out after 2 minutes."))
            }
            return
        }

        reconnectAttempt++
        _reconnectAttemptCount.value = reconnectAttempt
        val backoffSeconds = (1L shl (reconnectAttempt - 1)).coerceAtMost(15L)
        Log.i(TAG, "Scheduling reconnect attempt #$reconnectAttempt in ${backoffSeconds}s...")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffSeconds * 1000)
            if (!isExplicitDisconnect.get()) {
                initiateConnection()
            }
        }
    }

    fun disconnect() {
        isExplicitDisconnect.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        _reconnectAttemptCount.value = 0

        try {
            webSocket?.close(1000, "Client closed connection")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket: ${e.message}")
        }
        webSocket = null
        _state.value = SignalingState.DISCONNECTED
    }
}
