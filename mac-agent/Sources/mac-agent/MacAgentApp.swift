import Foundation
import ScreenCaptureKit
import AppKit

public protocol MacAgentAppDelegate: AnyObject {
    func macAgentApp(_ app: MacAgentApp, didStartWithRoomCode roomCode: String)
    func macAgentAppDidStop(_ app: MacAgentApp)
    func macAgentApp(_ app: MacAgentApp, didUpdateViewerCount count: Int)
    func macAgentApp(_ app: MacAgentApp, didFailWithError error: Error)
    func macAgentApp(_ app: MacAgentApp, didUpdateFileTransfer fileName: String, progress: Double, isSending: Bool)
    func macAgentApp(_ app: MacAgentApp, didCompleteFileTransfer fileName: String, localURL: URL, isSending: Bool)
    func macAgentApp(_ app: MacAgentApp, didFailFileTransfer fileName: String, error: String, isSending: Bool)
}

public extension MacAgentAppDelegate {
    func macAgentApp(_ app: MacAgentApp, didUpdateFileTransfer fileName: String, progress: Double, isSending: Bool) {}
    func macAgentApp(_ app: MacAgentApp, didCompleteFileTransfer fileName: String, localURL: URL, isSending: Bool) {}
    func macAgentApp(_ app: MacAgentApp, didFailFileTransfer fileName: String, error: String, isSending: Bool) {}
}

/// Core application orchestrator managing ScreenCaptureKit, WebRTC, Bonjour, and Signaling.
public final class MacAgentApp: SignalingClientDelegate, ScreenCapturerDelegate {

    public weak var appDelegate: MacAgentAppDelegate?

    public let serverURL: String
    public let targetFPS: Int
    public let qrOutputPath: String
    public let useTestPattern: Bool
    public let scaleFactor: Double
    public let customRes: (width: Int, height: Int)?
    public let bitrateMbps: Int
    public let maintainResolution: Bool

    private let signalingClient: SignalingClient
    private var webRTCManager: WebRTCManager!
    private let screenCapturer: ScreenCapturer
    private var bonjourAdvertiser: BonjourAdvertiser?

    public private(set) var isBroadcasting: Bool = false
    public private(set) var currentRoomCode: String?
    public private(set) var connectedViewerCount: Int = 0
    public let fileTransferManager: FileTransferManager = FileTransferManager()
    public private(set) var isLowBandwidthMode: Bool = false

    private struct PendingAuth {
        let deviceId: String
        let deviceName: String
        let publicKeyBase64: String
        let nonce: Data
        let saveToTrusted: Bool
    }

    private var pendingAuth: PendingAuth?
    private var authTimeoutTask: Task<Void, Never>?
    private var identityTimeoutTask: Task<Void, Never>?

    public init(
        serverURL: String = "http://localhost:8000",
        targetFPS: Int = 30,
        qrOutputPath: String = "room_qr.png",
        useTestPattern: Bool = false,
        scaleFactor: Double = 1.0,
        customRes: (width: Int, height: Int)? = nil,
        bitrateMbps: Int = 6,
        maintainResolution: Bool = false
    ) {
        self.serverURL = serverURL
        self.targetFPS = targetFPS
        self.qrOutputPath = qrOutputPath
        self.useTestPattern = useTestPattern
        self.scaleFactor = scaleFactor
        self.customRes = customRes
        self.bitrateMbps = bitrateMbps
        self.maintainResolution = maintainResolution

        self.signalingClient = SignalingClient(serverBaseURL: serverURL)
        self.screenCapturer = ScreenCapturer()
        self.screenCapturer.targetFPS = targetFPS
        self.screenCapturer.useTestPattern = useTestPattern
        self.screenCapturer.scaleFactor = scaleFactor
        if let res = customRes {
            self.screenCapturer.customWidth = res.width
            self.screenCapturer.customHeight = res.height
        }

        self.signalingClient.delegate = self
        self.screenCapturer.delegate = self
    }

    // MARK: - Lifecycle

    /// Starts screen capture, signaling, mDNS advertisement, and creates a session room.
    public func startBroadcasting() async throws -> String {
        guard !isBroadcasting else {
            return currentRoomCode ?? ""
        }

        let profileName = maintainResolution ? "High-Definition Crisp (Maintain Resolution)" : "Network-Friendly Adaptive (Tailscale / Cellular)"
        print("🚀 Starting macOS Screen Broadcaster...")
        print("📡 Mode: \(profileName) [Max Bitrate: \(bitrateMbps) Mbps, Scale: \(scaleFactor)x, Degradation: \(maintainResolution ? "maintainResolution" : "balanced")]")

        // 1. Initialize WebRTC stack
        self.webRTCManager = WebRTCManager(signalingClient: signalingClient)
        self.webRTCManager.maxBitrateMbps = bitrateMbps
        self.webRTCManager.minBitrateMbps = max(1, min(2, bitrateMbps / 4))
        self.webRTCManager.degradationPreference = maintainResolution ? .maintainResolution : .balanced

        self.webRTCManager.onLowBandwidthConfigReceived = { [weak self] enabled in
            Task { @MainActor in
                await self?.setLowBandwidthMode(enabled: enabled)
            }
        }

        self.webRTCManager.onClipboardReceived = { [weak self] text in
            DispatchQueue.main.async {
                let pasteboard = NSPasteboard.general
                pasteboard.clearContents()
                pasteboard.setString(text, forType: .string)
                print("[MacAgent] Written received clipboard text to macOS pasteboard (\(text.count) characters)")
                self?.showNotification(title: "Acuity Clipboard", subtitle: "Clipboard received from phone", body: text)
            }
        }

        self.fileTransferManager.delegate = self

        self.webRTCManager.onFileControlReceived = { [weak self] json in
            self?.fileTransferManager.handleControlMessage(json)
        }

        self.webRTCManager.onFileChunkReceived = { [weak self] data in
            self?.fileTransferManager.handleChunkData(data)
        }

        // 2. Create room on signaling server
        print("[Signaling] Requesting new room from \(serverURL)...")
        let roomCode = try await signalingClient.createRoom()
        self.currentRoomCode = roomCode

        // 3. Start mDNS/Bonjour service advertisement
        let signalingPort: Int
        if let url = URL(string: serverURL), let p = url.port {
            signalingPort = p
        } else {
            signalingPort = 8000
        }
        let advertiser = BonjourAdvertiser(roomCode: roomCode, signalingPort: signalingPort)
        advertiser.start()
        self.bonjourAdvertiser = advertiser

        // 4. Generate QR code file
        let joinURL = QRCodeGenerator.makeJoinURL(roomCode: roomCode, serverURL: serverURL)
        QRCodeGenerator.generateQRCode(from: joinURL, outputPath: qrOutputPath)
        QRCodeGenerator.printRoomBanner(roomCode: roomCode, serverURL: serverURL, qrPath: qrOutputPath)

        // 5. Start ScreenCaptureKit display capture
        do {
            try await screenCapturer.startCapture()
        } catch {
            print("❌ Failed to start screen capture: \(error.localizedDescription)")
            advertiser.stop()
            self.bonjourAdvertiser = nil
            self.currentRoomCode = nil
            throw error
        }

        // 6. Connect WebSocket signaling as role=host
        signalingClient.connect(roomCode: roomCode)

        self.isBroadcasting = true
        self.connectedViewerCount = 0

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.appDelegate?.macAgentApp(self, didStartWithRoomCode: roomCode)
            self.appDelegate?.macAgentApp(self, didUpdateViewerCount: 0)
        }

        return roomCode
    }

    /// Stops screen capture, disconnects signaling, terminates Bonjour advertising cleanly.
    public func stopBroadcasting() async {
        guard isBroadcasting else { return }
        print("⏹️ Stopping macOS Screen Broadcaster...")

        await screenCapturer.stopCapture()
        bonjourAdvertiser?.stop()
        bonjourAdvertiser = nil

        cancelIdentityTimeout()
        cancelAuthTimeout()
        pendingAuth = nil

        signalingClient.disconnect()

        self.isBroadcasting = false
        self.currentRoomCode = nil
        self.connectedViewerCount = 0

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.appDelegate?.macAgentAppDidStop(self)
            self.appDelegate?.macAgentApp(self, didUpdateViewerCount: 0)
        }
    }

    // MARK: - Crypto & Nonce Generation

    private func generateCryptoNonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, 32, &bytes)
        if status == errSecSuccess {
            return Data(bytes)
        } else {
            return Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        }
    }

    private func startAuthTimeout() {
        authTimeoutTask?.cancel()
        authTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: 10 * 1_000_000_000)
                guard !Task.isCancelled else { return }
                guard let self = self, let auth = self.pendingAuth else { return }
                print("⏱️ [Security Gate] Auth challenge TIMED OUT (10s) for device '\(auth.deviceName)'. Invalidating auth session.")
                self.pendingAuth = nil
                self.signalingClient.sendError(code: "AUTH_TIMEOUT", message: "Authentication challenge timed out (10s)")
                Task { @MainActor in
                    self.webRTCManager.createPeerConnection()
                }
            } catch {}
        }
    }

    private func cancelAuthTimeout() {
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
    }

    private func cancelIdentityTimeout() {
        identityTimeoutTask?.cancel()
        identityTimeoutTask = nil
    }

    // MARK: - Actions (Clipboard, File Transfer, Low-Bandwidth)

    public func sendClipboardToPhone() {
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            print("[MacAgent] Clipboard is empty, nothing to send")
            return
        }
        webRTCManager?.sendClipboardText(text)
    }

    public func sendFileToPhone(url: URL) {
        fileTransferManager.startSendingFile(url: url, sendChunkHandler: { [weak self] index, data in
            return self?.webRTCManager?.sendFileChunk(index: index, data: data) ?? false
        }, sendControlHandler: { [weak self] json in
            self?.webRTCManager?.sendFileControl(json)
        })
    }

    public func setLowBandwidthMode(enabled: Bool) async {
        self.isLowBandwidthMode = enabled
        if enabled {
            print("[MacAgent] Activating LOW-BANDWIDTH MODE (15 fps, 1.5 Mbps)")
            await screenCapturer.updateFPS(15)
            webRTCManager?.setBitrateCeiling(kbps: 1500)
        } else {
            print("[MacAgent] Deactivating low-bandwidth mode (\(targetFPS) fps, \(bitrateMbps) Mbps)")
            await screenCapturer.updateFPS(targetFPS)
            webRTCManager?.setBitrateCeiling(kbps: bitrateMbps * 1000)
        }
    }

    public func showNotification(title: String, subtitle: String, body: String) {
        let notif = NSUserNotification()
        notif.title = title
        notif.subtitle = subtitle
        notif.informativeText = body
        NSUserNotificationCenter.default.deliver(notif)
    }

    // MARK: - ScreenCapturerDelegate

    public func screenCapturer(_ capturer: ScreenCapturer, didCapture pixelBuffer: CVPixelBuffer, timestamp: CMTime) {
        webRTCManager.pushFrame(pixelBuffer, timestamp: timestamp)
    }

    public func screenCapturer(_ capturer: ScreenCapturer, didFailWithError error: Error) {
        print("❌ Screen capture error: \(error.localizedDescription)")
        appDelegate?.macAgentApp(self, didFailWithError: error)
    }

    // MARK: - SignalingClientDelegate

    public func signalingClientDidConnect(_ client: SignalingClient) {
        print("[Signaling] Host connected and waiting for viewer to join room...")
        webRTCManager.createPeerConnection()
    }

    public func signalingClientDidDisconnect(_ client: SignalingClient, error: Error?) {
        cancelIdentityTimeout()
        cancelAuthTimeout()
        self.pendingAuth = nil
        print("[Signaling] Disconnected from signaling server.")
    }

    public func signalingClient(_ client: SignalingClient, didReceivePeerJoined role: String, deviceId: String?, deviceName: String?) {
        guard role == "viewer" else { return }

        let name = deviceName ?? "Android Device"

        print("\n\u{001B}[1;32m[Viewer Joined]\u{001B}[0m Android device entered room: '\(name)'. Awaiting cryptographic device-identity...")

        // Strictly enforce device-identity message within 3.0s.
        // No unauthenticated fallback: clients without cryptographic identity are rejected immediately.
        cancelIdentityTimeout()
        identityTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: 3 * 1_000_000_000)
                guard !Task.isCancelled else { return }
                guard let self = self else { return }
                print("❌ [Security Gate] Client '\(name)' failed to send device-identity within 3.0s. Rejecting unsupported legacy client.")
                client.sendError(code: "UPDATE_REQUIRED", message: "Unsupported client — please update the Acuity app")
                Task { @MainActor in
                    self.webRTCManager.createPeerConnection()
                }
            } catch {}
        }
    }

    public func signalingClient(_ client: SignalingClient, didReceiveDeviceIdentity deviceId: String, deviceName: String, publicKey: String) {
        cancelIdentityTimeout()
        cancelAuthTimeout()
        self.pendingAuth = nil

        let name = deviceName.isEmpty ? "Android Device" : deviceName
        let fp = deviceId

        print("\n\u{001B}[1;32m[Device Identity]\u{001B}[0m '\(name)' [FP: \(fp.prefix(12))...]")

        // Validate public key presence
        guard !publicKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            print("❌ [Security Gate] Missing public key for '\(name)'! Rejecting connection.")
            client.sendError(code: "AUTH_FAILED", message: "Device public key is required for authentication.")
            return
        }

        // Validate fingerprint matches public key hash (SHA-256)
        guard DeviceApprovalManager.shared.verifyFingerprint(publicKeyBase64: publicKey, claimedFingerprint: fp) else {
            print("❌ [Security Gate] Fingerprint mismatch for '\(name)'! Claimed fingerprint does not match public key hash.")
            client.sendError(code: "AUTH_FAILED", message: "Device fingerprint does not match public key.")
            return
        }

        // Check if device is already trusted with a verified public key
        if let trustedDev = DeviceApprovalManager.shared.getTrustedDevice(fingerprint: fp) {
            // Case 1: Pre-trusted device. Issue single-use challenge to verify private key ownership!
            let nonce = generateCryptoNonce()
            let nonceBase64 = nonce.base64EncodedString()
            self.pendingAuth = PendingAuth(
                deviceId: fp,
                deviceName: name,
                publicKeyBase64: trustedDev.publicKey ?? publicKey,
                nonce: nonce,
                saveToTrusted: true
            )
            startAuthTimeout()
            print("🔒 [Security Gate] Device '\(name)' is pre-authorized. Issuing single-use auth-challenge...")
            client.sendAuthChallenge(nonceBase64: nonceBase64)
            return
        }

        // Case 2: Untrusted (or previously Allow-Once) device. Notify viewer and show native macOS 3-button prompt.
        print("🔒 [Security Gate] Untrusted device '\(name)' requires local host approval.")
        client.sendApprovalPending()

        Task.detached { [weak self] in
            guard let self = self else { return }
            let choice = DeviceApprovalManager.shared.promptUserForApproval(deviceName: name, fingerprint: fp)

            switch choice {
            case .alwaysAllow:
                print("✅ [Security Gate] Host user clicked ALWAYS ALLOW for device '\(name)'. Issuing auth-challenge...")
                let nonce = self.generateCryptoNonce()
                let nonceBase64 = nonce.base64EncodedString()
                self.pendingAuth = PendingAuth(
                    deviceId: fp,
                    deviceName: name,
                    publicKeyBase64: publicKey,
                    nonce: nonce,
                    saveToTrusted: true
                )
                self.startAuthTimeout()
                client.sendAuthChallenge(nonceBase64: nonceBase64)

            case .allowOnce:
                print("✅ [Security Gate] Host user clicked ALLOW ONCE for device '\(name)'. Issuing auth-challenge (session only)...")
                let nonce = self.generateCryptoNonce()
                let nonceBase64 = nonce.base64EncodedString()
                self.pendingAuth = PendingAuth(
                    deviceId: fp,
                    deviceName: name,
                    publicKeyBase64: publicKey,
                    nonce: nonce,
                    saveToTrusted: false
                )
                self.startAuthTimeout()
                client.sendAuthChallenge(nonceBase64: nonceBase64)

            case .deny:
                print("🚫 [Security Gate] Host REJECTED/TIMED OUT device '\(name)'. Sending error...")
                client.sendError(code: "APPROVAL_DENIED", message: "Connection request was denied by the Mac host")
                Task { @MainActor in
                    self.webRTCManager.createPeerConnection()
                }
            }
        }
    }

    public func signalingClient(_ client: SignalingClient, didReceiveAuthResponse nonce: String, signature: String) {
        cancelAuthTimeout()
        guard let auth = pendingAuth else {
            print("⚠️ [Security Gate] Received auth-response with no pending challenge active (or timed out). Dropping.")
            client.sendError(code: "AUTH_FAILED", message: "No active authentication challenge found")
            return
        }

        // Single-use nonce: clear pendingAuth immediately to prevent replay attacks
        self.pendingAuth = nil

        // Verify nonce matches
        guard nonce == auth.nonce.base64EncodedString() else {
            print("❌ [Security Gate] Auth nonce mismatch for '\(auth.deviceName)'! Potential replay attack.")
            client.sendError(code: "AUTH_FAILED", message: "Authentication challenge nonce mismatch.")
            Task { @MainActor in self.webRTCManager.createPeerConnection() }
            return
        }

        // Verify cryptographic ECDSA signature against the public key
        let valid = DeviceApprovalManager.shared.verifySignature(
            publicKeyBase64: auth.publicKeyBase64,
            nonce: auth.nonce,
            signatureBase64: signature
        )

        if valid {
            print("✅ [Security Gate] Cryptographic challenge PASSED for device '\(auth.deviceName)'!")
            if auth.saveToTrusted {
                DeviceApprovalManager.shared.addTrusted(
                    fingerprint: auth.deviceId,
                    name: auth.deviceName,
                    publicKey: auth.publicKeyBase64
                )
                // Non-blocking notification for Always Allow / already-trusted reconnects
                DeviceApprovalManager.shared.showConnectedNotification(deviceName: auth.deviceName)
            } else {
                print("ℹ️ [Security Gate] Device '\(auth.deviceName)' authorized for this session only (Allow Once). Not saved to trusted_devices.json.")
            }

            self.connectedViewerCount += 1
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.appDelegate?.macAgentApp(self, didUpdateViewerCount: self.connectedViewerCount)
            }

            print("🚀 [Security Gate] Device authenticated. Initiating WebRTC SDP negotiation...")
            Task { @MainActor in
                self.webRTCManager.createAndSendOffer()
            }
        } else {
            print("❌ [Security Gate] Cryptographic signature verification FAILED for device '\(auth.deviceName)'. Spoofing attempt blocked!")
            client.sendError(code: "AUTH_FAILED", message: "Cryptographic signature verification failed.")
            Task { @MainActor in
                self.webRTCManager.createPeerConnection()
            }
        }
    }

    public func signalingClient(_ client: SignalingClient, didReceiveAnswer sdp: String) {
        print("[Signaling] Applying remote SDP answer from viewer...")
        webRTCManager.handleRemoteAnswer(sdp: sdp)
    }

    public func signalingClient(_ client: SignalingClient, didReceiveCandidate candidate: String, sdpMid: String, sdpMLineIndex: Int32) {
        webRTCManager.handleRemoteCandidate(candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex)
    }

    public func signalingClient(_ client: SignalingClient, didReceivePeerLeft role: String) {
        cancelIdentityTimeout()
        cancelAuthTimeout()
        self.pendingAuth = nil
        self.connectedViewerCount = max(0, self.connectedViewerCount - 1)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.appDelegate?.macAgentApp(self, didUpdateViewerCount: self.connectedViewerCount)
        }

        print("\n\u{001B}[33m[Viewer Left]\u{001B}[0m Android viewer disconnected. Re-initializing WebRTC peer connection for next session...")
        webRTCManager.createPeerConnection()
    }

    public func signalingClient(_ client: SignalingClient, didReceiveError message: String) {
        print("⚠️ [Signaling Error]: \(message)")
    }
}

// MARK: - FileTransferManagerDelegate

extension MacAgentApp: FileTransferManagerDelegate {
    public func fileTransferDidUpdateProgress(fileName: String, progress: Double, isSending: Bool) {
        let action = isSending ? "Sending" : "Receiving"
        let pct = Int(progress * 100)
        print("[FileTransfer] \(action) \(fileName): \(pct)%")
        appDelegate?.macAgentApp(self, didUpdateFileTransfer: fileName, progress: progress, isSending: isSending)
    }

    public func fileTransferDidComplete(fileName: String, localURL: URL, isSending: Bool) {
        let action = isSending ? "Sent" : "Received"
        print("[FileTransfer] \(action) \(fileName) successfully!")
        showNotification(title: "Acuity File Transfer", subtitle: "\(action) \(fileName)", body: isSending ? "Transfer complete" : "Saved to Downloads/Acuity")
        appDelegate?.macAgentApp(self, didCompleteFileTransfer: fileName, localURL: localURL, isSending: isSending)
    }

    public func fileTransferDidFail(fileName: String, error: String, isSending: Bool) {
        print("[FileTransfer] Error: \(error)")
        showNotification(title: "Acuity File Transfer Failed", subtitle: fileName, body: error)
        appDelegate?.macAgentApp(self, didFailFileTransfer: fileName, error: error, isSending: isSending)
    }
}
