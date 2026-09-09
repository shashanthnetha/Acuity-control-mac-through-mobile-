import AppKit
import ServiceManagement

public protocol MenuBarControllerDelegate: AnyObject {
    func menuBarDidToggleBroadcasting(start: Bool)
    func menuBarDidRequestPermissionsWindow()
    func menuBarDidRequestSendClipboard()
    func menuBarDidRequestSendFile(url: URL)
}

public extension MenuBarControllerDelegate {
    func menuBarDidRequestSendClipboard() {}
    func menuBarDidRequestSendFile(url: URL) {}
}

/// Controls the macOS menu bar status item (NSStatusItem) and its dropdown menu.
public final class MenuBarController: NSObject, NSMenuDelegate {

    public weak var delegate: MenuBarControllerDelegate?

    private var statusItem: NSStatusItem!
    private var menu: NSMenu!

    private var statusMenuItem: NSMenuItem!
    private var viewerCountMenuItem: NSMenuItem!
    private var copyRoomCodeMenuItem: NSMenuItem!
    private var qrCodeMenuItem: NSMenuItem!
    private var toggleBroadcastMenuItem: NSMenuItem!
    private var trustedDevicesMenu: NSMenu!
    private var trustedDevicesMenuItem: NSMenuItem!
    private var sendClipboardMenuItem: NSMenuItem!
    private var sendFileMenuItem: NSMenuItem!
    private var fileTransferProgressMenuItem: NSMenuItem!
    private var launchAtLoginMenuItem: NSMenuItem!

    private var popover: NSPopover?
    private var qrPopoverVC: QRPopoverViewController?

    private var isBroadcasting = false
    private var currentRoomCode: String?
    private var connectedViewers = 0
    private let serverURL: String

    public init(serverURL: String) {
        self.serverURL = serverURL
        super.init()
        setupStatusItem()
        setupMenu()
        updateUI()
    }

    // MARK: - Setup

    private func setupStatusItem() {
        self.statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem.button {
            button.image = createMenuBarIcon()
            button.imagePosition = .imageOnly
            button.toolTip = "Acuity Screen Broadcaster"
        }
    }

    private func createMenuBarIcon() -> NSImage {
        // Try loading bundled MenuBarIcon template image first
        if let bundleImage = Bundle.main.image(forResource: "MenuBarIcon") {
            bundleImage.isTemplate = true
            return bundleImage
        }

        // Custom Focus Frame vector mark (18x18 monochrome template)
        let img = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { rect in
            NSColor.black.setStroke()
            NSColor.black.setFill()

            let inset: CGFloat = 3.0
            let arm: CGFloat = 4.0
            let stroke: CGFloat = 1.6
            let size: CGFloat = 18.0

            func drawBracket(x: CGFloat, y: CGFloat, dx: CGFloat, dy: CGFloat) {
                let path = NSBezierPath()
                path.lineCapStyle = .round
                path.lineJoinStyle = .round
                path.lineWidth = stroke
                path.move(to: NSPoint(x: x, y: y + dy * arm))
                path.line(to: NSPoint(x: x, y: y))
                path.line(to: NSPoint(x: x + dx * arm, y: y))
                path.stroke()
            }

            // Top-Left
            drawBracket(x: inset, y: size - inset, dx: 1, dy: -1)
            // Top-Right
            drawBracket(x: size - inset, y: size - inset, dx: -1, dy: -1)
            // Bottom-Left
            drawBracket(x: inset, y: inset, dx: 1, dy: 1)
            // Bottom-Right
            drawBracket(x: size - inset, y: inset, dx: -1, dy: 1)

            // Center Dot
            let dotRect = NSRect(x: 9.0 - 1.25, y: 9.0 - 1.25, width: 2.5, height: 2.5)
            let dotPath = NSBezierPath(ovalIn: dotRect)
            dotPath.fill()

            return true
        }
        img.isTemplate = true
        return img
    }

    private func setupMenu() {
        self.menu = NSMenu()
        menu.delegate = self
        menu.autoenablesItems = false

        // 1. Status Header
        self.statusMenuItem = NSMenuItem(title: "○ Broadcast: IDLE", action: nil, keyEquivalent: "")
        statusMenuItem.isEnabled = false
        menu.addItem(statusMenuItem)

        // 1b. Viewer Count Info
        self.viewerCountMenuItem = NSMenuItem(title: "  Connected Viewers: 0", action: nil, keyEquivalent: "")
        viewerCountMenuItem.isEnabled = false
        viewerCountMenuItem.isHidden = true
        menu.addItem(viewerCountMenuItem)

        // 1c. Copy Room Code
        self.copyRoomCodeMenuItem = NSMenuItem(title: "Copy Room Code", action: #selector(copyRoomCodeClicked), keyEquivalent: "c")
        copyRoomCodeMenuItem.target = self
        copyRoomCodeMenuItem.isHidden = true
        menu.addItem(copyRoomCodeMenuItem)

        menu.addItem(NSMenuItem.separator())

        // 2. Show QR Code
        self.qrCodeMenuItem = NSMenuItem(title: "Show Pairing QR Code...", action: #selector(showQRCodeClicked), keyEquivalent: "p")
        qrCodeMenuItem.target = self
        menu.addItem(qrCodeMenuItem)

        // 3. Start / Stop Broadcasting Toggle
        self.toggleBroadcastMenuItem = NSMenuItem(title: "Start Broadcasting", action: #selector(toggleBroadcastClicked), keyEquivalent: "b")
        toggleBroadcastMenuItem.target = self
        menu.addItem(toggleBroadcastMenuItem)

        menu.addItem(NSMenuItem.separator())

        // 3b. Clipboard & File Transfer Actions
        self.sendClipboardMenuItem = NSMenuItem(title: "Send Clipboard to Phone", action: #selector(sendClipboardClicked), keyEquivalent: "")
        sendClipboardMenuItem.target = self
        sendClipboardMenuItem.isEnabled = false
        menu.addItem(sendClipboardMenuItem)

        self.sendFileMenuItem = NSMenuItem(title: "Send File to Phone...", action: #selector(sendFileClicked), keyEquivalent: "")
        sendFileMenuItem.target = self
        sendFileMenuItem.isEnabled = false
        menu.addItem(sendFileMenuItem)

        self.fileTransferProgressMenuItem = NSMenuItem(title: "  Transfer: Idle", action: nil, keyEquivalent: "")
        fileTransferProgressMenuItem.isEnabled = false
        fileTransferProgressMenuItem.isHidden = true
        menu.addItem(fileTransferProgressMenuItem)

        menu.addItem(NSMenuItem.separator())

        // 4. Trusted Devices Submenu
        self.trustedDevicesMenuItem = NSMenuItem(title: "Trusted Devices", action: nil, keyEquivalent: "")
        self.trustedDevicesMenu = NSMenu()
        trustedDevicesMenuItem.submenu = trustedDevicesMenu
        menu.addItem(trustedDevicesMenuItem)

        menu.addItem(NSMenuItem.separator())

        // 5. Check Permissions Window
        let permItem = NSMenuItem(title: "Permissions Setup...", action: #selector(permissionsClicked), keyEquivalent: "")
        permItem.target = self
        menu.addItem(permItem)

        // 6. Launch at Login
        self.launchAtLoginMenuItem = NSMenuItem(title: "Launch at Login", action: #selector(toggleLaunchAtLogin), keyEquivalent: "")
        launchAtLoginMenuItem.target = self
        menu.addItem(launchAtLoginMenuItem)

        menu.addItem(NSMenuItem.separator())

        // 7. Quit
        let quitItem = NSMenuItem(title: "Quit Acuity", action: #selector(quitClicked), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        self.statusItem.menu = menu
    }

    // MARK: - State Updates

    public func updateBroadcastingState(isBroadcasting: Bool, roomCode: String?, viewerCount: Int = 0) {
        self.isBroadcasting = isBroadcasting
        self.currentRoomCode = roomCode
        self.connectedViewers = viewerCount

        DispatchQueue.main.async { [weak self] in
            self?.updateUI()
        }
    }

    public func updateViewerCount(_ count: Int) {
        self.connectedViewers = count
        DispatchQueue.main.async { [weak self] in
            self?.updateUI()
        }
    }

    private func updateUI() {
        if isBroadcasting, let code = currentRoomCode {
            statusMenuItem.title = "● Broadcast: ACTIVE · Room: \(code)"
            viewerCountMenuItem.isHidden = false
            let deviceWord = connectedViewers == 1 ? "device" : "devices"
            viewerCountMenuItem.title = "  Connected: \(connectedViewers) \(deviceWord)"

            copyRoomCodeMenuItem.title = "Copy Room Code (\(code))"
            copyRoomCodeMenuItem.isHidden = false

            toggleBroadcastMenuItem.title = "Stop Broadcasting"
            qrCodeMenuItem.isEnabled = true

            qrPopoverVC?.update(roomCode: code, serverURL: serverURL)
        } else {
            statusMenuItem.title = "○ Broadcast: IDLE"
            viewerCountMenuItem.isHidden = true
            copyRoomCodeMenuItem.isHidden = true
            toggleBroadcastMenuItem.title = "Start Broadcasting"
            qrCodeMenuItem.isEnabled = false

            popover?.close()
        }

        // Update Launch at Login checkmark
        let isLoginEnabled = (SMAppService.mainApp.status == .enabled)
        launchAtLoginMenuItem.state = isLoginEnabled ? .on : .off

        // Enable or disable viewer-dependent actions
        let canInteractWithViewer = isBroadcasting && connectedViewers > 0
        sendClipboardMenuItem.isEnabled = canInteractWithViewer
        sendFileMenuItem.isEnabled = canInteractWithViewer

        rebuildTrustedDevicesSubmenu()
    }

    private func rebuildTrustedDevicesSubmenu() {
        trustedDevicesMenu.removeAllItems()

        let devices = DeviceApprovalManager.shared.getTrustedDevices()
        trustedDevicesMenuItem.title = "Trusted Devices (\(devices.count))"

        if devices.isEmpty {
            let emptyItem = NSMenuItem(title: "No trusted devices", action: nil, keyEquivalent: "")
            emptyItem.isEnabled = false
            trustedDevicesMenu.addItem(emptyItem)
            return
        }

        for dev in devices {
            let shortFp = dev.fingerprint.count > 10 ? String(dev.fingerprint.prefix(8)) + "..." : dev.fingerprint
            let devItem = NSMenuItem(title: "\(dev.name) (\(shortFp))", action: nil, keyEquivalent: "")
            let sub = NSMenu()

            let approvedItem = NSMenuItem(title: "Approved: \(dev.approvedAt)", action: nil, keyEquivalent: "")
            approvedItem.isEnabled = false
            sub.addItem(approvedItem)

            let seenItem = NSMenuItem(title: "Last Seen: \(dev.lastSeenAt)", action: nil, keyEquivalent: "")
            seenItem.isEnabled = false
            sub.addItem(seenItem)

            sub.addItem(NSMenuItem.separator())

            let revokeItem = NSMenuItem(title: "🗑️ Revoke Device", action: #selector(revokeDeviceClicked(_:)), keyEquivalent: "")
            revokeItem.target = self
            revokeItem.representedObject = dev.fingerprint
            sub.addItem(revokeItem)

            devItem.submenu = sub
            trustedDevicesMenu.addItem(devItem)
        }

        trustedDevicesMenu.addItem(NSMenuItem.separator())

        let revokeAllItem = NSMenuItem(title: "🗑️ Revoke All Devices", action: #selector(revokeAllClicked), keyEquivalent: "")
        revokeAllItem.target = self
        trustedDevicesMenu.addItem(revokeAllItem)
    }

    // MARK: - NSMenuDelegate

    public func menuWillOpen(_ menu: NSMenu) {
        rebuildTrustedDevicesSubmenu()
        let isLoginEnabled = (SMAppService.mainApp.status == .enabled)
        launchAtLoginMenuItem.state = isLoginEnabled ? .on : .off
    }

    // MARK: - Menu Actions

    @objc private func toggleBroadcastClicked() {
        delegate?.menuBarDidToggleBroadcasting(start: !isBroadcasting)
    }

    @objc private func copyRoomCodeClicked() {
        guard let code = currentRoomCode else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(code, forType: .string)
    }

    @objc private func showQRCodeClicked() {
        guard let button = statusItem.button, let code = currentRoomCode else { return }

        if let existing = popover, existing.isShown {
            existing.close()
            return
        }

        let popover = NSPopover()
        popover.behavior = .transient
        let vc = QRPopoverViewController(roomCode: code, serverURL: serverURL)
        popover.contentViewController = vc
        self.qrPopoverVC = vc
        self.popover = popover

        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
    }

    @objc private func revokeDeviceClicked(_ sender: NSMenuItem) {
        guard let fp = sender.representedObject as? String else { return }
        DeviceApprovalManager.shared.revokeDevice(target: fp)
        rebuildTrustedDevicesSubmenu()
    }

    @objc private func revokeAllClicked() {
        DeviceApprovalManager.shared.revokeDevice(target: "all")
        rebuildTrustedDevicesSubmenu()
    }

    @objc private func permissionsClicked() {
        delegate?.menuBarDidRequestPermissionsWindow()
    }

    @objc private func toggleLaunchAtLogin() {
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
                print("ℹ️ [LaunchAtLogin] Unregistered Acuity from Login Items")
            } else {
                try SMAppService.mainApp.register()
                print("✅ [LaunchAtLogin] Registered Acuity to launch at login")
            }
        } catch {
            print("⚠️ [LaunchAtLogin] Failed to toggle login item: \(error.localizedDescription)")
        }
        let isLoginEnabled = (SMAppService.mainApp.status == .enabled)
        launchAtLoginMenuItem.state = isLoginEnabled ? .on : .off
    }

    @objc private func sendClipboardClicked() {
        delegate?.menuBarDidRequestSendClipboard()
    }

    @objc private func sendFileClicked() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.title = "Select File to Send to Phone"
        panel.prompt = "Send"
        if panel.runModal() == .OK, let url = panel.url {
            delegate?.menuBarDidRequestSendFile(url: url)
        }
    }

    public func updateFileTransferProgress(fileName: String, progress: Double, isSending: Bool) {
        DispatchQueue.main.async { [weak self] in
            let action = isSending ? "Sending" : "Receiving"
            let pct = Int(progress * 100)
            self?.fileTransferProgressMenuItem.title = "  \(action) \(fileName) (\(pct)%)"
            self?.fileTransferProgressMenuItem.isHidden = false
        }
    }

    public func finishFileTransfer(fileName: String, success: Bool) {
        DispatchQueue.main.async { [weak self] in
            self?.fileTransferProgressMenuItem.title = success ? "  Transfer complete: \(fileName)" : "  Transfer failed: \(fileName)"
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
                self?.fileTransferProgressMenuItem.isHidden = true
            }
        }
    }

    @objc private func quitClicked() {
        NSApplication.shared.terminate(nil)
    }
}
