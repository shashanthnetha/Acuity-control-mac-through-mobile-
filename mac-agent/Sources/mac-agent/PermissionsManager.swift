import AppKit
import CoreGraphics
import ApplicationServices
import ScreenCaptureKit

/// Manages macOS permissions for ScreenCaptureKit (Screen Recording) and Accessibility (Input Injection).
public final class PermissionsManager: NSWindowController {

    public static let shared = PermissionsManager()

    public var onPermissionsGranted: (() -> Void)?

    private var screenRecordStatusLabel: NSTextField!
    private var screenRecordButton: NSButton!
    private var accessibilityStatusLabel: NSTextField!
    private var accessibilityButton: NSButton!
    private var continueButton: NSButton!

    public static var screenRecordingCached: Bool = false

    private init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 400),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Acuity Permissions Setup"
        window.center()
        window.isReleasedWhenClosed = false
        window.appearance = NSAppearance(named: .darkAqua)
        window.backgroundColor = NSColor(srgbRed: 0x0A/255.0, green: 0x0E/255.0, blue: 0x14/255.0, alpha: 1.0)
        super.init(window: window)

        setupUI()
        NotificationCenter.default.addObserver(self, selector: #selector(appDidBecomeActive), name: NSApplication.didBecomeActiveNotification, object: nil)
    }

    @objc private func appDidBecomeActive() {
        refreshUI()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    // MARK: - Permission Checks

    public static func hasScreenRecordingPermission() -> Bool {
        if screenRecordingCached { return true }
        if CGPreflightScreenCaptureAccess() {
            screenRecordingCached = true
            return true
        }
        return false
    }

    public static func hasAccessibilityPermission() -> Bool {
        return AXIsProcessTrusted()
    }

    public static func hasAllPermissions() -> Bool {
        return hasScreenRecordingPermission() && hasAccessibilityPermission()
    }

    public static func checkScreenRecordingAsync(completion: @escaping (Bool) -> Void) {
        if CGPreflightScreenCaptureAccess() {
            screenRecordingCached = true
            completion(true)
            return
        }

        Task {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                let ok = !content.displays.isEmpty
                screenRecordingCached = ok
                DispatchQueue.main.async {
                    completion(ok)
                }
            } catch {
                DispatchQueue.main.async {
                    completion(false)
                }
            }
        }
    }

    public static func requestAccessibilityPrompt() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }

    public static func requestScreenRecordingPrompt() {
        _ = CGRequestScreenCaptureAccess()
    }

    public static func openScreenCaptureSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(url)
        }
    }

    public static func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: - Window Management

    public func showWindowIfNeeded(completion: (() -> Void)? = nil) {
        self.onPermissionsGranted = completion
        refreshUI()
        self.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    // MARK: - UI Construction

    private func setupUI() {
        guard let window = self.window else { return }

        let contentView = NSView(frame: window.contentView!.bounds)
        contentView.autoresizingMask = [.width, .height]
        window.contentView = contentView

        // Header Title
        let titleLabel = NSTextField(labelWithString: "Welcome to Acuity")
        titleLabel.font = NSFont.systemFont(ofSize: 20, weight: .bold)
        titleLabel.textColor = NSColor(srgbRed: 0xF0/255.0, green: 0xF6/255.0, blue: 0xFC/255.0, alpha: 1.0)
        titleLabel.frame = NSRect(x: 24, y: 340, width: 432, height: 28)
        contentView.addSubview(titleLabel)

        // Subtitle
        let subtitleLabel = NSTextField(wrappingLabelWithString: "Acuity requires two macOS permissions to mirror your display and receive mouse/touch inputs from your Android device.")
        subtitleLabel.font = NSFont.systemFont(ofSize: 13, weight: .regular)
        subtitleLabel.textColor = NSColor(srgbRed: 0x8B/255.0, green: 0x94/255.0, blue: 0x9E/255.0, alpha: 1.0)
        subtitleLabel.frame = NSRect(x: 24, y: 285, width: 432, height: 48)
        contentView.addSubview(subtitleLabel)

        // Card 1: Screen Recording
        let screenBox = createCardBox(
            y: 195,
            title: "Screen Recording",
            desc: "Streams your display to your Android device with low latency.",
            buttonTitle: "Open Settings",
            buttonAction: #selector(screenRecordButtonClicked)
        )
        contentView.addSubview(screenBox.box)
        self.screenRecordStatusLabel = screenBox.statusLabel
        self.screenRecordButton = screenBox.actionButton

        // Card 2: Accessibility
        let accessBox = createCardBox(
            y: 95,
            title: "Accessibility",
            desc: "Injects remote touch, cursor moves, and clicks into macOS.",
            buttonTitle: "Open Settings",
            buttonAction: #selector(accessibilityButtonClicked)
        )
        contentView.addSubview(accessBox.box)
        self.accessibilityStatusLabel = accessBox.statusLabel
        self.accessibilityButton = accessBox.actionButton

        // Bottom Bar: Check Again + Continue Button
        let checkAgainButton = NSButton(title: "Check Again", target: self, action: #selector(checkAgainClicked))
        checkAgainButton.bezelStyle = .rounded
        checkAgainButton.frame = NSRect(x: 24, y: 24, width: 120, height: 32)
        contentView.addSubview(checkAgainButton)

        let continueBtn = NSButton(title: "Start Acuity", target: self, action: #selector(continueClicked))
        continueBtn.bezelStyle = .rounded
        continueBtn.keyEquivalent = "\r"
        continueBtn.frame = NSRect(x: 336, y: 24, width: 120, height: 32)
        contentView.addSubview(continueBtn)
        self.continueButton = continueBtn

        refreshUI()
    }

    private func createCardBox(
        y: CGFloat,
        title: String,
        desc: String,
        buttonTitle: String,
        buttonAction: Selector
    ) -> (box: NSBox, statusLabel: NSTextField, actionButton: NSButton) {
        let box = NSBox(frame: NSRect(x: 24, y: y, width: 432, height: 82))
        box.boxType = .custom
        box.borderWidth = 1.0
        box.borderColor = NSColor(srgbRed: 0x20/255.0, green: 0x26/255.0, blue: 0x33/255.0, alpha: 1.0)
        box.cornerRadius = 8.0
        box.fillColor = NSColor(srgbRed: 0x15/255.0, green: 0x1A/255.0, blue: 0x24/255.0, alpha: 1.0)

        let titleLabel = NSTextField(labelWithString: title)
        titleLabel.font = NSFont.systemFont(ofSize: 14, weight: .semibold)
        titleLabel.textColor = NSColor(srgbRed: 0xF0/255.0, green: 0xF6/255.0, blue: 0xFC/255.0, alpha: 1.0)
        titleLabel.frame = NSRect(x: 16, y: 50, width: 250, height: 20)
        box.contentView?.addSubview(titleLabel)

        let descLabel = NSTextField(wrappingLabelWithString: desc)
        descLabel.font = NSFont.systemFont(ofSize: 11, weight: .regular)
        descLabel.textColor = NSColor(srgbRed: 0x8B/255.0, green: 0x94/255.0, blue: 0x9E/255.0, alpha: 1.0)
        descLabel.frame = NSRect(x: 16, y: 12, width: 260, height: 36)
        box.contentView?.addSubview(descLabel)

        let statusLabel = NSTextField(labelWithString: "Checking...")
        statusLabel.font = NSFont.monospacedSystemFont(ofSize: 11, weight: .semibold)
        statusLabel.alignment = .right
        statusLabel.frame = NSRect(x: 280, y: 50, width: 136, height: 20)
        box.contentView?.addSubview(statusLabel)

        let actionBtn = NSButton(title: buttonTitle, target: self, action: buttonAction)
        actionBtn.bezelStyle = .rounded
        actionBtn.controlSize = .small
        actionBtn.font = NSFont.systemFont(ofSize: 11)
        actionBtn.frame = NSRect(x: 290, y: 16, width: 126, height: 26)
        box.contentView?.addSubview(actionBtn)

        return (box, statusLabel, actionBtn)
    }

    // MARK: - Actions

    @objc private func screenRecordButtonClicked() {
        Self.requestScreenRecordingPrompt()
        Self.openScreenCaptureSettings()
    }

    @objc private func accessibilityButtonClicked() {
        Self.requestAccessibilityPrompt()
        Self.openAccessibilitySettings()
    }

    @objc private func checkAgainClicked() {
        refreshUI()
        Self.checkScreenRecordingAsync { [weak self] screenOk in
            if screenOk && Self.hasAccessibilityPermission() {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                    self?.continueClicked()
                }
            }
        }
    }

    @objc private func continueClicked() {
        self.window?.orderOut(nil)
        onPermissionsGranted?()
    }

    public func refreshUI() {
        let activeColor = NSColor(srgbRed: 0x10/255.0, green: 0xB9/255.0, blue: 0x81/255.0, alpha: 1.0)
        let requiredColor = NSColor(srgbRed: 0x4D/255.0, green: 0xD0/255.0, blue: 0xFF/255.0, alpha: 1.0)

        // 1. Accessibility Check
        let hasAccess = Self.hasAccessibilityPermission()
        if hasAccess {
            accessibilityStatusLabel.stringValue = "● ACTIVE"
            accessibilityStatusLabel.textColor = activeColor
            accessibilityButton.isEnabled = false
            accessibilityButton.title = "Enabled"
        } else {
            accessibilityStatusLabel.stringValue = "⚠️ SETUP"
            accessibilityStatusLabel.textColor = requiredColor
            accessibilityButton.isEnabled = true
            accessibilityButton.title = "Open Settings"
        }

        // 2. Screen Recording Check (Sync + Async verification)
        if Self.hasScreenRecordingPermission() {
            screenRecordStatusLabel.stringValue = "● ACTIVE"
            screenRecordStatusLabel.textColor = activeColor
            screenRecordButton.isEnabled = false
            screenRecordButton.title = "Enabled"

            if hasAccess {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                    self?.continueClicked()
                }
            }
        } else {
            screenRecordStatusLabel.stringValue = "Verifying..."
            screenRecordStatusLabel.textColor = .secondaryLabelColor
            Self.checkScreenRecordingAsync { [weak self] granted in
                guard let self = self else { return }
                if granted {
                    self.screenRecordStatusLabel.stringValue = "● ACTIVE"
                    self.screenRecordStatusLabel.textColor = activeColor
                    self.screenRecordButton.isEnabled = false
                    self.screenRecordButton.title = "Enabled"

                    if Self.hasAccessibilityPermission() {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                            self?.continueClicked()
                        }
                    }
                } else {
                    self.screenRecordStatusLabel.stringValue = "⚠️ SETUP"
                    self.screenRecordStatusLabel.textColor = requiredColor
                    self.screenRecordButton.isEnabled = true
                    self.screenRecordButton.title = "Open Settings"
                }
            }
        }

        // 3. Start Acuity Button: ALWAYS ENABLED so user is never trapped!
        continueButton.isEnabled = true
        continueButton.title = "Start Acuity"
    }
}
