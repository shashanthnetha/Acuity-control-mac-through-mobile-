import Foundation

/// Advertises mac-agent via mDNS/Bonjour using NetService.
/// Allows Android clients on the same Wi-Fi network to discover the Mac automatically
/// and connect with zero IP/port/room-code typing.
public final class BonjourAdvertiser: NSObject, NetServiceDelegate {

    private var netService: NetService?
    private var currentRoomCode: String
    private let signalingPort: Int
    private let hostDisplayName: String

    public init(roomCode: String, signalingPort: Int = 8000) {
        self.currentRoomCode = roomCode
        self.signalingPort = signalingPort
        self.hostDisplayName = Host.current().localizedName ?? ProcessInfo.processInfo.hostName
        super.init()
    }

    /// Starts advertising the service on the local network.
    public func start() {
        stop()

        // Service type: _acuity._tcp.
        // Domain: local. (or empty string for default local domain)
        let service = NetService(
            domain: "local.",
            type: "_acuity._tcp.",
            name: hostDisplayName,
            port: Int32(signalingPort)
        )
        service.delegate = self
        service.includesPeerToPeer = true

        let txtData = buildTXTRecordData(roomCode: currentRoomCode)
        service.setTXTRecord(txtData)

        service.publish(options: [])
        self.netService = service

        print("📡 [mDNS/Bonjour] Advertising '\(hostDisplayName)' (_acuity._tcp) on port \(signalingPort) [Room: \(currentRoomCode)]")
    }

    /// Updates the published room code in the TXT record.
    public func updateRoom(code: String) {
        self.currentRoomCode = code
        guard let service = netService else { return }
        let txtData = buildTXTRecordData(roomCode: code)
        service.setTXTRecord(txtData)
        print("📡 [mDNS/Bonjour] Updated advertised room code to \(code)")
    }

    /// Stops advertising cleanly.
    public func stop() {
        guard let service = netService else { return }
        print("📡 [mDNS/Bonjour] Stopping mDNS advertisement...")
        service.stop()
        netService = nil
    }

    private func buildTXTRecordData(roomCode: String) -> Data {
        let dict: [String: Data] = [
            "room": Data(roomCode.utf8),
            "port": Data(String(signalingPort).utf8),
            "version": Data("1.0".utf8),
            "name": Data(hostDisplayName.utf8)
        ]
        return NetService.data(fromTXTRecord: dict)
    }

    // MARK: - NetServiceDelegate

    public func netServiceDidPublish(_ sender: NetService) {
        print("✅ [mDNS/Bonjour] Service published successfully: \(sender.name)._acuity._tcp.local.")
    }

    public func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        print("⚠️ [mDNS/Bonjour] Failed to publish service: \(errorDict)")
    }

    public func netServiceDidStop(_ sender: NetService) {
        print("📡 [mDNS/Bonjour] Service stopped.")
    }
}
