#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "🔨 Building mac-agent..."
mkdir -p bin

FRAMEWORK_DIR="$SCRIPT_DIR/Frameworks/WebRTC.xcframework/macos-x86_64_arm64"

swiftc \
  Sources/mac-agent/QRCodeGenerator.swift \
  Sources/mac-agent/ScreenCapturer.swift \
  Sources/mac-agent/SignalingClient.swift \
  Sources/mac-agent/WebRTCManager.swift \
  Sources/mac-agent/InputInjector.swift \
  Sources/mac-agent/BonjourAdvertiser.swift \
  Sources/mac-agent/DeviceApprovalManager.swift \
  Sources/mac-agent/PermissionsManager.swift \
  Sources/mac-agent/QRPopoverViewController.swift \
  Sources/mac-agent/MenuBarController.swift \
  Sources/mac-agent/MacAgentApp.swift \
  Sources/mac-agent/AppDelegate.swift \
  Sources/mac-agent/main.swift \
  -F "$FRAMEWORK_DIR" \
  -framework WebRTC \
  -framework ScreenCaptureKit \
  -framework CoreMedia \
  -framework CoreVideo \
  -framework CoreGraphics \
  -framework CoreImage \
  -framework AppKit \
  -framework ApplicationServices \
  -framework ServiceManagement \
  -framework Foundation \
  -Xlinker -rpath -Xlinker "@executable_path/../Frameworks/WebRTC.xcframework/macos-x86_64_arm64" \
  -Xlinker -rpath -Xlinker "@executable_path/Frameworks/WebRTC.xcframework/macos-x86_64_arm64" \
  -target arm64-apple-macosx13.0 \
  -O \
  -o bin/mac-agent

echo "✅ Build complete: $SCRIPT_DIR/bin/mac-agent"
