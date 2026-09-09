import Foundation
import CoreGraphics
import ApplicationServices

/// Responsible for processing input events received over the WebRTC DataChannel
/// and injecting them into macOS as Quartz CGEvents (mouse, scroll, and keyboard).
public final class InputInjector {

    // MARK: - State & Safety Guards

    private var isDragging: Bool = false
    private var lastDragPoint: CGPoint = .zero
    private var watchdogWorkItem: DispatchWorkItem?

    // Global Rate Limiter: max ~120 events/sec
    // Known limitation: single global cap, so an intense scroll burst could temporarily delay a tap
    private var eventCount: Int = 0
    private var windowStart: Date = Date()
    private let maxEventsPerSecond: Int = 120

    private var hasLoggedAccessibilityWarning: Bool = false

    public init() {}

    // MARK: - Accessibility Permissions

    /// Checks if the process has macOS Accessibility control permissions.
    @discardableResult
    public static func checkAccessibility(prompt: Bool = true) -> Bool {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: prompt] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    // MARK: - Message Handling

    /// Dispatches a raw JSON string received over the DataChannel.
    public func handleMessage(_ jsonString: String) {
        guard shouldAllowEvent() else {
            // Throttled by rate limiter
            return
        }

        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }

        // Verify accessibility permission once
        if !AXIsProcessTrusted() {
            if !hasLoggedAccessibilityWarning {
                hasLoggedAccessibilityWarning = true
                print("""
                \u{001B}[1;33m⚠️ [Accessibility Permission Required]\u{001B}[0m
                   mac-agent cannot inject input because macOS Accessibility permission is missing.
                   👉 Go to: System Settings > Privacy & Security > Accessibility
                   👉 Enable Terminal (or mac-agent) to allow mouse/keyboard control.
                """)
            }
            return
        }

        DispatchQueue.main.async { [weak self] in
            self?.processEvent(type: type, json: json)
        }
    }

    private func processEvent(type: String, json: [String: Any]) {
        let x = (json["x"] as? NSNumber)?.doubleValue ?? 0.5
        let y = (json["y"] as? NSNumber)?.doubleValue ?? 0.5
        let point = screenPoint(fromNormX: x, normY: y)

        switch type {
        case "tap":
            injectTap(at: point)

        case "right_tap":
            injectRightTap(at: point)

        case "drag_start":
            injectDragStart(at: point)

        case "drag_move":
            injectDragMove(at: point)

        case "drag_end":
            injectDragEnd(at: point)

        case "scroll":
            let dx = (json["dx"] as? NSNumber)?.doubleValue ?? 0.0
            let dy = (json["dy"] as? NSNumber)?.doubleValue ?? 0.0
            injectScroll(dx: dx, dy: dy)

        case "key":
            let text = json["text"] as? String
            let keyCode = (json["keyCode"] as? NSNumber)?.intValue ?? 0
            let modifiers = json["modifiers"] as? [String] ?? []
            injectKey(text: text, keyCode: keyCode, modifiers: modifiers)

        default:
            break
        }
    }

    // MARK: - Mouse & Touch Injection

    private func injectTap(at point: CGPoint) {
        // Cancel any lingering drag before tap
        if isDragging {
            forceReleaseDrag()
        }

        guard let down = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left),
              let up = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left) else {
            return
        }

        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
    }

    private func injectRightTap(at point: CGPoint) {
        if isDragging {
            forceReleaseDrag()
        }

        guard let down = CGEvent(mouseEventSource: nil, mouseType: .rightMouseDown, mouseCursorPosition: point, mouseButton: .right),
              let up = CGEvent(mouseEventSource: nil, mouseType: .rightMouseUp, mouseCursorPosition: point, mouseButton: .right) else {
            return
        }

        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
    }

    private func injectDragStart(at point: CGPoint) {
        guard let down = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left) else {
            return
        }

        isDragging = true
        lastDragPoint = point
        down.post(tap: .cghidEventTap)
        startOrResetWatchdog()
    }

    private func injectDragMove(at point: CGPoint) {
        guard isDragging else {
            // If drag_start was missed, initiate drag now
            injectDragStart(at: point)
            return
        }

        lastDragPoint = point
        guard let move = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged, mouseCursorPosition: point, mouseButton: .left) else {
            return
        }

        move.post(tap: .cghidEventTap)
        startOrResetWatchdog()
    }

    private func injectDragEnd(at point: CGPoint) {
        isDragging = false
        watchdogWorkItem?.cancel()
        watchdogWorkItem = nil

        guard let up = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left) else {
            return
        }

        up.post(tap: .cghidEventTap)
    }

    // MARK: - Scroll Injection

    private func injectScroll(dx: Double, dy: Double) {
        // macOS scroll deltas: wheel1 is vertical, wheel2 is horizontal
        // Invert dy so swiping up scrolls content down (standard natural feel)
        let pixelDy = Int32(dy)
        let pixelDx = Int32(dx)

        guard let scroll = CGEvent(
            scrollWheelEvent2Source: nil,
            units: .pixel,
            wheelCount: 2,
            wheel1: pixelDy,
            wheel2: pixelDx,
            wheel3: 0
        ) else {
            return
        }

        scroll.post(tap: .cghidEventTap)
    }

    // MARK: - Keyboard Injection

    private func injectKey(text: String?, keyCode: Int, modifiers: [String] = []) {
        var flags: CGEventFlags = []
        for mod in modifiers {
            switch mod.lowercased() {
            case "cmd", "command": flags.insert(.maskCommand)
            case "opt", "option", "alt": flags.insert(.maskAlternate)
            case "ctrl", "control": flags.insert(.maskControl)
            case "shift": flags.insert(.maskShift)
            default: break
            }
        }

        if let rawText = text, !rawText.isEmpty {
            // Cap accepted text length at 500 characters as a sanity guard
            let cappedText = String(rawText.prefix(500))
            var utf16 = Array(cappedText.utf16)

            if let down = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true),
               let up = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false) {
                if !flags.isEmpty {
                    down.flags = flags
                    up.flags = flags
                }
                down.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: &utf16)
                up.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: &utf16)
                down.post(tap: .cghidEventTap)
                up.post(tap: .cghidEventTap)
            }
        } else if keyCode > 0 {
            // Standard macOS virtual keycodes (e.g. 51 = Backspace, 36 = Return)
            let vk = CGKeyCode(keyCode)
            if let down = CGEvent(keyboardEventSource: nil, virtualKey: vk, keyDown: true),
               let up = CGEvent(keyboardEventSource: nil, virtualKey: vk, keyDown: false) {
                if !flags.isEmpty {
                    down.flags = flags
                    up.flags = flags
                }
                down.post(tap: .cghidEventTap)
                up.post(tap: .cghidEventTap)
            }
        }
    }

    // MARK: - Helpers & Watchdog

    /// Converts normalized [0.0, 1.0] coordinates to main display point coordinates.
    private func screenPoint(fromNormX x: Double, normY y: Double) -> CGPoint {
        let mainDisplay = CGMainDisplayID()
        let w = CGFloat(CGDisplayPixelsWide(mainDisplay))
        let h = CGFloat(CGDisplayPixelsHigh(mainDisplay))
        let clampedX = max(0.0, min(1.0, CGFloat(x)))
        let clampedY = max(0.0, min(1.0, CGFloat(y)))
        return CGPoint(x: clampedX * w, y: clampedY * h)
    }

    /// Rate limiter check: caps events at maxEventsPerSecond.
    private func shouldAllowEvent() -> Bool {
        let now = Date()
        if now.timeIntervalSince(windowStart) >= 1.0 {
            windowStart = now
            eventCount = 0
        }
        if eventCount >= maxEventsPerSecond {
            return false
        }
        eventCount += 1
        return true
    }

    /// Watchdog timer to prevent stuck mouseDown if client disconnects mid-drag.
    private func startOrResetWatchdog() {
        watchdogWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self = self, self.isDragging else { return }
            print("\u{001B}[33m[InputInjector] Watchdog timeout (2s): Force-releasing stuck drag.\u{001B}[0m")
            self.forceReleaseDrag()
        }
        watchdogWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0, execute: item)
    }

    /// Releases any active mouse-down state.
    public func forceReleaseDrag() {
        if isDragging {
            if let up = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: lastDragPoint, mouseButton: .left) {
                up.post(tap: .cghidEventTap)
            }
            isDragging = false
        }
        watchdogWorkItem?.cancel()
        watchdogWorkItem = nil
    }

    /// Resets injector state (called on DataChannel disconnect).
    public func reset() {
        forceReleaseDrag()
    }
}
