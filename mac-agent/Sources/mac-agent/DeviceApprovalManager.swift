import Foundation
import CryptoKit

/// Data structure representing an authorized Android device.
public struct TrustedDevice: Codable {
    public let fingerprint: String
    public var publicKey: String? // Base64 X.509 DER representation
    public var name: String
    public let approvedAt: String
    public var lastSeenAt: String
}

public struct TrustedDevicesFile: Codable {
    public var devices: [TrustedDevice]
}

/// Manages host-side security authorization for client connections.
/// Implements persistent device trust in ~/.macky/trusted_devices.json,
/// cryptographic challenge-response signature verification, and native macOS dialogs.
public final class DeviceApprovalManager {

    public static let shared = DeviceApprovalManager()

    private let fileManager = FileManager.default
    private let storageURL: URL
    private let lock = NSLock()

    private init() {
        let homeDir = fileManager.homeDirectoryForCurrentUser
        let acuityDir = homeDir.appendingPathComponent(".acuity", isDirectory: true)

        if !fileManager.fileExists(atPath: acuityDir.path) {
            try? fileManager.createDirectory(at: acuityDir, withIntermediateDirectories: true)
        }

        self.storageURL = acuityDir.appendingPathComponent("trusted_devices.json")
    }

    // MARK: - Trust Verification & Storage

    /// Retrieves a trusted device by fingerprint.
    /// Handles legacy migration: if the device exists but lacks a stored public key,
    /// logs a clear one-time warning and returns nil so it undergoes cryptographic re-approval.
    public func getTrustedDevice(fingerprint: String?) -> TrustedDevice? {
        guard let fp = fingerprint?.trimmingCharacters(in: .whitespacesAndNewlines), !fp.isEmpty else {
            return nil
        }

        lock.lock()
        defer { lock.unlock() }

        var list = loadDevices()
        if let idx = list.firstIndex(where: { $0.fingerprint.caseInsensitiveCompare(fp) == .orderedSame }) {
            let dev = list[idx]
            guard let pubKey = dev.publicKey, !pubKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                print("⚠️ [Security Gate] Legacy trusted device '\(dev.name)' lacks a stored public key; requiring cryptographic re-approval.")
                return nil
            }

            let formatter = ISO8601DateFormatter()
            list[idx].lastSeenAt = formatter.string(from: Date())
            saveDevices(list)
            return dev
        }

        return nil
    }

    /// Backwards compatibility helper returning whether a device is trusted and has a public key.
    public func isTrusted(fingerprint: String?) -> Bool {
        return getTrustedDevice(fingerprint: fingerprint) != nil
    }

    /// Returns a snapshot of all trusted devices currently in storage.
    public func getTrustedDevices() -> [TrustedDevice] {
        lock.lock()
        defer { lock.unlock() }
        return loadDevices()
    }

    /// Adds or updates an authorized device with its verified public key.
    public func addTrusted(fingerprint: String, name: String, publicKey: String) {
        let fp = fingerprint.trimmingCharacters(in: .whitespacesAndNewlines)
        let pk = publicKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !fp.isEmpty, !pk.isEmpty else { return }

        lock.lock()
        defer { lock.unlock() }

        var list = loadDevices()
        let formatter = ISO8601DateFormatter()
        let nowStr = formatter.string(from: Date())

        if let idx = list.firstIndex(where: { $0.fingerprint.caseInsensitiveCompare(fp) == .orderedSame }) {
            list[idx].name = name
            list[idx].publicKey = pk
            list[idx].lastSeenAt = nowStr
        } else {
            list.append(TrustedDevice(
                fingerprint: fp,
                publicKey: pk,
                name: name,
                approvedAt: nowStr,
                lastSeenAt: nowStr
            ))
        }

        saveDevices(list)
        print("🔒 [Security] Device '\(name)' (\(fp.prefix(12))...) stored in trusted devices with cryptographic public key.")
    }

    // MARK: - Cryptographic Verification (NIST P-256 / ECDSA)

    /// Verifies that the claimed fingerprint matches SHA-256(publicKeyData).
    public func verifyFingerprint(publicKeyBase64: String, claimedFingerprint: String) -> Bool {
        guard let pubData = Data(base64Encoded: publicKeyBase64) else { return false }
        let hash = SHA256.hash(data: pubData)
        let computedFp = hash.map { String(format: "%02x", $0) }.joined()
        return computedFp.caseInsensitiveCompare(claimedFingerprint.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
    }

    /// Verifies an ECDSA signature over the given nonce using the provided P-256 public key.
    public func verifySignature(publicKeyBase64: String, nonce: Data, signatureBase64: String) -> Bool {
        guard let pubData = Data(base64Encoded: publicKeyBase64),
              let sigData = Data(base64Encoded: signatureBase64) else {
            print("⚠️ [Crypto Error] Failed to decode Base64 public key or signature")
            return false
        }

        do {
            let pubKey = try P256.Signing.PublicKey(derRepresentation: pubData)
            let signature = try P256.Signing.ECDSASignature(derRepresentation: sigData)
            return pubKey.isValidSignature(signature, for: nonce)
        } catch {
            print("⚠️ [Crypto Error] Cryptographic validation error: \(error.localizedDescription)")
            return false
        }
    }

    // MARK: - Native macOS Approval Prompt

    /// The host user's decision from the native macOS approval dialog.
    public enum ApprovalChoice {
        case allowOnce
        case alwaysAllow
        case deny
    }

    /// Presents a native macOS modal alert prompting the Mac user to Deny, Allow Once, or Always Allow the connection.
    /// Times out after 30 seconds (defaulting to Deny).
    /// Does NOT save to trusted_devices.json — saving only occurs after signature verification if Always Allow was selected.
    public func promptUserForApproval(deviceName: String, fingerprint: String) -> ApprovalChoice {
        let cleanName = deviceName.isEmpty ? "Unknown Android Device" : deviceName
        let shortFp = fingerprint.count > 12 ? "\(fingerprint.prefix(12))..." : fingerprint

        // Escape double quotes and backslashes for AppleScript
        let escapedName = cleanName.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        let escapedFp = shortFp.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")

        let promptText = "\(escapedName) (\(escapedFp)) wants to connect to your Mac.\\n\\nAllow this device to view your screen and inject input?"

        let script = """
        display dialog "\(promptText)" buttons {"Deny", "Allow Once", "Always Allow"} default button "Always Allow" with title "Acuity Connection Request" with icon caution giving up after 30
        """

        print("🔔 [Approval Gate] Prompting user: '\(cleanName)' is requesting connection (30s timeout)...")

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", script]

        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = Pipe() // Suppress stderr

        do {
            try process.run()
            process.waitUntilExit()

            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let output = String(data: data, encoding: .utf8) ?? ""

            // osascript output format: "button returned:Always Allow, gave up:false"
            if output.contains("button returned:Always Allow") && output.contains("gave up:false") {
                print("✅ [Approval Gate] Host user clicked ALWAYS ALLOW for device '\(cleanName)'")
                return .alwaysAllow
            } else if output.contains("button returned:Allow Once") && output.contains("gave up:false") {
                print("✅ [Approval Gate] Host user clicked ALLOW ONCE for device '\(cleanName)'")
                return .allowOnce
            } else if output.contains("gave up:true") {
                print("⏱️ [Approval Gate] Connection request TIMED OUT (30s elapsed) for device '\(cleanName)'")
                return .deny
            } else {
                print("🚫 [Approval Gate] Connection DENIED by host for device '\(cleanName)'")
                return .deny
            }
        } catch {
            print("❌ [Approval Gate] Failed to display approval dialog: \(error.localizedDescription)")
            return .deny
        }
    }

    /// Shows a brief non-blocking macOS notification when a trusted device connects.
    public func showConnectedNotification(deviceName: String) {
        let cleanName = deviceName.isEmpty ? "Android Device" : deviceName
        let escapedName = cleanName.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        let script = "display notification \"\(escapedName) connected\" with title \"Acuity Screen Mirroring\""

        DispatchQueue.global(qos: .userInitiated).async {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            process.arguments = ["-e", script]
            try? process.run()
        }
    }

    // MARK: - CLI Management Commands

    public func listTrustedDevices() {
        lock.lock()
        defer { lock.unlock() }

        let devices = loadDevices()
        if devices.isEmpty {
            print("ℹ️ No trusted devices found in ~/.acuity/trusted_devices.json")
            return
        }

        print("\n=======================================================")
        print("          ACUITY TRUSTED CLIENT DEVICES")
        print("=======================================================")
        for (i, dev) in devices.enumerated() {
            let keyStatus = (dev.publicKey != nil && !dev.publicKey!.isEmpty) ? "Verified (P-256)" : "Legacy (Requires Re-approval)"
            print("[\(i + 1)] Device:      \(dev.name)")
            print("    Fingerprint: \(dev.fingerprint)")
            print("    Public Key:  \(keyStatus)")
            print("    Approved:    \(dev.approvedAt)")
            print("    Last Seen:   \(dev.lastSeenAt)")
            print("-------------------------------------------------------")
        }
        print("Total: \(devices.count) device(s)\n")
    }

    public func revokeDevice(target: String) {
        lock.lock()
        defer { lock.unlock() }

        var list = loadDevices()
        if target.lowercased() == "all" {
            let count = list.count
            saveDevices([])
            print("🗑️ Revoked all (\(count)) trusted devices.")
            return
        }

        let initialCount = list.count
        list.removeAll { dev in
            dev.fingerprint.caseInsensitiveCompare(target) == .orderedSame ||
            dev.fingerprint.lowercased().hasPrefix(target.lowercased())
        }

        if list.count < initialCount {
            saveDevices(list)
            print("🗑️ Successfully revoked device with fingerprint matching '\(target)'.")
        } else {
            print("⚠️ No trusted device matched fingerprint '\(target)'.")
        }
    }

    // MARK: - Internal File Helpers

    private func loadDevices() -> [TrustedDevice] {
        guard fileManager.fileExists(atPath: storageURL.path),
              let data = try? Data(contentsOf: storageURL) else {
            return []
        }

        if let fileObj = try? JSONDecoder().decode(TrustedDevicesFile.self, from: data) {
            return fileObj.devices
        } else if let directList = try? JSONDecoder().decode([TrustedDevice].self, from: data) {
            return directList
        }

        return []
    }

    private func saveDevices(_ list: [TrustedDevice]) {
        let fileObj = TrustedDevicesFile(devices: list)
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted

        if let data = try? encoder.encode(fileObj) {
            try? data.write(to: storageURL, options: .atomic)
        }
    }
}
