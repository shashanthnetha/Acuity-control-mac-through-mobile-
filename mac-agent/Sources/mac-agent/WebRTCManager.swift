import Foundation
import CoreMedia
import CoreVideo
import WebRTC

/// Custom RTCVideoCapturer that feeds CVPixelBuffer frames directly to RTCVideoSource.
public final class ScreenVideoCapturer: RTCVideoCapturer {

    public func pushPixelBuffer(_ pixelBuffer: CVPixelBuffer, timestamp: CMTime) {
        let rtcBuffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
        let timeStampNs = Int64(CMTimeGetSeconds(timestamp) * 1_000_000_000)
        let videoFrame = RTCVideoFrame(
            buffer: rtcBuffer,
            rotation: ._0,
            timeStampNs: timeStampNs
        )
        self.delegate?.capturer(self, didCapture: videoFrame)
    }
}

/// WebRTC Peer Connection and Media Pipeline Manager.
public final class WebRTCManager: NSObject, RTCPeerConnectionDelegate {

    public private(set) var peerConnection: RTCPeerConnection?
    public private(set) var videoTrack: RTCVideoTrack?
    public private(set) var videoSource: RTCVideoSource?
    public private(set) var dataChannel: RTCDataChannel?
    public private(set) var clipboardDataChannel: RTCDataChannel?
    public private(set) var filesDataChannel: RTCDataChannel?

    public var onClipboardReceived: ((String) -> Void)?
    public var onLowBandwidthConfigReceived: ((Bool) -> Void)?
    public var onFileControlReceived: ((String) -> Void)?
    public var onFileChunkReceived: ((Data) -> Void)?

    public let inputInjector: InputInjector = InputInjector()
    private var screenCapturer: ScreenVideoCapturer?
    private let factory: RTCPeerConnectionFactory
    private let signalingClient: SignalingClient

    public private(set) var framesCapturedCount: UInt64 = 0
    public private(set) var isConnected: Bool = false
    public var maxBitrateMbps: Int = 6
    public var minBitrateMbps: Int = 1
    public var degradationPreference: RTCDegradationPreference = .balanced

    public init(signalingClient: SignalingClient) {
        self.signalingClient = signalingClient

        // Initialize WebRTC SSL and thread state
        RTCInitializeSSL()

        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        self.factory = RTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )

        super.init()
        setupMediaPipeline()
    }

    deinit {
        RTCCleanupSSL()
    }

    // MARK: - Media Pipeline Setup

    private func setupMediaPipeline() {
        // Create local video source and capturer
        let source = factory.videoSource()
        self.videoSource = source
        self.screenCapturer = ScreenVideoCapturer(delegate: source)

        // Create local video track
        let track = factory.videoTrack(with: source, trackId: "mac-screen-video-track")
        track.isEnabled = true
        self.videoTrack = track
        print("[WebRTC] Video pipeline configured (trackId: mac-screen-video-track)")
    }

    /// Applies screen sharing encoding parameters (bitrate limits and degradation preference).
    public func applySenderParameters() {
        guard let pc = peerConnection else { return }
        guard let sender = pc.senders.first(where: { $0.track?.kind == "video" }) else { return }

        let parameters = sender.parameters
        parameters.degradationPreference = NSNumber(value: degradationPreference.rawValue)

        if let encoding = parameters.encodings.first {
            encoding.maxBitrateBps = NSNumber(value: maxBitrateMbps * 1_000_000)
            encoding.minBitrateBps = NSNumber(value: minBitrateMbps * 1_000_000)
            encoding.scaleResolutionDownBy = NSNumber(value: 1.0)
            encoding.bitratePriority = degradationPreference == .maintainResolution ? 2.0 : 1.0

            let prefStr = degradationPreference == .maintainResolution ? "maintainResolution" : "balanced"
            print("[WebRTC] Configured RTP sender: maxBitrate=\(maxBitrateMbps)Mbps, minBitrate=\(minBitrateMbps)Mbps, degradationPreference=\(prefStr)")
        }
        sender.parameters = parameters
    }

    /// Munges SDP to declare high bandwidth limits so receiver and sender negotiate maximum quality.
    private func mungeSDP(_ sdp: String) -> String {
        let maxKbps = maxBitrateMbps * 1000
        let lines = sdp.components(separatedBy: "\r\n")
        var modified: [String] = []

        for line in lines {
            modified.append(line)
            if line.starts(with: "m=video") {
                modified.append("b=AS:\(maxKbps)")
                modified.append("b=TIAS:\(maxKbps * 1000)")
            }
        }
        return modified.joined(separator: "\r\n")
    }

    /// Feeds a CVPixelBuffer frame from ScreenCaptureKit into WebRTC video pipeline.
    public func pushFrame(_ pixelBuffer: CVPixelBuffer, timestamp: CMTime) {
        screenCapturer?.pushPixelBuffer(pixelBuffer, timestamp: timestamp)
        framesCapturedCount += 1

        if framesCapturedCount % 150 == 0 {
            print("[WebRTC] Outbound screen frames broadcasted: \(framesCapturedCount) frames")
        }
    }

    // MARK: - Peer Connection Lifecycle

    /// Creates and initializes the RTCPeerConnection.
    public func createPeerConnection() {
        if peerConnection != nil {
            peerConnection?.close()
            peerConnection = nil
        }

        let config = RTCConfiguration()
        // Public Google STUN fallback for NAT traversal (Tailscale/local subnet bypasses if direct)
        let stunServer = RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])
        config.iceServers = [stunServer]
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "false",
                "OfferToReceiveVideo": "false"
            ],
            optionalConstraints: [
                "DtlsSrtpKeyAgreement": "true"
            ]
        )

        guard let pc = factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            print("[WebRTC] Error: Failed to instantiate RTCPeerConnection")
            return
        }

        self.peerConnection = pc

        // Attach our screen video track
        if let videoTrack = self.videoTrack {
            pc.add(videoTrack, streamIds: ["mac-screen-stream"])
            print("[WebRTC] Added video track to peer connection")
            applySenderParameters()
        }

        // Create WebRTC DataChannel labeled "input" for touch/gesture/keyboard input injection
        let dcConfig = RTCDataChannelConfiguration()
        dcConfig.isOrdered = true
        if let dc = pc.dataChannel(forLabel: "input", configuration: dcConfig) {
            dc.delegate = self
            self.dataChannel = dc
            print("[WebRTC] Created and attached DataChannel '\(dc.label)' (ordered=true)")
        }

        // Create WebRTC DataChannel labeled "clipboard" for manual clipboard sync
        let clipConfig = RTCDataChannelConfiguration()
        clipConfig.isOrdered = true
        if let dc = pc.dataChannel(forLabel: "clipboard", configuration: clipConfig) {
            dc.delegate = self
            self.clipboardDataChannel = dc
            print("[WebRTC] Created and attached DataChannel '\(dc.label)' (ordered=true)")
        }

        // Create WebRTC DataChannel labeled "files" for chunked binary file transfer
        let filesConfig = RTCDataChannelConfiguration()
        filesConfig.isOrdered = true
        if let dc = pc.dataChannel(forLabel: "files", configuration: filesConfig) {
            dc.delegate = self
            self.filesDataChannel = dc
            print("[WebRTC] Created and attached DataChannel '\(dc.label)' (ordered=true)")
        }

        print("[WebRTC] PeerConnection created successfully with STUN fallback")
    }

    /// Creates and sends an SDP Offer to the viewer.
    public func createAndSendOffer() {
        guard let pc = peerConnection else {
            print("[WebRTC] Cannot create offer: PeerConnection is null")
            return
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "false",
                "OfferToReceiveVideo": "false"
            ],
            optionalConstraints: nil
        )

        pc.offer(for: constraints) { [weak self] (sdp, error) in
            guard let self = self else { return }

            if let error = error {
                print("[WebRTC] Error creating SDP offer: \(error.localizedDescription)")
                return
            }

            guard let sdp = sdp else {
                print("[WebRTC] Error: Generated SDP offer was nil")
                return
            }

            let mungedSDPString = self.mungeSDP(sdp.sdp)
            let mungedSDP = RTCSessionDescription(type: .offer, sdp: mungedSDPString)

            print("[WebRTC] SDP offer created with \(self.maxBitrateMbps)Mbps bandwidth allocation. Setting local description...")
            pc.setLocalDescription(mungedSDP) { [weak self] error in
                guard let self = self else { return }

                if let error = error {
                    print("[WebRTC] Error setting local description: \(error.localizedDescription)")
                    return
                }

                // Ensure high-bitrate encoder parameters are locked in
                self.applySenderParameters()

                print("[WebRTC] Local description set. Transmitting offer over signaling...")
                self.signalingClient.sendOffer(sdp: mungedSDP.sdp)
            }
        }
    }

    /// Handles incoming SDP Answer from the viewer.
    public func handleRemoteAnswer(sdp: String) {
        guard let pc = peerConnection else {
            print("[WebRTC] Cannot handle remote answer: PeerConnection is null")
            return
        }

        let remoteDesc = RTCSessionDescription(type: .answer, sdp: sdp)
        pc.setRemoteDescription(remoteDesc) { [weak self] error in
            if let error = error {
                print("[WebRTC] Error setting remote description (answer): \(error.localizedDescription)")
            } else {
                print("[WebRTC] \u{001B}[1;32mRemote SDP answer accepted!\u{001B}[0m WebRTC handshake complete.")
                self?.applySenderParameters()
            }
        }
    }

    /// Handles incoming remote ICE candidate from the viewer.
    public func handleRemoteCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int32) {
        guard let pc = peerConnection else { return }
        let iceCandidate = RTCIceCandidate(sdp: candidate, sdpMLineIndex: sdpMLineIndex, sdpMid: sdpMid)
        pc.add(iceCandidate) { error in
            if let error = error {
                print("[WebRTC] Error adding remote ICE candidate: \(error.localizedDescription)")
            } else {
                print("[WebRTC] Added remote ICE candidate (mid: \(sdpMid), mLine: \(sdpMLineIndex))")
            }
        }
    }

    // MARK: - RTCPeerConnectionDelegate

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        print("[WebRTC] Signaling State: \(stateChanged.description)")
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {
        print("[WebRTC] PeerConnection should negotiate")
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        switch newState {
        case .new:
            print("[WebRTC] ICE Connection State: \u{001B}[33mNEW\u{001B}[0m")
        case .checking:
            print("[WebRTC] ICE Connection State: \u{001B}[33mCHECKING\u{001B}[0m")
        case .connected:
            print("[WebRTC] ICE Connection State: \u{001B}[1;32mCONNECTED\u{001B}[0m 🚀")
        case .completed:
            print("[WebRTC] ICE Connection State: \u{001B}[1;32mCOMPLETED\u{001B}[0m")
        case .failed:
            print("[WebRTC] ICE Connection State: \u{001B}[1;31mFAILED\u{001B}[0m")
        case .disconnected:
            print("[WebRTC] ICE Connection State: \u{001B}[31mDISCONNECTED\u{001B}[0m")
        case .closed:
            print("[WebRTC] ICE Connection State: CLOSED")
        case .count:
            break
        @unknown default:
            break
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        print("[WebRTC] ICE Gathering State: \(newState.description)")
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        print("[WebRTC] Generated local ICE candidate: \(candidate.sdpMid ?? "video"):\(candidate.sdpMLineIndex)")
        signalingClient.sendCandidate(
            sdp: candidate.sdp,
            sdpMid: candidate.sdpMid,
            sdpMLineIndex: candidate.sdpMLineIndex
        )
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        print("[WebRTC] Remote peer opened DataChannel: '\(dataChannel.label)'")
        if dataChannel.label == "input" {
            self.dataChannel = dataChannel
            dataChannel.delegate = self
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        switch newState {
        case .new:
            print("[WebRTC] PeerConnection State: NEW")
        case .connecting:
            print("[WebRTC] PeerConnection State: \u{001B}[33mCONNECTING...\u{001B}[0m")
        case .connected:
            self.isConnected = true
            print("[WebRTC] PeerConnection State: \u{001B}[1;32mCONNECTED!\u{001B}[0m Streaming screen live to peer.")
        case .disconnected:
            self.isConnected = false
            inputInjector.reset()
            print("[WebRTC] PeerConnection State: \u{001B}[31mDISCONNECTED\u{001B}[0m")
        case .failed:
            self.isConnected = false
            inputInjector.reset()
            print("[WebRTC] PeerConnection State: \u{001B}[1;31mFAILED\u{001B}[0m")
        case .closed:
            self.isConnected = false
            inputInjector.reset()
            print("[WebRTC] PeerConnection State: CLOSED")
        @unknown default:
            break
        }
    }
}

// MARK: - RTCDataChannelDelegate

extension WebRTCManager: RTCDataChannelDelegate {
    public func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        let stateStr: String
        switch dataChannel.readyState {
        case .connecting: stateStr = "CONNECTING"
        case .open: stateStr = "OPEN"
        case .closing: stateStr = "CLOSING"
        case .closed: stateStr = "CLOSED"
        @unknown default: stateStr = "UNKNOWN"
        }
        print("\u{001B}[1;36m[DataChannel]\u{001B}[0m Channel '\(dataChannel.label)' readyState changed to: \(stateStr)")
        if dataChannel.readyState == .closed {
            inputInjector.reset()
        }
    }

    public func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        if dataChannel.label == "input" {
            guard let text = String(data: buffer.data, encoding: .utf8) else { return }
            if text.contains("\"type\":\"config\"") || text.contains("\"type\": \"config\"") {
                if let data = text.data(using: .utf8),
                   let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let lowBw = dict["lowBandwidth"] as? Bool {
                    onLowBandwidthConfigReceived?(lowBw)
                    return
                }
            }
            inputInjector.handleMessage(text)
        } else if dataChannel.label == "clipboard" {
            guard let text = String(data: buffer.data, encoding: .utf8) else { return }
            if let data = text.data(using: .utf8),
               let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let clipText = dict["text"] as? String {
                onClipboardReceived?(clipText)
            } else {
                onClipboardReceived?(text)
            }
        } else if dataChannel.label == "files" {
            if buffer.isBinary {
                onFileChunkReceived?(buffer.data)
            } else if let text = String(data: buffer.data, encoding: .utf8) {
                onFileControlReceived?(text)
            }
        }
    }

    public func sendClipboardText(_ text: String) {
        guard let dc = clipboardDataChannel, dc.readyState == .open else {
            print("[WebRTC] Cannot send clipboard: channel not open")
            return
        }
        let dict: [String: Any] = ["type": "clipboard", "text": text]
        if let data = try? JSONSerialization.data(withJSONObject: dict) {
            let buffer = RTCDataBuffer(data: data, isBinary: false)
            dc.sendData(buffer)
            print("[WebRTC] Sent clipboard text to phone (\(text.count) characters)")
        }
    }

    public func sendFileControl(_ jsonString: String) {
        guard let dc = filesDataChannel, dc.readyState == .open else { return }
        if let data = jsonString.data(using: .utf8) {
            let buffer = RTCDataBuffer(data: data, isBinary: false)
            dc.sendData(buffer)
        }
    }

    public func sendFileChunk(index: Int32, data: Data) -> Bool {
        guard let dc = filesDataChannel, dc.readyState == .open else { return false }
        var packet = Data()
        var bigIndex = index.bigEndian
        packet.append(Data(bytes: &bigIndex, count: 4))
        packet.append(data)
        let buffer = RTCDataBuffer(data: packet, isBinary: true)
        return dc.sendData(buffer)
    }

    public func setBitrateCeiling(kbps: Int) {
        guard let pc = peerConnection else { return }
        for sender in pc.senders {
            guard let track = sender.track, track.kind == kRTCMediaStreamTrackKindVideo else { continue }
            let params = sender.parameters
            for encoding in params.encodings {
                encoding.maxBitrateBps = NSNumber(value: kbps * 1000)
            }
            sender.parameters = params
            print("[WebRTC] Video encoder bitrate ceiling set to \(kbps) kbps")
        }
    }
}

// MARK: - Enum Description Helpers

extension RTCSignalingState {
    var description: String {
        switch self {
        case .stable: return "stable"
        case .haveLocalOffer: return "haveLocalOffer"
        case .haveLocalPrAnswer: return "haveLocalPrAnswer"
        case .haveRemoteOffer: return "haveRemoteOffer"
        case .haveRemotePrAnswer: return "haveRemotePrAnswer"
        case .closed: return "closed"
        @unknown default: return "unknown"
        }
    }
}

extension RTCIceGatheringState {
    var description: String {
        switch self {
        case .new: return "new"
        case .gathering: return "gathering"
        case .complete: return "complete"
        @unknown default: return "unknown"
        }
    }
}
