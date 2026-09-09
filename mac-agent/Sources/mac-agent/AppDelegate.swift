import AppKit

/// Application delegate coordinating the menu bar controller, background agent service, and permissions onboarding.
public final class AppDelegate: NSObject, NSApplicationDelegate, MenuBarControllerDelegate, MacAgentAppDelegate {

    private var menuBarController: MenuBarController!
    private var macAgentApp: MacAgentApp!
    private var serverURL: String = "http://localhost:8000"

    public func applicationDidFinishLaunching(_ notification: Notification) {
        // Prevent App Nap / system idling during active screen broadcasting
        ProcessInfo.processInfo.beginActivity(
            options: [.userInitiated, .idleSystemSleepDisabled],
            reason: "Acuity Screen Mirroring Service"
        )

        self.serverURL = ProcessInfo.processInfo.environment["SIGNALING_SERVER_URL"] ?? "http://localhost:8000"

        // 0. Automatically start native embedded signaling server if needed
        EmbeddedSignalingServer.shared.start(port: 8000)

        // 1. Initialize Menu Bar UI
        self.menuBarController = MenuBarController(serverURL: serverURL)
        menuBarController.delegate = self

        // 2. Initialize MacAgentApp core service
        self.macAgentApp = MacAgentApp(serverURL: serverURL, targetFPS: 30, qrOutputPath: "room_qr.png")
        macAgentApp.appDelegate = self

        // 3. Launch Broadcasting
        startBroadcastingTask()
    }

    public func applicationWillTerminate(_ notification: Notification) {
        EmbeddedSignalingServer.shared.stop()
        Task {
            await macAgentApp.stopBroadcasting()
        }
    }

    // MARK: - Broadcasting Tasks

    private func startBroadcastingTask() {
        Task {
            do {
                _ = try await macAgentApp.startBroadcasting()
            } catch {
                print("❌ [AppDelegate] Failed to start broadcasting: \(error.localizedDescription)")
                DispatchQueue.main.async { [weak self] in
                    self?.menuBarController.updateBroadcastingState(isBroadcasting: false, roomCode: nil)
                    PermissionsManager.shared.showWindowIfNeeded { [weak self] in
                        self?.startBroadcastingTask()
                    }
                }
            }
        }
    }

    private func stopBroadcastingTask() {
        Task {
            await macAgentApp.stopBroadcasting()
        }
    }

    // MARK: - MenuBarControllerDelegate

    public func menuBarDidToggleBroadcasting(start: Bool) {
        if start {
            startBroadcastingTask()
        } else {
            stopBroadcastingTask()
        }
    }

    public func menuBarDidRequestPermissionsWindow() {
        PermissionsManager.shared.showWindowIfNeeded { [weak self] in
            guard let self = self else { return }
            if !self.macAgentApp.isBroadcasting {
                self.startBroadcastingTask()
            }
        }
    }

    public func menuBarDidRequestSendClipboard() {
        macAgentApp.sendClipboardToPhone()
    }

    public func menuBarDidRequestSendFile(url: URL) {
        macAgentApp.sendFileToPhone(url: url)
    }

    // MARK: - MacAgentAppDelegate

    public func macAgentApp(_ app: MacAgentApp, didStartWithRoomCode roomCode: String) {
        menuBarController.updateBroadcastingState(
            isBroadcasting: true,
            roomCode: roomCode,
            viewerCount: app.connectedViewerCount
        )
    }

    public func macAgentAppDidStop(_ app: MacAgentApp) {
        menuBarController.updateBroadcastingState(
            isBroadcasting: false,
            roomCode: nil,
            viewerCount: 0
        )
    }

    public func macAgentApp(_ app: MacAgentApp, didUpdateViewerCount count: Int) {
        menuBarController.updateViewerCount(count)
    }

    public func macAgentApp(_ app: MacAgentApp, didFailWithError error: Error) {
        print("⚠️ [AppDelegate] MacAgentApp error: \(error.localizedDescription)")
    }

    public func macAgentApp(_ app: MacAgentApp, didUpdateFileTransfer fileName: String, progress: Double, isSending: Bool) {
        menuBarController.updateFileTransferProgress(fileName: fileName, progress: progress, isSending: isSending)
    }

    public func macAgentApp(_ app: MacAgentApp, didCompleteFileTransfer fileName: String, localURL: URL, isSending: Bool) {
        menuBarController.finishFileTransfer(fileName: fileName, success: true)
    }

    public func macAgentApp(_ app: MacAgentApp, didFailFileTransfer fileName: String, error: String, isSending: Bool) {
        menuBarController.finishFileTransfer(fileName: fileName, success: false)
    }
}

