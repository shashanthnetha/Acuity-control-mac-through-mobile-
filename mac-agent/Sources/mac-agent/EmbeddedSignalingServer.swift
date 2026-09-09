import Foundation
import Network
import CryptoKit

/// Embedded lightweight WebRTC signaling server running natively inside Acuity.app.
/// Eliminates the need for any external Python or Node.js process, allowing the app
/// to be shared with friends and work with zero terminal commands or dependencies.
public final class EmbeddedSignalingServer {

    public static let shared = EmbeddedSignalingServer()

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "com.sha.acuity.signaling", qos: .userInitiated)
    private var rooms: [String: RoomSession] = [:]
    private var latestRoomCode: String?
    private let lock = NSLock()

    private final class RoomSession {
        let roomCode: String
        var hostConnection: ConnectionState?
        var viewerConnection: ConnectionState?
        var lastActivity = Date()

        init(roomCode: String) {
            self.roomCode = roomCode
        }
    }

    private final class ConnectionState {
        let connection: NWConnection
        let role: String
        let roomCode: String
        var isWebSocket = false
        var incomingBuffer = Data()

        init(connection: NWConnection, role: String, roomCode: String) {
            self.connection = connection
            self.role = role
            self.roomCode = roomCode
        }
    }

    private init() {}

    /// Starts the embedded signaling server on the given port.
    /// If the port is already in use by an external server, it gracefully yields.
    public func start(port: UInt16 = 8000) {
        lock.lock()
        defer { lock.unlock() }

        if listener != nil { return }

        do {
            let nwPort = NWEndpoint.Port(rawValue: port)!
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true

            let nwListener = try NWListener(using: parameters, on: nwPort)
            self.listener = nwListener

            nwListener.newConnectionHandler = { [weak self] newConnection in
                self?.handleNewConnection(newConnection)
            }

            nwListener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    print("✅ [EmbeddedSignaling] Native signaling server running on port \(port)")
                case .failed(let error):
                    print("ℹ️ [EmbeddedSignaling] Port \(port) unavailable (\(error.localizedDescription)). Using external server.")
                default:
                    break
                }
            }

            nwListener.start(queue: queue)
        } catch {
            print("ℹ️ [EmbeddedSignaling] Failed to bind port \(port): \(error.localizedDescription)")
        }
    }

    public func stop() {
        lock.lock()
        defer { lock.unlock() }

        listener?.cancel()
        listener = nil
        rooms.removeAll()
    }

    // MARK: - Connection Handling

    private func handleNewConnection(_ connection: NWConnection) {
        connection.start(queue: queue)
        readIncomingData(connection: connection, state: nil)
    }

    private func readIncomingData(connection: NWConnection, state: ConnectionState?) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] content, _, isComplete, error in
            guard let self = self else { return }

            if let data = content, !data.isEmpty {
                if let existingState = state, existingState.isWebSocket {
                    existingState.incomingBuffer.append(data)
                    self.processWebSocketFrames(for: existingState)
                    self.readIncomingData(connection: connection, state: existingState)
                } else {
                    // Initial HTTP or WebSocket upgrade request
                    self.handleInitialHTTP(connection: connection, data: data)
                }
            } else if isComplete || error != nil {
                if let existingState = state {
                    self.handleDisconnect(existingState)
                }
            } else {
                self.readIncomingData(connection: connection, state: state)
            }
        }
    }

    // MARK: - HTTP & WebSocket Upgrade

    private func handleInitialHTTP(connection: NWConnection, data: Data) {
        guard let requestText = String(data: data, encoding: .utf8) else {
            connection.cancel()
            return
        }

        let lines = requestText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            connection.cancel()
            return
        }

        let parts = requestLine.components(separatedBy: " ")
        guard parts.count >= 2 else {
            connection.cancel()
            return
        }

        let method = parts[0].uppercased()
        let pathWithQuery = parts[1]

        // CORS Preflight
        if method == "OPTIONS" {
            let response = "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\nContent-Length: 0\r\n\r\n"
            sendRaw(connection: connection, text: response)
            return
        }

        // Check for WebSocket upgrade
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if line.isEmpty { break }
            let kv = line.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if kv.count == 2 {
                headers[kv[0].lowercased()] = kv[1]
            }
        }

        let isUpgrade = (headers["upgrade"]?.lowercased() == "websocket")

        if isUpgrade, let secKey = headers["sec-websocket-key"] {
            handleWebSocketUpgrade(connection: connection, pathWithQuery: pathWithQuery, secKey: secKey)
            return
        }

        // REST Endpoints
        let urlComponents = URLComponents(string: "http://localhost" + pathWithQuery)
        let path = urlComponents?.path ?? pathWithQuery

        if method == "POST" && path == "/rooms" {
            let roomCode = generateRoomCode()
            lock.lock()
            rooms[roomCode] = RoomSession(roomCode: roomCode)
            latestRoomCode = roomCode
            lock.unlock()

            let body = "{\"room_code\":\"\(roomCode)\"}"
            let response = "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: \(body.utf8.count)\r\n\r\n\(body)"
            sendRaw(connection: connection, text: response)
        } else if method == "GET" && path == "/current-room" {
            lock.lock()
            let code = latestRoomCode ?? (rooms.keys.first ?? "")
            lock.unlock()

            if !code.isEmpty {
                let body = "{\"room_code\":\"\(code)\",\"host_connected\":true,\"version\":\"1.0\"}"
                let response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: \(body.utf8.count)\r\n\r\n\(body)"
                sendRaw(connection: connection, text: response)
            } else {
                let response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                sendRaw(connection: connection, text: response)
            }
        } else if method == "GET" && (path == "/" || path == "/health") {
            let body = "{\"status\":\"ok\",\"service\":\"acuity-embedded-signaling\",\"active_rooms\":\(rooms.count)}"
            let response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: \(body.utf8.count)\r\n\r\n\(body)"
            sendRaw(connection: connection, text: response)
        } else {
            let response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            sendRaw(connection: connection, text: response)
        }
    }

    private func handleWebSocketUpgrade(connection: NWConnection, pathWithQuery: String, secKey: String) {
        let acceptKey = makeSecWebSocketAccept(key: secKey)
        let response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: \(acceptKey)\r\n\r\n"
        sendRaw(connection: connection, text: response)

        // Parse path: /ws/{roomCode}?role={host|viewer}&deviceId=...&deviceName=...
        let urlComponents = URLComponents(string: "http://localhost" + pathWithQuery)
        let pathParts = (urlComponents?.path ?? "").components(separatedBy: "/").filter { !$0.isEmpty }
        let roomCode = pathParts.count >= 2 ? pathParts[1].uppercased() : (latestRoomCode ?? "WRHS9N")

        let queryItems = urlComponents?.queryItems ?? []
        let role = queryItems.first(where: { $0.name == "role" })?.value?.lowercased() ?? "viewer"
        let deviceId = queryItems.first(where: { $0.name == "deviceId" })?.value
        let deviceName = queryItems.first(where: { $0.name == "deviceName" })?.value

        let connState = ConnectionState(connection: connection, role: role, roomCode: roomCode)
        connState.isWebSocket = true

        lock.lock()
        let room = rooms[roomCode] ?? RoomSession(roomCode: roomCode)
        rooms[roomCode] = room
        latestRoomCode = roomCode

        if role == "host" {
            room.hostConnection = connState
            print("[EmbeddedSignaling] Host attached to room \(roomCode)")
        } else {
            room.viewerConnection = connState
            print("[EmbeddedSignaling] Viewer attached to room \(roomCode) (device: \(deviceName ?? "unknown"))")

            // Notify Host that Viewer joined
            if let host = room.hostConnection {
                var joinedMsg = "{\"type\":\"peer-joined\",\"role\":\"viewer\""
                if let devId = deviceId { joinedMsg += ",\"deviceId\":\"\(devId)\"" }
                if let devName = deviceName { joinedMsg += ",\"deviceName\":\"\(devName)\"" }
                joinedMsg += "}"
                sendWebSocketText(connection: host.connection, text: joinedMsg)
            }

            // Acknowledge Viewer
            sendWebSocketText(connection: connection, text: "{\"type\":\"peer-joined\",\"role\":\"host\"}")
        }
        lock.unlock()

        // Continue reading WebSocket frames
        readIncomingData(connection: connection, state: connState)
    }

    // MARK: - Frame Parsing & Relaying

    private func processWebSocketFrames(for state: ConnectionState) {
        while state.incomingBuffer.count >= 2 {
            let b0 = state.incomingBuffer[0]
            let opcode = b0 & 0x0F
            let b1 = state.incomingBuffer[1]
            let isMasked = (b1 & 0x80) != 0
            var payloadLen = UInt64(b1 & 0x7F)
            var offset = 2

            if payloadLen == 126 {
                guard state.incomingBuffer.count >= 4 else { break }
                payloadLen = UInt64(state.incomingBuffer[2]) << 8 | UInt64(state.incomingBuffer[3])
                offset = 4
            } else if payloadLen == 127 {
                guard state.incomingBuffer.count >= 10 else { break }
                payloadLen = 0
                for i in 0..<8 {
                    payloadLen = (payloadLen << 8) | UInt64(state.incomingBuffer[2 + i])
                }
                offset = 10
            }

            var mask: [UInt8] = [0, 0, 0, 0]
            if isMasked {
                guard state.incomingBuffer.count >= offset + 4 else { break }
                mask = [state.incomingBuffer[offset], state.incomingBuffer[offset+1], state.incomingBuffer[offset+2], state.incomingBuffer[offset+3]]
                offset += 4
            }

            guard state.incomingBuffer.count >= offset + Int(payloadLen) else { break }

            let payloadData = state.incomingBuffer.subdata(in: offset..<(offset + Int(payloadLen)))
            var unmasked = [UInt8](repeating: 0, count: payloadData.count)
            payloadData.withUnsafeBytes { (rawBytes: UnsafeRawBufferPointer) in
                for i in 0..<payloadData.count {
                    unmasked[i] = rawBytes[i] ^ mask[i % 4]
                }
            }
            state.incomingBuffer.removeSubrange(0..<(offset + Int(payloadLen)))

            if opcode == 0x01 { // Text
                if let message = String(bytes: unmasked, encoding: .utf8) {
                    relayMessage(from: state, text: message)
                }
            } else if opcode == 0x08 { // Close
                handleDisconnect(state)
                return
            } else if opcode == 0x09 { // Ping
                sendWebSocketFrame(connection: state.connection, opcode: 0x0A, payload: Data(unmasked))
            }
        }
    }

    private func relayMessage(from sender: ConnectionState, text: String) {
        lock.lock()
        defer { lock.unlock() }

        guard let room = rooms[sender.roomCode] else { return }
        room.lastActivity = Date()

        let target = (sender.role == "host") ? room.viewerConnection : room.hostConnection
        if let targetConn = target?.connection {
            sendWebSocketText(connection: targetConn, text: text)
        }
    }

    private func handleDisconnect(_ state: ConnectionState) {
        lock.lock()
        defer { lock.unlock() }

        guard let room = rooms[state.roomCode] else { return }

        if state.role == "host" {
            room.hostConnection = nil
            if let viewer = room.viewerConnection {
                sendWebSocketText(connection: viewer.connection, text: "{\"type\":\"peer-left\",\"role\":\"host\"}")
            }
        } else {
            room.viewerConnection = nil
            if let host = room.hostConnection {
                sendWebSocketText(connection: host.connection, text: "{\"type\":\"peer-left\",\"role\":\"viewer\"}")
            }
        }
    }

    // MARK: - Framing Utilities

    private func sendRaw(connection: NWConnection, text: String) {
        let data = Data(text.utf8)
        connection.send(content: data, completion: .contentProcessed { _ in })
    }

    private func sendWebSocketText(connection: NWConnection, text: String) {
        sendWebSocketFrame(connection: connection, opcode: 0x01, payload: Data(text.utf8))
    }

    private func sendWebSocketFrame(connection: NWConnection, opcode: UInt8, payload: Data) {
        var frame = Data()
        frame.append(0x80 | (opcode & 0x0F))
        let len = payload.count
        if len <= 125 {
            frame.append(UInt8(len))
        } else if len <= 65535 {
            frame.append(126)
            frame.append(UInt8((len >> 8) & 0xFF))
            frame.append(UInt8(len & 0xFF))
        } else {
            frame.append(127)
            for i in (0..<8).reversed() {
                frame.append(UInt8((UInt64(len) >> (i * 8)) & 0xFF))
            }
        }
        frame.append(payload)
        connection.send(content: frame, completion: .contentProcessed { _ in })
    }

    private func makeSecWebSocketAccept(key: String) -> String {
        let magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        let digest = Insecure.SHA1.hash(data: Data((key + magic).utf8))
        return Data(digest).base64EncodedString()
    }

    private func generateRoomCode() -> String {
        let chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return String((0..<6).map { _ in chars.randomElement()! })
    }
}
