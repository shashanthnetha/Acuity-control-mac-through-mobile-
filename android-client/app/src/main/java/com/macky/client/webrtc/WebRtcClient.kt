package com.macky.client.webrtc

import android.content.Context
import android.util.Log
import com.macky.client.webrtc.models.InputEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import java.nio.ByteBuffer

private const val TAG = "WebRtcClient"

enum class ConnectionQuality {
    UNKNOWN,
    GOOD,
    FAIR,
    POOR
}

data class StreamStats(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrateKbps: Long = 0,
    val rttMs: Int = 0,
    val packetsLost: Long = 0,
    val lossRatePercent: Double = 0.0,
    val quality: ConnectionQuality = ConnectionQuality.UNKNOWN
)

enum class PeerState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}


/**
 * WebRTC Media & Connection Manager for the Android Receiver.
 *
 * Lifecycle Strategy:
 * When the app backgrounds, PeerConnection and WebSocket stay alive
 * while the SurfaceViewRenderer simply pauses rendering. This ensures
 * instantaneous resume on foreground without renegotiation overhead.
 */
class WebRtcClient(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    val eglBase: EglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var surfaceViewRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    private val _connectionState = MutableStateFlow(PeerState.IDLE)
    val connectionState: StateFlow<PeerState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private var inputDataChannel: DataChannel? = null
    private val _isInputReady = MutableStateFlow(false)
    val isInputReady: StateFlow<Boolean> = _isInputReady.asStateFlow()

    private var clipboardDataChannel: DataChannel? = null
    private val _clipboardReceived = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val clipboardReceived: kotlinx.coroutines.flow.SharedFlow<String> = _clipboardReceived

    private var filesDataChannel: DataChannel? = null
    val fileTransferManager = FileTransferManager(context, scope)

    private var statsJob: Job? = null
    private var lastBytesReceived: Long = 0
    private var lastTimestampMs: Double = 0.0
    private var prevPacketsLost: Long = 0
    private var prevPacketsReceived: Long = 0


    init {
        // 1. Initialize PeerConnectionFactory global configuration
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // 2. Build hardware + platform software MediaCodec video decoder factory.
        // We avoid DefaultVideoDecoderFactory / SoftwareVideoDecoderFactory because
        // the Stream WebRTC build does not link nativeCreateDecoder for SoftwareVideoDecoderFactory.
        // This composite factory uses HardwareVideoDecoderFactory for real hardware SoCs
        // (Snapdragon, Tensor, Exynos) and falls back to PlatformSoftwareVideoDecoderFactory
        // (c2.android.avc.decoder) on emulators and unsupported chipsets.
        val hwDecoderFactory = HardwareVideoDecoderFactory(eglBase.eglBaseContext)
        val platformSwDecoderFactory = PlatformSoftwareVideoDecoderFactory(eglBase.eglBaseContext)

        val decoderFactory = object : VideoDecoderFactory {
            override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
                return hwDecoderFactory.createDecoder(info)
                    ?: platformSwDecoderFactory.createDecoder(info)
            }

            override fun getSupportedCodecs(): Array<VideoCodecInfo> {
                val codecs = mutableSetOf<VideoCodecInfo>()
                codecs.addAll(hwDecoderFactory.supportedCodecs)
                codecs.addAll(platformSwDecoderFactory.supportedCodecs)
                return codecs.toTypedArray()
            }
        }
        val encoderFactory = HardwareVideoEncoderFactory(eglBase.eglBaseContext, false, false)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        // 3. Listen to incoming signaling events
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.OfferReceived -> handleRemoteOffer(event.sdp)
                    is SignalingEvent.CandidateReceived -> handleRemoteCandidate(event.candidate, event.sdpMid, event.sdpMLineIndex)
                    is SignalingEvent.PeerLeft -> handlePeerLeft()
                    is SignalingEvent.ErrorReceived -> Log.e(TAG, "Signaling error: ${event.message}")
                    else -> Unit
                }
            }
        }
    }

    /**
     * Binds the UI SurfaceViewRenderer.
     */
    fun attachRenderer(renderer: SurfaceViewRenderer) {
        this.surfaceViewRenderer = renderer
        val rendererEvents = object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                Log.i(TAG, "First video frame rendered on SurfaceViewRenderer")
            }

            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                Log.i(TAG, "Video frame resolution changed: ${videoWidth}x${videoHeight}, rotation: $rotation")
                _stats.value = _stats.value.copy(width = videoWidth, height = videoHeight)
            }
        }
        renderer.init(eglBase.eglBaseContext, rendererEvents)
        // Disable hardware scaler so SurfaceView uses full physical resolution (setSizeFromLayout)
        // instead of downsampling to a tiny fixed-size buffer
        renderer.setEnableHardwareScaler(false)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setMirror(false)

        remoteVideoTrack?.addSink(renderer)
        Log.i(TAG, "Attached SurfaceViewRenderer to WebRtcClient")
    }

    /**
     * Called when the app backgrounds: video rendering is paused,
     * but PeerConnection and WebSocket remain alive.
     */
    fun pauseRendering() {
        Log.i(TAG, "Pausing video rendering (PeerConnection remains alive in background)")
        remoteVideoTrack?.setEnabled(false)
    }

    /**
     * Called when the app returns to foreground: video rendering resumes.
     */
    fun resumeRendering() {
        Log.i(TAG, "Resuming video rendering")
        remoteVideoTrack?.setEnabled(true)
    }

    /**
     * Creates and configures the RTCPeerConnection with STUN fallback.
     */
    fun createPeerConnection() {
        if (peerConnection != null) {
            peerConnection?.close()
            peerConnection = null
        }

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling State: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ICE Connection State: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        _connectionState.value = PeerState.CONNECTED
                        startStatsPolling()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        _connectionState.value = PeerState.DISCONNECTED
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        _connectionState.value = PeerState.FAILED
                        stopStatsPolling()
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        _connectionState.value = PeerState.IDLE
                        stopStatsPolling()
                    }
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE Gathering State: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "Generated local ICE Candidate: ${it.sdpMid}:${it.sdpMLineIndex}")
                    signalingClient.sendCandidate(it.sdp, it.sdpMid, it.sdpMLineIndex)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                Log.i(TAG, "onAddStream called with stream: ${stream?.id}")
                if (stream != null && stream.videoTracks.isNotEmpty()) {
                    val track = stream.videoTracks[0]
                    attachRemoteVideoTrack(track)
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: DataChannel?) {
                dataChannel?.let { dc ->
                    Log.i(TAG, "Host established DataChannel: '${dc.label()}' (state: ${dc.state()})")
                    when (dc.label()) {
                        "input" -> {
                            inputDataChannel = dc
                            dc.registerObserver(object : DataChannel.Observer {
                                override fun onBufferedAmountChange(previousAmount: Long) {}
                                override fun onStateChange() {
                                    val state = dc.state()
                                    Log.i(TAG, "Input DataChannel state changed to: $state")
                                    _isInputReady.value = (state == DataChannel.State.OPEN)
                                }
                                override fun onMessage(buffer: DataChannel.Buffer) {}
                            })
                            if (dc.state() == DataChannel.State.OPEN) {
                                _isInputReady.value = true
                            }
                        }
                        "clipboard" -> {
                            clipboardDataChannel = dc
                            dc.registerObserver(object : DataChannel.Observer {
                                override fun onBufferedAmountChange(previousAmount: Long) {}
                                override fun onStateChange() {
                                    Log.i(TAG, "Clipboard DataChannel state changed to: ${dc.state()}")
                                }
                                override fun onMessage(buffer: DataChannel.Buffer) {
                                    val bytes = ByteArray(buffer.data.remaining())
                                    buffer.data.get(bytes)
                                    val text = String(bytes, Charsets.UTF_8)
                                    val parsed = try {
                                        val json = org.json.JSONObject(text)
                                        json.optString("text", text)
                                    } catch (e: Exception) {
                                        text
                                    }
                                    scope.launch(Dispatchers.Main) {
                                        _clipboardReceived.emit(parsed)
                                    }
                                }
                            })
                        }
                        "files" -> {
                            filesDataChannel = dc
                            fileTransferManager.attachDataChannel(dc)
                            dc.registerObserver(object : DataChannel.Observer {
                                override fun onBufferedAmountChange(previousAmount: Long) {}
                                override fun onStateChange() {
                                    Log.i(TAG, "Files DataChannel state changed to: ${dc.state()}")
                                    fileTransferManager.attachDataChannel(dc)
                                }
                                override fun onMessage(buffer: DataChannel.Buffer) {
                                    if (buffer.binary) {
                                        val bytes = ByteArray(buffer.data.remaining())
                                        buffer.data.get(bytes)
                                        fileTransferManager.handleIncomingChunk(bytes)
                                    } else {
                                        val bytes = ByteArray(buffer.data.remaining())
                                        buffer.data.get(bytes)
                                        val text = String(bytes, Charsets.UTF_8)
                                        fileTransferManager.handleIncomingControl(text)
                                    }
                                }
                            })
                        }
                    }
                }
            }


            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded called")
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                Log.i(TAG, "onAddTrack called with receiver: ${receiver?.id()}")
                val track = receiver?.track() as? VideoTrack
                if (track != null) {
                    attachRemoteVideoTrack(track)
                }
            }
        })

        _connectionState.value = PeerState.CONNECTING
        Log.i(TAG, "PeerConnection created successfully")
    }

    private fun attachRemoteVideoTrack(track: VideoTrack) {
        scope.launch(Dispatchers.Main) {
            Log.i(TAG, "Binding incoming VideoTrack to SurfaceViewRenderer: ${track.id()}")
            remoteVideoTrack = track
            track.setEnabled(true)
            surfaceViewRenderer?.let {
                track.addSink(it)
            }
        }
    }

    /**
     * Handles remote SDP offer from the Mac host, sets remote description,
     * creates an SDP answer, sets local description, and transmits to host.
     */
    private fun handleRemoteOffer(sdp: String) {
        val pc = peerConnection ?: run {
            createPeerConnection()
            peerConnection!!
        }

        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        Log.i(TAG, "Applying remote SDP Offer...")

        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set successfully. Creating SDP Answer...")
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (answerDesc == null) return
                        Log.i(TAG, "SDP Answer created. Setting local description...")
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.i(TAG, "Local description set. Sending answer over signaling...")
                                signalingClient.sendAnswer(answerDesc.description)
                            }
                            override fun onCreateFailure(err: String?) {
                                Log.e(TAG, "Failed to set local description: $err")
                            }
                            override fun onSetFailure(err: String?) {
                                Log.e(TAG, "Failed to set local description: $err")
                            }
                        }, answerDesc)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) {
                        Log.e(TAG, "Failed to create SDP answer: $err")
                    }
                    override fun onSetFailure(err: String?) {}
                }, constraints)
            }
            override fun onCreateFailure(err: String?) {}
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "Failed to set remote description: $err")
            }
        }, sessionDesc)
    }

    private fun handleRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val pc = peerConnection ?: return
        val iceCandidate = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        pc.addIceCandidate(iceCandidate)
        Log.d(TAG, "Added remote ICE candidate")
    }

    private fun handlePeerLeft() {
        Log.w(TAG, "Host left the session. Closing peer connection.")
        _connectionState.value = PeerState.DISCONNECTED
        stopStatsPolling()
    }

    /**
     * Polls pc.getStats() on a fixed 2-second interval via a coroutine.
     */
    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(2000)
                val pc = peerConnection ?: continue
                pc.getStats { rtcStatsReport ->
                    parseStatsReport(rtcStatsReport)
                }
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun parseStatsReport(report: RTCStatsReport) {
        var width = 0
        var height = 0
        var fps = 0
        var bitrateKbps: Long = 0
        var rttMs = 0
        var packetsLostCount: Long = 0
        var lossRatePercent = 0.0

        for (stat in report.statsMap.values) {
            if (stat.type == "inbound-rtp" && stat.members["kind"] == "video") {
                val frameW = (stat.members["frameWidth"] as? Number)?.toInt()
                val frameH = (stat.members["frameHeight"] as? Number)?.toInt()
                if (frameW != null && frameH != null) {
                    width = frameW
                    height = frameH
                }

                val framesPerSec = (stat.members["framesPerSecond"] as? Number)?.toInt()
                if (framesPerSec != null) {
                    fps = framesPerSec
                }

                val packets = (stat.members["packetsLost"] as? Number)?.toLong() ?: 0L
                val received = (stat.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                val bytes = (stat.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                val timestamp = stat.timestampUs / 1000.0 // ms
                if (lastTimestampMs > 0 && bytes > lastBytesReceived) {
                    val deltaMs = timestamp - lastTimestampMs
                    if (deltaMs > 0) {
                        bitrateKbps = ((bytes - lastBytesReceived) * 8 / deltaMs).toLong()
                    }
                }
                lastBytesReceived = bytes
                lastTimestampMs = timestamp
                packetsLostCount = packets

                if (prevPacketsReceived > 0) {
                    val deltaLost = maxOf(0L, packets - prevPacketsLost)
                    val deltaReceived = maxOf(0L, received - prevPacketsReceived)
                    val total = deltaLost + deltaReceived
                    lossRatePercent = if (total > 0) (deltaLost.toDouble() / total.toDouble()) * 100.0 else 0.0
                }
                prevPacketsLost = packets
                prevPacketsReceived = received
            }

            if (stat.type == "candidate-pair" && stat.members["state"] == "succeeded") {
                val rtt = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()
                if (rtt != null) {
                    rttMs = (rtt * 1000).toInt()
                }
            }
        }

        val quality = when {
            rttMs <= 0 && lossRatePercent <= 0.0 -> ConnectionQuality.UNKNOWN
            rttMs < 30 && lossRatePercent <= 0.0 -> ConnectionQuality.GOOD
            rttMs <= 80 && lossRatePercent <= 2.0 -> ConnectionQuality.FAIR
            else -> ConnectionQuality.POOR
        }

        if (width > 0 || bitrateKbps > 0 || rttMs > 0) {
            _stats.value = StreamStats(
                width = if (width > 0) width else _stats.value.width,
                height = if (height > 0) height else _stats.value.height,
                fps = fps,
                bitrateKbps = bitrateKbps,
                rttMs = rttMs,
                packetsLost = packetsLostCount,
                lossRatePercent = lossRatePercent,
                quality = quality
            )
        }
    }

    fun disconnect() {
        stopStatsPolling()
        remoteVideoTrack?.let {
            surfaceViewRenderer?.let { renderer -> it.removeSink(renderer) }
        }
        remoteVideoTrack = null

        inputDataChannel?.close()
        inputDataChannel = null
        _isInputReady.value = false

        clipboardDataChannel?.close()
        clipboardDataChannel = null

        filesDataChannel?.close()
        filesDataChannel = null
        fileTransferManager.cancelTransfer()

        peerConnection?.close()
        peerConnection = null
        _connectionState.value = PeerState.IDLE
    }

    /**
     * Transmits a serialized input event (touch, scroll, key) over the WebRTC DataChannel.
     */
    fun sendInputEvent(event: InputEvent): Boolean {
        val dc = inputDataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val json = event.toJson()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
        return dc.send(buffer)
    }

    /**
     * Transmits clipboard text over the dedicated clipboard DataChannel.
     */
    fun sendClipboardText(text: String): Boolean {
        val dc = clipboardDataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val json = org.json.JSONObject().apply {
            put("type", "clipboard")
            put("text", text)
        }.toString()
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false)
        return dc.send(buffer)
    }

    /**
     * Transmits low-bandwidth mode configuration to Mac host over input DataChannel.
     */
    fun sendLowBandwidthConfig(enabled: Boolean): Boolean {
        val dc = inputDataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val json = org.json.JSONObject().apply {
            put("type", "config")
            put("lowBandwidth", enabled)
        }.toString()
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false)
        return dc.send(buffer)
    }

    fun release() {
        disconnect()
        surfaceViewRenderer?.release()
        surfaceViewRenderer = null
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}

