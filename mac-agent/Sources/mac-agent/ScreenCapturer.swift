import Foundation
import ScreenCaptureKit
import CoreMedia
import CoreVideo
import CoreGraphics
#if canImport(AppKit)
import AppKit
#endif

/// Protocol for receiving captured screen video frames.
public protocol ScreenCapturerDelegate: AnyObject {
    func screenCapturer(_ capturer: ScreenCapturer, didCapture pixelBuffer: CVPixelBuffer, timestamp: CMTime)
    func screenCapturer(_ capturer: ScreenCapturer, didFailWithError error: Error)
}

/// ScreenCaptureKit-based display capturer with optional synthetic test pattern fallback for CI/headless verification.
public final class ScreenCapturer: NSObject, SCStreamOutput, SCStreamDelegate {

    public weak var delegate: ScreenCapturerDelegate?

    private var stream: SCStream?
    private let videoOutputQueue = DispatchQueue(label: "com.macky.mac-agent.videocapture", qos: .userInteractive)
    private var isCapturing = false

    public var targetFPS: Int = 30
    public var useTestPattern: Bool = false
    public var scaleFactor: Double = 1.0
    public var customWidth: Int? = nil
    public var customHeight: Int? = nil
    public private(set) var displayWidth: Int = 1920
    public private(set) var displayHeight: Int = 1080

    private var mockTimer: DispatchSourceTimer?
    private var mockFrameCount: Int = 0

    public override init() {
        super.init()
    }

    /// Verifies if screen capture access is authorized by macOS.
    /// If not granted, requests permission and returns false.
    public static func checkPermission() -> Bool {
        if CGPreflightScreenCaptureAccess() {
            return true
        }

        // Trigger system permission prompt if not yet granted
        let requested = CGRequestScreenCaptureAccess()
        if !requested {
            print("\n" + String(repeating: "!", count: 65))
            print("  ❌ SCREEN RECORDING PERMISSION REQUIRED")
            print(String(repeating: "!", count: 65))
            print("  macOS requires explicit Screen Recording permission for this app.")
            print("  Please enable permission in:")
            print("  👉 System Settings > Privacy & Security > Screen & System Audio Recording")
            print("     (or Privacy & Security > Screen Recording)")
            print("  After enabling, re-run this command.")
            print("  Tip: You can also pass --test-pattern to test WebRTC streaming without permissions.")
            print(String(repeating: "!", count: 65) + "\n")
        }
        return false
    }

    /// Starts capturing the primary display at its high-definition resolution at targetFPS.
    public func startCapture() async throws {
        guard !isCapturing else { return }

        if useTestPattern {
            startTestPatternCapture()
            return
        }

        // Query available displays and windows
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)

        guard let mainDisplay = content.displays.first else {
            throw NSError(
                domain: "ScreenCapturer",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "No active display found for capture."]
            )
        }

        #if canImport(AppKit)
        let detectedScale = NSScreen.main?.backingScaleFactor ?? 2.0
        #else
        let detectedScale = 2.0
        #endif

        let effectiveScale = self.scaleFactor > 0 ? self.scaleFactor : detectedScale

        var targetWidth: Int
        var targetHeight: Int

        if let cw = customWidth, let ch = customHeight {
            targetWidth = cw
            targetHeight = ch
        } else {
            targetWidth = Int(Double(mainDisplay.width) * effectiveScale)
            targetHeight = Int(Double(mainDisplay.height) * effectiveScale)
        }

        // Hardware video encoders (H.264 / VP8) require even pixel dimensions
        if targetWidth % 2 != 0 { targetWidth += 1 }
        if targetHeight % 2 != 0 { targetHeight += 1 }

        self.displayWidth = targetWidth
        self.displayHeight = targetHeight

        print("[Capture] Selected main display ID \(mainDisplay.displayID) (Points: \(mainDisplay.width)x\(mainDisplay.height), Native Scale: \(detectedScale)x)")
        print("[Capture] \u{001B}[1;32mStream Resolution: \(targetWidth)x\(targetHeight)\u{001B}[0m (Scale: \(effectiveScale)x) @ \(targetFPS)fps")

        let filter = SCContentFilter(display: mainDisplay, excludingWindows: [])

        let config = SCStreamConfiguration()
        config.width = targetWidth
        config.height = targetHeight
        config.scalesToFit = true
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(targetFPS))
        config.queueDepth = 5
        config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        config.showsCursor = true

        let newStream = SCStream(filter: filter, configuration: config, delegate: self)
        try newStream.addStreamOutput(self, type: .screen, sampleHandlerQueue: videoOutputQueue)
        try await newStream.startCapture()

        self.stream = newStream
        self.isCapturing = true
        print("[Capture] ScreenCaptureKit stream started successfully with high-DPI backing store")
    }

    /// Stops screen capture stream.
    public func stopCapture() async {
        if let timer = mockTimer {
            timer.cancel()
            mockTimer = nil
        }
        guard isCapturing, let stream = self.stream else { return }
        do {
            try await stream.stopCapture()
            print("[Capture] Screen capture stream stopped cleanly")
        } catch {
            print("[Capture] Error stopping capture stream: \(error.localizedDescription)")
        }
        self.stream = nil
        self.isCapturing = false
    }

    /// Dynamically updates capture target frame rate (e.g. 15fps for low-bandwidth mode, 30-60fps for standard).
    public func updateFPS(_ fps: Int) async {
        self.targetFPS = fps
        if mockTimer != nil {
            startTestPatternCapture()
            return
        }

        guard let stream = self.stream else { return }
        let config = SCStreamConfiguration()
        config.width = self.displayWidth
        config.height = self.displayHeight
        config.scalesToFit = true
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        config.queueDepth = 5
        config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        config.showsCursor = true

        do {
            try await stream.updateConfiguration(config)
            print("[Capture] Updated ScreenCaptureKit stream frame rate target to \(fps) fps")
        } catch {
            print("[Capture] Warning: Failed to update stream configuration frame rate: \(error.localizedDescription)")
        }
    }

    // MARK: - Test Pattern Generator (for testing without TCC permission)

    private func startTestPatternCapture() {
        print("[Capture] Starting synthetic test pattern stream @ \(targetFPS)fps (\(displayWidth)x\(displayHeight))...")
        self.isCapturing = true

        let interval = 1.0 / Double(targetFPS)
        let timer = DispatchSource.makeTimerSource(queue: videoOutputQueue)
        timer.schedule(deadline: .now(), repeating: interval)

        timer.setEventHandler { [weak self] in
            guard let self = self, self.isCapturing else { return }
            self.mockFrameCount += 1
            if let pb = self.generateTestPatternPixelBuffer(width: self.displayWidth, height: self.displayHeight, frame: self.mockFrameCount) {
                let timestamp = CMTime(value: Int64(self.mockFrameCount), timescale: CMTimeScale(self.targetFPS))
                self.delegate?.screenCapturer(self, didCapture: pb, timestamp: timestamp)
            }
        }

        self.mockTimer = timer
        timer.resume()
    }

    private func generateTestPatternPixelBuffer(width: Int, height: Int, frame: Int) -> CVPixelBuffer? {
        var pixelBuffer: CVPixelBuffer?
        let attrs: [CFString: Any] = [
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true
        ]
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            attrs as CFDictionary,
            &pixelBuffer
        )
        guard status == kCVReturnSuccess, let pb = pixelBuffer else { return nil }

        CVPixelBufferLockBaseAddress(pb, [])
        defer { CVPixelBufferUnlockBaseAddress(pb, []) }

        guard let baseAddress = CVPixelBufferGetBaseAddress(pb) else { return nil }
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pb)

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: baseAddress,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return nil }

        // Background color cycling
        #if canImport(AppKit)
        let hue = CGFloat((frame * 2) % 360) / 360.0
        let bg = NSColor(hue: hue, saturation: 0.5, brightness: 0.25, alpha: 1.0)
        context.setFillColor(bg.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))

        // Center card
        let cardRect = CGRect(x: 100, y: 100, width: width - 200, height: height - 200)
        context.setFillColor(NSColor(white: 0.1, alpha: 0.85).cgColor)
        let path = CGPath(roundedRect: cardRect, cornerWidth: 24, cornerHeight: 24, transform: nil)
        context.addPath(path)
        context.fillPath()

        // Text display
        let title = "🖥️ Macky Screen Stream Test Pattern"
        let fontTitle = NSFont.systemFont(ofSize: 52, weight: .bold)
        let titleAttr = NSAttributedString(string: title, attributes: [.font: fontTitle, .foregroundColor: NSColor.white])
        let lineTitle = CTLineCreateWithAttributedString(titleAttr)
        context.textPosition = CGPoint(x: 140, y: CGFloat(height) - 220)
        CTLineDraw(lineTitle, context)

        let subtitle = "Frame: \(frame)  |  Resolution: \(width)x\(height)  |  Target: \(targetFPS) FPS  |  Time: \(Date())"
        let fontSub = NSFont.monospacedSystemFont(ofSize: 32, weight: .regular)
        let subAttr = NSAttributedString(string: subtitle, attributes: [.font: fontSub, .foregroundColor: NSColor.cyan])
        let lineSub = CTLineCreateWithAttributedString(subAttr)
        context.textPosition = CGPoint(x: 140, y: CGFloat(height) - 300)
        CTLineDraw(lineSub, context)
        #endif

        return pb
    }

    // MARK: - SCStreamOutput

    public func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen else { return }

        guard let attachmentsArray = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let attachments = attachmentsArray.first,
              let statusRawValue = attachments[.status] as? Int,
              let status = SCFrameStatus(rawValue: statusRawValue),
              status == .complete else {
            return
        }

        guard let imageBuffer = sampleBuffer.imageBuffer else { return }
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)

        delegate?.screenCapturer(self, didCapture: imageBuffer, timestamp: timestamp)
    }

    // MARK: - SCStreamDelegate

    public func stream(_ stream: SCStream, didStopWithError error: Error) {
        print("[Capture] SCStream stopped with error: \(error.localizedDescription)")
        delegate?.screenCapturer(self, didFailWithError: error)
    }
}
