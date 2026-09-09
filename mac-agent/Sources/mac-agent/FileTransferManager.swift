import Foundation
import CryptoKit
import AppKit

public protocol FileTransferManagerDelegate: AnyObject {
    func fileTransferDidUpdateProgress(fileName: String, progress: Double, isSending: Bool)
    func fileTransferDidComplete(fileName: String, localURL: URL, isSending: Bool)
    func fileTransferDidFail(fileName: String, error: String, isSending: Bool)
}

/// Manages chunked, binary WebRTC file transfers (<200MB) with SHA-256 integrity checks.
public final class FileTransferManager {

    public weak var delegate: FileTransferManagerDelegate?

    public static let maxFileSize: Int64 = 200 * 1024 * 1024 // 200MB
    public static let chunkSize: Int = 16 * 1024             // 16KB

    // Outgoing transfer
    private var isSending = false
    private var sendTask: Task<Void, Never>?

    // Incoming transfer
    private struct IncomingFile {
        let name: String
        let size: Int64
        let mimeType: String
        let totalChunks: Int
        var receivedChunks: Int
        let tempURL: URL
        let fileHandle: FileHandle
        var hasher: SHA256
    }
    private var incoming: IncomingFile?

    public init() {}

    // MARK: - Outgoing (Send File)

    public func startSendingFile(url: URL, sendChunkHandler: @escaping (Int32, Data) -> Bool, sendControlHandler: @escaping (String) -> Void) {
        guard !isSending else {
            delegate?.fileTransferDidFail(fileName: url.lastPathComponent, error: "A transfer is already in progress.", isSending: true)
            return
        }

        let fileName = url.lastPathComponent
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let fileSize = attrs[.size] as? Int64 else {
            delegate?.fileTransferDidFail(fileName: fileName, error: "Cannot access file attributes.", isSending: true)
            return
        }

        if fileSize > Self.maxFileSize {
            delegate?.fileTransferDidFail(fileName: fileName, error: "File exceeds 200MB limit (\(fileSize / (1024 * 1024))MB).", isSending: true)
            return
        }

        let totalChunks = Int(ceil(Double(fileSize) / Double(Self.chunkSize)))

        // Send START message
        let startMsg: [String: Any] = [
            "type": "start",
            "name": fileName,
            "size": fileSize,
            "mimeType": "application/octet-stream",
            "totalChunks": totalChunks
        ]

        guard let startData = try? JSONSerialization.data(withJSONObject: startMsg),
              let startStr = String(data: startData, encoding: .utf8) else {
            delegate?.fileTransferDidFail(fileName: fileName, error: "Failed to create start message.", isSending: true)
            return
        }

        sendControlHandler(startStr)
        self.isSending = true

        sendTask = Task.detached { [weak self] in
            guard let self = self else { return }
            var hasher = SHA256()

            guard let fileHandle = try? FileHandle(forReadingFrom: url) else {
                await MainActor.run {
                    self.delegate?.fileTransferDidFail(fileName: fileName, error: "Failed to open file for reading.", isSending: true)
                    self.isSending = false
                }
                return
            }

            defer { try? fileHandle.close() }

            for chunkIndex in 0..<totalChunks {
                if Task.isCancelled {
                    sendControlHandler("{\"type\":\"cancel\"}")
                    await MainActor.run { self.isSending = false }
                    return
                }

                let chunkData = fileHandle.readData(ofLength: Self.chunkSize)
                if chunkData.isEmpty && chunkIndex < totalChunks - 1 {
                    break
                }

                hasher.update(data: chunkData)

                // Send chunk with 4-byte Int32 big-endian index header
                let success = sendChunkHandler(Int32(chunkIndex), chunkData)
                if !success {
                    try? await Task.sleep(nanoseconds: 10_000_000) // 10ms backpressure pause
                }

                let progress = Double(chunkIndex + 1) / Double(totalChunks)
                await MainActor.run {
                    self.delegate?.fileTransferDidUpdateProgress(fileName: fileName, progress: progress, isSending: true)
                }

                // Tiny yield to prevent event loop starvation
                if chunkIndex % 4 == 0 {
                    try? await Task.sleep(nanoseconds: 1_000_000)
                }
            }

            let digest = hasher.finalize()
            let checksum = digest.map { String(format: "%02x", $0) }.joined()

            let completeMsg: [String: Any] = [
                "type": "complete",
                "checksum": checksum
            ]

            if let completeData = try? JSONSerialization.data(withJSONObject: completeMsg),
               let completeStr = String(data: completeData, encoding: .utf8) {
                sendControlHandler(completeStr)
            }

            await MainActor.run {
                self.isSending = false
                self.delegate?.fileTransferDidComplete(fileName: fileName, localURL: url, isSending: true)
            }
        }
    }

    public func cancelSending() {
        sendTask?.cancel()
        sendTask = nil
        isSending = false
    }

    // MARK: - Incoming (Receive File)

    public func handleControlMessage(_ jsonString: String) {
        guard let data = jsonString.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = dict["type"] as? String else {
            return
        }

        switch type {
        case "start":
            guard let name = dict["name"] as? String,
                  let size = (dict["size"] as? NSNumber)?.int64Value,
                  let totalChunks = dict["totalChunks"] as? Int else {
                return
            }
            startIncomingFile(name: name, size: size, mimeType: (dict["mimeType"] as? String) ?? "", totalChunks: totalChunks)

        case "complete":
            let checksum = (dict["checksum"] as? String) ?? ""
            finishIncomingFile(expectedChecksum: checksum)

        case "cancel":
            cancelIncomingFile()

        default:
            break
        }
    }

    private func startIncomingFile(name: String, size: Int64, mimeType: String, totalChunks: Int) {
        cancelIncomingFile()

        if size > Self.maxFileSize {
            delegate?.fileTransferDidFail(fileName: name, error: "File exceeds 200MB limit.", isSending: false)
            return
        }

        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "_" + name)
        FileManager.default.createFile(atPath: tempURL.path, contents: nil)

        guard let handle = try? FileHandle(forWritingTo: tempURL) else {
            delegate?.fileTransferDidFail(fileName: name, error: "Failed to create temporary file for incoming transfer.", isSending: false)
            return
        }

        self.incoming = IncomingFile(
            name: name,
            size: size,
            mimeType: mimeType,
            totalChunks: totalChunks,
            receivedChunks: 0,
            tempURL: tempURL,
            fileHandle: handle,
            hasher: SHA256()
        )

        DispatchQueue.main.async {
            self.delegate?.fileTransferDidUpdateProgress(fileName: name, progress: 0.0, isSending: false)
        }
    }

    public func handleChunkData(_ rawData: Data) {
        guard var inc = self.incoming, rawData.count >= 4 else { return }

        // First 4 bytes: Int32 chunk index (big-endian)
        let chunkIndex = rawData.subdata(in: 0..<4).withUnsafeBytes { $0.load(as: Int32.self).bigEndian }
        let payload = rawData.subdata(in: 4..<rawData.count)

        inc.fileHandle.write(payload)
        inc.hasher.update(data: payload)
        inc.receivedChunks += 1
        self.incoming = inc

        let progress = Double(inc.receivedChunks) / Double(inc.totalChunks)
        DispatchQueue.main.async {
            self.delegate?.fileTransferDidUpdateProgress(fileName: inc.name, progress: progress, isSending: false)
        }
    }

    private func finishIncomingFile(expectedChecksum: String) {
        guard let inc = self.incoming else { return }
        try? inc.fileHandle.close()
        self.incoming = nil

        let digest = inc.hasher.finalize()
        let computedChecksum = digest.map { String(format: "%02x", $0) }.joined()

        if !expectedChecksum.isEmpty && computedChecksum.lowercased() != expectedChecksum.lowercased() {
            try? FileManager.default.removeItem(at: inc.tempURL)
            DispatchQueue.main.async {
                self.delegate?.fileTransferDidFail(fileName: inc.name, error: "Checksum verification failed (corrupted file).", isSending: false)
            }
            return
        }

        // Destination: ~/Downloads/Acuity/
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let acuityDir = downloads.appendingPathComponent("Acuity", isDirectory: true)
        try? FileManager.default.createDirectory(at: acuityDir, withIntermediateDirectories: true)

        var destURL = acuityDir.appendingPathComponent(inc.name)
        var counter = 1
        let nameWithoutExt = (inc.name as NSString).deletingPathExtension
        let ext = (inc.name as NSString).pathExtension

        while FileManager.default.fileExists(atPath: destURL.path) {
            let uniqueName = ext.isEmpty ? "\(nameWithoutExt) (\(counter))" : "\(nameWithoutExt) (\(counter)).\(ext)"
            destURL = acuityDir.appendingPathComponent(uniqueName)
            counter += 1
        }

        do {
            try FileManager.default.moveItem(at: inc.tempURL, to: destURL)
            print("[FileTransfer] Saved received file to: \(destURL.path)")
            DispatchQueue.main.async {
                self.delegate?.fileTransferDidComplete(fileName: inc.name, localURL: destURL, isSending: false)
            }
        } catch {
            DispatchQueue.main.async {
                self.delegate?.fileTransferDidFail(fileName: inc.name, error: "Failed to save file to Downloads/Acuity: \(error.localizedDescription)", isSending: false)
            }
        }
    }

    public func cancelIncomingFile() {
        if let inc = self.incoming {
            try? inc.fileHandle.close()
            try? FileManager.default.removeItem(at: inc.tempURL)
            self.incoming = nil
        }
    }
}
