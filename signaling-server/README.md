# WebRTC Signaling Server (FastAPI)

> **Step 1 of Mac-to-Android Screen Mirroring App**
> Production-clean, low-latency 1-to-1 WebRTC signaling relay built with Python 3.11+, FastAPI, and native WebSockets.

---

## 🚀 Overview

This server provides the signaling layer to establish a direct WebRTC peer-to-peer connection between a **Host** (Mac screen broadcaster) and a **Viewer** (Android display client).

### Key Architectural Characteristics
- **Signaling Only**: The server strictly brokers JSON metadata (Session Description Protocol offers/answers and ICE candidates). **Zero video, audio, or input data touches this server.**
- **Strict 1-to-1 Topology**: Exactly two roles per room: `host` and `viewer`. Any 3rd peer is immediately rejected with an error message.
- **In-Memory Lifecycle & TTL**: Active rooms are tracked in an in-memory dictionary. Rooms expire after 5 minutes of inactivity via a background periodic worker, or immediately when both peers disconnect.
- **Production-Ready Clean Architecture**: Structured with Pydantic v2 data models, thread-safe asynchronous state management, structured logging, and comprehensive error handling.

---

## 📁 Project Structure

```
signaling-server/
├── .env.example          # Environment variable template
├── main.py               # FastAPI app, REST endpoints, and WebSocket relay loop
├── models.py             # Pydantic models for signaling messages and REST responses
├── README.md             # Architecture, protocol, and usage instructions
├── requirements.txt      # Production & testing dependencies
├── rooms.py              # Room and RoomManager concurrency & state classes
└── test_client.py        # Automated end-to-end integration test suite
```

---

## 🛠️ Quickstart & Run Instructions

### 1. Prerequisites
- Python 3.11 or higher
- Recommended: [`uv`](https://github.com/astral-sh/uv) or standard Python `venv`

### 2. Setup Virtual Environment & Install Dependencies

Using `uv` (ultra fast):
```bash
cd signaling-server
uv venv .venv
source .venv/bin/activate
uv pip install -r requirements.txt
```

Or using standard `pip`:
```bash
cd signaling-server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 3. Configure Environment
Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```

Configurable options:
| Variable | Default | Description |
|---|---|---|
| `HOST` | `0.0.0.0` | Bind IP address |
| `PORT` | `8000` | Bind port number |
| `ROOM_TTL_SECONDS` | `300` | Inactivity expiration timeout (5 minutes) |
| `CLEANUP_INTERVAL_SECONDS` | `30` | Interval for background room eviction task |
| `LOG_LEVEL` | `INFO` | Logging level (`DEBUG`, `INFO`, `WARNING`, `ERROR`) |

### 4. Launch Server
```bash
# Start directly with uvicorn
python main.py

# Or via uvicorn CLI with reload
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Interactive API documentation will be available at:
- **Swagger UI**: [http://localhost:8000/docs](http://localhost:8000/docs)
- **ReDoc**: [http://localhost:8000/redoc](http://localhost:8000/redoc)

---

## 📡 REST Endpoints

### 1. Create a Room
**`POST /rooms`**
Called by the Host (Mac) to generate an active 6-character room code before opening the WebSocket.

**Response (201 Created):**
```json
{
  "room_code": "X7K2QP"
}
```

### 2. Query Room Status
**`GET /rooms/{room_code}/status`**
Returns connection states and diagnostics. Useful for the Android client prior to connecting.

**Response (200 OK):**
```json
{
  "room_code": "X7K2QP",
  "exists": true,
  "host_connected": true,
  "viewer_connected": false,
  "created_at": 1725712000.12,
  "last_activity": 1725712015.45,
  "messages_relayed": 0,
  "ttl_remaining_seconds": 284.6
}
```
*Returns `404 Not Found` if the room has expired or does not exist.*

### 3. Health Check
**`GET /health`**
Returns `{"status": "ok", "service": "webrtc-signaling-server"}`.

---

## ⚡ WebSocket Protocol

### Endpoint URI
```
ws://<server-host>:8000/ws/{room_code}?role=host
ws://<server-host>:8000/ws/{room_code}?role=viewer
```

### Protocol Envelope Structure
All WebSocket communication uses a standardized JSON envelope:
```json
{
  "type": "<message-type>",
  "payload": { ... }
}
```

### Supported Message Types

| Message Type | Direction | Payload Example | Description |
|---|---|---|---|
| `offer` | Host ➔ Viewer | `{"sdp": "...", "type": "offer"}` | WebRTC SDP offer from Mac |
| `answer` | Viewer ➔ Host | `{"sdp": "...", "type": "answer"}` | WebRTC SDP answer from Android |
| `ice-candidate` | Bidirectional | `{"candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0}` | ICE candidate trickle |
| `peer-joined` | Server ➔ Host | `{"role": "viewer"}` | Sent to Host when Android joins |
| `peer-left` | Server ➔ Peer | `{"role": "viewer"}` / `{"role": "host"}` | Sent to remaining peer on disconnect |
| `error` | Server ➔ Client | `{"message": "Room is full"}` | Rejection or protocol violation notice |

### Connection Rules
1. **Host connects first**: If Viewer attempts to connect to a room without an active Host, the server responds with an `error` and closes the socket.
2. **Strict pair relay**: Messages are forwarded verbatim directly to the opposite peer only.
3. **Capacity rejection**: If a 3rd peer attempts to join an active room, it receives `{"type": "error", "payload": {"message": "Room is full"}}` and is immediately disconnected.
4. **Immediate cleanup**: When both peers disconnect, the room is deleted from memory.

---

## 🧪 Testing

The repository includes an automated integration test script [`test_client.py`](test_client.py) that simulates:
1. REST room creation
2. Host WebSocket connection
3. Viewer WebSocket connection and `peer-joined` verification
4. Bidirectional WebRTC SDP offer & answer exchange
5. Bidirectional ICE candidate exchange
6. Rejection of 3rd peer attempting to join full room
7. Disconnect notification (`peer-left`) and room deletion

Run the test suite:
```bash
# Make sure server is running on localhost:8000 in another terminal, or run:
python test_client.py
```
