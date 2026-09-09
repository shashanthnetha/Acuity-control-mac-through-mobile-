# Acuity v1.0.0 — Zero-Latency Mac Control from Android

Turn any Android phone or tablet into a high-performance, ultra-low latency (<16ms) secondary display, multi-touch trackpad, and remote keyboard for your Mac.

---

### ⚠️ Prerequisite (Important!)
> **Both your Mac and your Android device MUST be connected to the same Wi-Fi network** (or the same Tailscale VPN mesh / local mobile hotspot). Local network connectivity is required for direct WebRTC peer streaming and auto-discovery.

---

### 📦 Downloads

| Asset | Platform | Description |
| :--- | :--- | :--- |
| 🍏 [**Acuity.dmg**](https://github.com/shashanthnetha/Acuity-control-mac-through-mobile-/releases/download/v1.0.0/Acuity.dmg) | macOS 13.0+ | Universal installer for Apple Silicon (M1/M2/M3/M4) & Intel Macs |
| 🤖 [**acuity.apk**](https://github.com/shashanthnetha/Acuity-control-mac-through-mobile-/releases/download/v1.0.0/acuity.apk) | Android 8.0+ | Signed APK package for Android phones and tablets |

---

### ⚡ Highlights & Features

- 🖥️ **Hardware-Accelerated Screen Mirroring**: Direct ScreenCaptureKit + VideoToolbox H.264 stream to Android with sub-16ms glass-to-glass latency.
- 🖱️ **Precision Multi-Touch Trackpad**: Smooth inertial scrolling, two-finger tap for right-click, tap to click, and drag gestures.
- ⌨️ **Full Remote Keyboard**: Low-latency typing with special macOS key support (Cmd, Option, Ctrl, Shift, Arrows, Esc, Backspace).
- 🔌 **Embedded Native Signaling Server**: Zero terminal setup! The macOS app includes an embedded Swift WebSocket server (`NWListener`) running on port 8000. No Python or Node.js required.
- 📡 **Zero-Config Auto-Discovery**: Discovers your Mac automatically across your Wi-Fi using Bonjour / mDNS (`_acuity._tcp`).
- 📷 **Instant QR Code Pairing**: Scan your Mac's screen with your Android camera to connect in 1 second.
- 📋 **Bidirectional Clipboard Sync**: Seamlessly copy on Mac and paste on Android, or vice versa.
- 📁 **P2P File Transfer**: High-speed chunked file transfer over encrypted WebRTC DataChannels.
- 🎨 **Sleek Technical UI**: Crafted with a modern dark interface inspired by Tailscale and Raycast.

---

### 🚀 Quick Start Guide

1. **On your Mac**:
   - Download and open `Acuity.dmg`.
   - Drag `Acuity.app` into `/Applications` and launch it.
   - Grant **Screen Recording** and **Accessibility** permissions when prompted.
   - Click the Acuity menu bar icon to view connection info or QR code.

2. **On your Android Phone**:
   - Download and install `acuity.apk`.
   - Ensure your phone is connected to the **same Wi-Fi network** as your Mac.
   - Open Acuity — your Mac will be auto-detected, or tap "Scan QR Code".
   - Start controlling your Mac instantly!
