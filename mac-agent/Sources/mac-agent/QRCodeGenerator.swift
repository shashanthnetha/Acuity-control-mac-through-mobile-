import Foundation
import CoreImage
#if canImport(AppKit)
import AppKit
#endif

/// Generates QR codes for WebRTC room connection pairing.
public enum QRCodeGenerator {

    /// Generates a PNG QR code image file and saves it to the given file path.
    /// Uses macOS CoreImage built-in CIQRCodeGenerator.
    ///
    /// - Parameters:
    ///   - string: The room code or join URL to encode.
    ///   - outputPath: File path where the PNG should be saved.
    /// - Returns: True if successfully generated and saved, false otherwise.
    @discardableResult
    public static func generateQRCode(from string: String, outputPath: String = "room_qr.png") -> Bool {
        guard let data = string.data(using: .utf8) else {
            print("[QR] Error: Failed to convert string to UTF-8 data")
            return false
        }

        guard let filter = CIFilter(name: "CIQRCodeGenerator") else {
            print("[QR] Error: CIQRCodeGenerator filter unavailable")
            return false
        }

        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel") // Medium error correction (15%)

        guard let outputImage = filter.outputImage else {
            print("[QR] Error: Failed to generate QR CIImage")
            return false
        }

        // Scale up the CIImage so it is sharp and high-res (default is 1 point per module)
        let scale = CGAffineTransform(scaleX: 12.0, y: 12.0)
        let scaledImage = outputImage.transformed(by: scale)

        #if canImport(AppKit)
        let rep = NSCIImageRep(ciImage: scaledImage)
        let nsImage = NSImage(size: rep.size)
        nsImage.addRepresentation(rep)

        guard let tiffData = nsImage.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiffData),
              let pngData = bitmap.representation(using: .png, properties: [:]) else {
            print("[QR] Error: Failed to serialize QR bitmap to PNG")
            return false
        }

        let fileURL = URL(fileURLWithPath: outputPath)
        do {
            try pngData.write(to: fileURL, options: .atomic)
            return true
        } catch {
            print("[QR] Error: Failed to write PNG to \(outputPath): \(error.localizedDescription)")
            return false
        }
        #else
        return false
        #endif
    }

    #if canImport(AppKit)
    /// Generates an in-memory NSImage of the QR code for UI and popovers.
    public static func createQRImage(from string: String, scaleFactor: CGFloat = 12.0) -> NSImage? {
        guard let data = string.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }

        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")

        guard let outputImage = filter.outputImage else { return nil }

        let scale = CGAffineTransform(scaleX: scaleFactor, y: scaleFactor)
        let scaledImage = outputImage.transformed(by: scale)

        let rep = NSCIImageRep(ciImage: scaledImage)
        let nsImage = NSImage(size: rep.size)
        nsImage.addRepresentation(rep)
        return nsImage
    }
    #endif

    /// Discovers the primary local IPv4 address (e.g. 192.168.x.x on en0 / Wi-Fi).
    public static func getPrimaryIPAddress() -> String {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr {
            defer { freeifaddrs(ifaddr) }
            for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
                let interface = ptr.pointee
                let addrFamily = interface.ifa_addr.pointee.sa_family
                if addrFamily == UInt8(AF_INET) {
                    let name = String(cString: interface.ifa_name)
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                                   &hostname, socklen_t(hostname.count),
                                   nil, 0, NI_NUMERICHOST) == 0 {
                        let ip = String(cString: hostname)
                        if !ip.starts(with: "127.") {
                            if name == "en0" { return ip }
                            if address == nil { address = ip }
                        }
                    }
                }
            }
        }
        return address ?? "localhost"
    }

    /// Constructs a reachable join URL with the local network IP and signaling port.
    public static func makeJoinURL(roomCode: String, serverURL: String = "http://localhost:8000") -> String {
        let ip = getPrimaryIPAddress()
        var port = 8000
        if let url = URL(string: serverURL), let p = url.port {
            port = p
        }
        return "http://\(ip):\(port)/?room=\(roomCode)"
    }

    /// Renders a simple terminal-friendly display banner for the room code.
    public static func printRoomBanner(roomCode: String, serverURL: String, qrPath: String) {
        let ip = getPrimaryIPAddress()
        var port = 8000
        if let url = URL(string: serverURL), let p = url.port {
            port = p
        }
        let joinURL = "http://\(ip):\(port)/?room=\(roomCode)"
        let border = String(repeating: "═", count: 54)
        print("\n" + border)
        print("  📱 MAC-TO-ANDROID SCREEN BROADCASTER")
        print(border)
        print("  🔑 ROOM CODE:  \u{001B}[1;32m\(roomCode)\u{001B}[0m")
        print("  🌐 SIGNALING:  \(serverURL)")
        print("  📡 LOCAL IP:   \(ip):\(port)")
        print("  🔗 JOIN URL:   \(joinURL)")
        print("  🖼️  QR CODE:    Saved to \(qrPath)")
        print("  👉 Scan with Acuity on your phone to connect instantly")
        print(border + "\n")
    }
}
