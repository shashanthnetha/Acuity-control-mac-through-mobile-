# Mac Screen Capture Agent (`mac-agent`)

> **Step 2 of Mac-to-Android Screen Mirroring App**
> High-performance macOS screen capture & WebRTC streaming agent built with Swift 5.9+, ScreenCaptureKit, and Google WebRTC.

---

## 🚀 Overview

`mac-agent` captures the Mac's primary display at native resolution and 30fps using Apple's modern **ScreenCaptureKit** framework, feeds raw `CVPixelBuffer` frames into a **WebRTC video pipeline**, connects to the **Signaling Server** (from Step 1), and streams low-latency video to peer viewers (Android client or test browser).

### Key Architectural Highlights
- **ScreenCaptureKit Integration**: Uses `SCStream` and `SCStreamConfiguration` for zero-copy, GPU-backed 30fps display capture.
- **Hardware-Accelerated Encoding**: Frames flow through `RTCCVPixelBuffer` into Google's `RTCDefaultVideoEncoderFactory` (H.264 VideoToolbox hardware encoder).
- **Graceful Permission Handling**: Checks `CGPreflightScreenCaptureAccess()` and `CGRequestScreenCaptureAccess()`; exits cleanly with actionable instructions if permission is not granted.
- **Automated Pairing & QR Code**: Fetches a 6-character room code via `POST /rooms`, prints it to the console, and generates `room_qr.png` via CoreImage `CIQRCodeGenerator`.
- **STUN & Tailscale Direct P2P**: Configured with `stun:stun.l.google.com:19302` as fallback, while supporting direct host-to-host P2P when both devices share a Tailscale or local Wi-Fi subnet.

---

## 📁 Architecture & File Layout

```text
mac-agent/
├── bin/
│   └── mac-agent                     # Compiled executable binary
├── Frameworks/
│   └── WebRTC.xcframework            # Vendored Google WebRTC framework (universal macOS + iOS)
├── Sources/
│   └── mac-agent/
│       ├── main.swift                # CLI entrypoint, argument parsing, lifecycle orchestration
│       ├── ScreenCapturer.swift      # ScreenCaptureKit display capture & permission checks
│       ├── WebRTCManager.swift       # RTCPeerConnection lifecycle, video pipeline & SDP/ICE
│       ├── SignalingClient.swift     # WebSocket client matching Step 1 JSON protocol
│       └── QRCodeGenerator.swift     # CoreImage CIQRCodeGenerator PNG exporter & banner
├── build.sh                          # Fast native compilation script (swiftc)
├── Package.swift                     # Swift Package Manager manifest
└── README.md                         # Architecture and run guide
```

---

## 📦 WebRTC Framework Choice: Vendored XCFramework

Requirement 1 requested:
> *"Add dependency on Google's WebRTC framework for macOS (use the `WebRTC-SDK` pod-equivalent SPM package, or document if you need to vendor a prebuilt XCFramework — explain your choice)"*

### Why Vendored `WebRTC.xcframework` was chosen:
1. **Universal Apple Silicon & Intel Binary**: The vendored `WebRTC.xcframework` includes a native `macos-x86_64_arm64` slice with complete hardware acceleration headers (`RTCCVPixelBuffer`, `RTCDefaultVideoEncoderFactory`, `RTCPeerConnection`).
2. **Deterministic, Offline-Capable Builds**: Remote SPM binary targets frequently suffer from git tag drift, GitHub release rate limits, and network latency. Vendoring ensures instantaneous, reproducible builds across development environments.
3. **Toolchain Resilience**: Developer preview and seed versions of macOS / Xcode occasionally encounter package manifest linker quirks with `libPackageDescription.dylib`. A vendored framework compiles cleanly both via SPM and directly via `swiftc` / `build.sh`.

---

## 🛠️ Build Instructions

### Build with `build.sh` (Recommended & Instant):
```bash
cd mac-agent
./build.sh
```
This compiles the executable to `bin/mac-agent` in ~2 seconds with all frameworks and `@rpath` properly linked.

### Build with Swift Package Manager:
```bash
cd mac-agent
swift build -c release
```

---

## 🖥️ Screen Recording Permission Setup

ScreenCaptureKit requires explicit macOS user authorization:
1. When running for the first time, macOS may display a system permission dialog.
2. If denied or unprompted, open:
   **System Settings > Privacy & Security > Screen & System Audio Recording**
3. Add or toggle **Terminal** (or iTerm2 / your IDE terminal) to **ON**.
4. Restart the terminal session if prompted.

---

## 🚀 Running the Agent

### Basic Usage:
```bash
# Ensure signaling server is running on localhost:8000
./bin/mac-agent
```

### CLI Options:
```text
Usage: mac-agent [options]

Options:
  -s, --server <url>   Signaling server URL (default: http://localhost:8000)
  --fps <int>          Target screen capture framerate (default: 30)
  -q, --qr <path>      Output path for room QR code image (default: room_qr.png)
  -h, --help           Show this help message
```

Example with custom server and framerate:
```bash
./bin/mac-agent --server http://192.168.1.100:8000 --fps 30 --qr /tmp/qr.png
```

---

## 🧪 End-to-End Verification with `test-viewer.html`

A browser-based viewer [`test-viewer.html`](../test-viewer.html) is provided to verify video streaming without waiting for the Android app (Step 3).

### Verification Steps:
1. **Start the Signaling Server (Terminal 1)**:
   ```bash
   cd signaling-server
   source .venv/bin/activate
   python main.py
   ```

2. **Start the Mac Agent (Terminal 2)**:
   ```bash
   cd mac-agent
   ./bin/mac-agent
   ```
   Note the 6-character **ROOM CODE** printed in the terminal (e.g., `DA6VFZ`).

3. **Open the Test Viewer (Browser)**:
   Open [`test-viewer.html`](../test-viewer.html) in any modern browser (Google Chrome, Safari, or Firefox):
   ```bash
   open test-viewer.html
   ```

4. **Connect**:
   - Verify the Signaling URL is `ws://localhost:8000`.
   - Enter your 6-character room code.
   - Click **Connect**.

5. **Observe Live Mirroring**:
   - The status badge will transition to **`CONNECTED`** (green).
   - Your Mac display will stream live into the video container.
   - Real-time resolution, FPS counter, and bitrate metrics will display in the bottom overlay.
