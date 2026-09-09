import Foundation

/// Delegate protocol for handling signaling events from the server.
public protocol SignalingClientDelegate: AnyObject {
    func signalingClientDidConnect(_ client: SignalingClient)
    func signalingClientDidDisconnect(_ client: SignalingClient, error: Error?)
    func signalingClient(_ client: SignalingClient, didReceivePeerJoined role: String, deviceId: String?, deviceName: String?)
    func signalingClient(_ client: SignalingClient, didReceiveDeviceIdentity deviceId: String, deviceName: String, publicKey: String)
    func signalingClient(_ client: SignalingClient, didReceiveAuthResponse nonce: String, signature: String)
    func signalingClient(_ client: SignalingClient, didReceiveAnswer sdp: String)
    func signalingClient(_ client: SignalingClient, didReceiveCandidate candidate: String, sdpMid: String, sdpMLineIndex: Int32)
    func signalingClient(_ client: SignalingClient, didReceivePeerLeft role: String)
    func signalingClient(_ client: SignalingClient, didReceiveError message: String)
}

/// WebSocket and REST client for communicating with the WebRTC signaling server.
public final class SignalingClient: NSObject, URLSessionWebSocketDelegate {

    public weak var delegate: SignalingClientDelegate?

    public let serverBaseURL: String
    public private(set) var roomCode: String?

    private var urlSession: URLSession!
    private var webSocketTask: URLSessionWebSocketTask?
    private var isConnected = false
    private var isExplicitDisconnect = false
    private var reconnectWorkItem: DispatchWorkItem?
    private let jsonDecoder = JSONDecoder()

    public init(serverBaseURL: String = "http://localhost:8000") {
        // Strip trailing slash
        if serverBaseURL.hasSuffix("/") {
            self.serverBaseURL = String(serverBaseURL.dropLast())
        } else {
            self.serverBaseURL = serverBaseURL
        }
        super.init()
        self.urlSession = URLSession(configuration: .default, delegate: self, delegateQueue: OperationQueue())
    }

    /// Calls POST /rooms on the signaling server to create a new session room.
    public func createRoom() async throws -> String {
        guard let url = URL(string: "\(serverBaseURL)/rooms") else {
            throw NSError(domain: "SignalingClient", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid server URL"])
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw NSError(
                domain: "SignalingClient",
                code: status,
                userInfo: [NSLocalizedDescriptionKey: "Failed to create room (HTTP \(status))"]
            )
        }

        struct CreateResponse: Decodable {
            let room_code: String
        }

        let decoded = try jsonDecoder.decode(CreateResponse.self, from: data)
        self.roomCode = decoded.room_code
        return decoded.room_code
    }

    /// Connects to the room's signaling WebSocket as role=host.
    public func connect(roomCode: String) {
        self.isExplicitDisconnect = false
        self.roomCode = roomCode.uppercased()

        // Build ws:// or wss:// URL from http:// or https:// base
        var wsBase = serverBaseURL
        if wsBase.hasPrefix("https://") {
            wsBase = "wss://" + wsBase.dropFirst("https://".count)
        } else if wsBase.hasPrefix("http://") {
            wsBase = "ws://" + wsBase.dropFirst("http://".count)
        } else if !wsBase.hasPrefix("ws://") && !wsBase.hasPrefix("wss://") {
            wsBase = "ws://" + wsBase
        }

        guard let wsURL = URL(string: "\(wsBase)/ws/\(self.roomCode!)?role=host") else {
            print("[Signaling] Invalid WebSocket URL")
            return
        }

        print("[Signaling] Connecting WebSocket to \(wsURL.absoluteString)...")
        let request = URLRequest(url: wsURL)
        let task = urlSession.webSocketTask(with: request)
        self.webSocketTask = task
        task.resume()

        listenForMessages()
    }

    /// Disconnects from the signaling server.
    public func disconnect() {
        isExplicitDisconnect = true
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
        isConnected = false
    }

    private func scheduleHostReconnect() {
        guard !isExplicitDisconnect, let room = roomCode else { return }
        reconnectWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self = self, !self.isExplicitDisconnect else { return }
            print("🔄 [Signaling] Reconnecting host WebSocket to room \(room)...")
            self.connect(roomCode: room)
        }
        reconnectWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0, execute: item)
    }

    // MARK: - Sending Messages

    /// Sends WebRTC SDP Offer to the viewer peer.
    public func sendOffer(sdp: String) {
        let envelope: [String: Any] = [
            "type": "offer",
            "payload": [
                "sdp": sdp,
                "type": "offer"
            ]
        ]
        sendEnvelope(envelope)
    }

    /// Sends an ICE Candidate to the viewer peer.
    public func sendCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int32) {
        let envelope: [String: Any] = [
            "type": "ice-candidate",
            "payload": [
                "candidate": sdp,
                "sdpMid": sdpMid ?? "",
                "sdpMLineIndex": sdpMLineIndex
            ]
        ]
        sendEnvelope(envelope)
    }

    /// Sends a cryptographic authentication challenge (32-byte nonce) to the viewer.
    public func sendAuthChallenge(nonceBase64: String) {
        let envelope: [String: Any] = [
            "type": "auth-challenge",
            "payload": [
                "nonce": nonceBase64
            ]
        ]
        sendEnvelope(envelope)
        print("🔒 [Signaling] Transmitted auth-challenge nonce to viewer")
    }

    /// Sends an approval-pending notification to the viewer.
    public func sendApprovalPending() {
        let envelope: [String: Any] = [
            "type": "approval-pending",
            "payload": [
                "message": "Waiting for host approval on Mac..."
            ]
        ]
        sendEnvelope(envelope)
    }

    /// Sends an error / denial to the viewer peer.
    public func sendError(code: String, message: String) {
        let envelope: [String: Any] = [
            "type": "error",
            "payload": [
                "code": code,
                "message": message
            ]
        ]
        sendEnvelope(envelope)
    }

    private func sendEnvelope(_ envelope: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: envelope),
              let jsonString = String(data: data, encoding: .utf8) else {
            print("[Signaling] Failed to serialize message envelope")
            return
        }

        let message = URLSessionWebSocketTask.Message.string(jsonString)
        webSocketTask?.send(message) { error in
            if let error = error {
                print("[Signaling] Failed to send WebSocket message: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Receiving Messages

    private func listenForMessages() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }

            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleIncomingText(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleIncomingText(text)
                    }
                @unknown default:
                    break
                }
                // Continue listening
                self.listenForMessages()

            case .failure(let error):
                if self.isConnected {
                    print("[Signaling] WebSocket receive error: \(error.localizedDescription)")
                    self.isConnected = false
                    self.delegate?.signalingClientDidDisconnect(self, error: error)
                    if !self.isExplicitDisconnect {
                        self.scheduleHostReconnect()
                    }
                }
            }
        }
    }

    private func handleIncomingText(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = json["type"] as? String else {
            print("[Signaling] Received malformed message: \(text)")
            return
        }

        let payload = json["payload"] as? [String: Any] ?? [:]

        switch type {
        case "peer-joined":
            let role = payload["role"] as? String ?? "viewer"
            let deviceId = payload["deviceId"] as? String
            let deviceName = payload["deviceName"] as? String
            print("[Signaling] Peer joined room: \(role) (device: \(deviceName ?? "unlabeled"), fingerprint: \(deviceId?.prefix(12) ?? "none")...)")
            delegate?.signalingClient(self, didReceivePeerJoined: role, deviceId: deviceId, deviceName: deviceName)

        case "device-identity":
            let deviceId = payload["deviceId"] as? String ?? ""
            let deviceName = payload["deviceName"] as? String ?? "Android Device"
            let publicKey = payload["publicKey"] as? String ?? ""
            print("[Signaling] Received device-identity: '\(deviceName)' (fp: \(deviceId.prefix(12))..., pubKey: \(publicKey.prefix(16))...)")
            delegate?.signalingClient(self, didReceiveDeviceIdentity: deviceId, deviceName: deviceName, publicKey: publicKey)

        case "auth-response":
            let nonce = payload["nonce"] as? String ?? ""
            let signature = payload["signature"] as? String ?? ""
            print("[Signaling] Received auth-response (nonce: \(nonce.prefix(8))..., sig length: \(signature.count))")
            delegate?.signalingClient(self, didReceiveAuthResponse: nonce, signature: signature)

        case "answer":
            if let sdp = payload["sdp"] as? String {
                print("[Signaling] Received SDP answer from viewer")
                delegate?.signalingClient(self, didReceiveAnswer: sdp)
            }

        case "ice-candidate":
            if let candidate = payload["candidate"] as? String {
                let sdpMid = payload["sdpMid"] as? String ?? "video"
                let sdpMLineIndex = Int32(payload["sdpMLineIndex"] as? Int ?? 0)
                delegate?.signalingClient(self, didReceiveCandidate: candidate, sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex)
            }

        case "peer-left":
            let role = payload["role"] as? String ?? "viewer"
            print("[Signaling] Peer left room: \(role)")
            delegate?.signalingClient(self, didReceivePeerLeft: role)

        case "error":
            let msg = payload["message"] as? String ?? "Unknown signaling error"
            print("[Signaling] Received error from server: \(msg)")
            delegate?.signalingClient(self, didReceiveError: msg)

        default:
            print("[Signaling] Ignored unhandled message type: \(type)")
        }
    }

    // MARK: - URLSessionWebSocketDelegate

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("[Signaling] WebSocket connection established successfully")
        self.isConnected = true
        delegate?.signalingClientDidConnect(self)
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        let reasonStr = reason.flatMap { String(data: $0, encoding: .utf8) } ?? "None"
        print("[Signaling] WebSocket connection closed (code: \(closeCode.rawValue), reason: \(reasonStr))")
        self.isConnected = false
        delegate?.signalingClientDidDisconnect(self, error: nil)
        if !isExplicitDisconnect {
            scheduleHostReconnect()
        }
    }
}
