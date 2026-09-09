#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APP_NAME="Acuity"
DIST_DIR="$SCRIPT_DIR/dist"
SCRATCH_DIR="/Users/netha/.gemini/antigravity-ide/brain/f1d8be68-ee74-4751-9e45-763a23b3cb7e/scratch"
STAGING_DIR="$SCRATCH_DIR/acuity_build"
APP_BUNDLE="$STAGING_DIR/$APP_NAME.app"
DMG_PATH="$DIST_DIR/$APP_NAME.dmg"
FRAMEWORK_SOURCE="$SCRIPT_DIR/Frameworks/WebRTC.xcframework/macos-x86_64_arm64/WebRTC.framework"

echo "═══════════════════════════════════════════════════════════"
echo "  🚀 PACKAGING ACUITY MACOS MENU-BAR APPLICATION (.APP & .DMG)"
echo "═══════════════════════════════════════════════════════════"

# 1. Clean previous dist and staging
echo "🧹 Preparing build staging..."
rm -rf "$DIST_DIR"
rm -rf "$STAGING_DIR"
mkdir -p "$DIST_DIR"
mkdir -p "$STAGING_DIR"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
mkdir -p "$APP_BUNDLE/Contents/Resources"
mkdir -p "$APP_BUNDLE/Contents/Frameworks"

# 2. Compile Swift source files
echo "🔨 Compiling $APP_NAME executable with AppKit and WebRTC..."
swiftc \
  Sources/mac-agent/QRCodeGenerator.swift \
  Sources/mac-agent/ScreenCapturer.swift \
  Sources/mac-agent/SignalingClient.swift \
  Sources/mac-agent/FileTransferManager.swift \
  Sources/mac-agent/WebRTCManager.swift \
  Sources/mac-agent/InputInjector.swift \
  Sources/mac-agent/BonjourAdvertiser.swift \
  Sources/mac-agent/DeviceApprovalManager.swift \
  Sources/mac-agent/PermissionsManager.swift \
  Sources/mac-agent/QRPopoverViewController.swift \
  Sources/mac-agent/MenuBarController.swift \
  Sources/mac-agent/MacAgentApp.swift \
  Sources/mac-agent/EmbeddedSignalingServer.swift \
  Sources/mac-agent/AppDelegate.swift \
  Sources/mac-agent/main.swift \
  -F "$SCRIPT_DIR/Frameworks/WebRTC.xcframework/macos-x86_64_arm64" \
  -framework WebRTC \
  -framework ScreenCaptureKit \
  -framework CoreMedia \
  -framework CoreVideo \
  -framework CoreGraphics \
  -framework CoreImage \
  -framework AppKit \
  -framework ApplicationServices \
  -framework ServiceManagement \
  -framework CryptoKit \
  -framework Network \
  -framework Foundation \
  -Xlinker -rpath -Xlinker "@executable_path/../Frameworks" \
  -Xlinker -rpath -Xlinker "@loader_path/../Frameworks" \
  -target arm64-apple-macosx13.0 \
  -O \
  -o "$APP_BUNDLE/Contents/MacOS/$APP_NAME"


# 3. Copy Plist, Icon, and Frameworks
echo "📦 Bundling Info.plist and AppIcon.icns..."
cp "$SCRIPT_DIR/Resources/Info.plist" "$APP_BUNDLE/Contents/Info.plist"
cp "$SCRIPT_DIR/Resources/AppIcon.icns" "$APP_BUNDLE/Contents/Resources/AppIcon.icns"

echo "📦 Bundling WebRTC.framework into Contents/Frameworks..."
cp -R "$FRAMEWORK_SOURCE" "$APP_BUNDLE/Contents/Frameworks/"

# 4. Code Signing
echo "🔏 Clearing extended attributes..."
xattr -cr "$APP_BUNDLE"

echo "🔏 Signing bundle with ad-hoc identity and fixed designated requirement..."
codesign --force --deep --sign - --identifier "com.sha.acuity" -r='designated => identifier "com.sha.acuity"' "$APP_BUNDLE/Contents/Frameworks/WebRTC.framework"
codesign --force --deep --sign - --identifier "com.sha.acuity" -r='designated => identifier "com.sha.acuity"' "$APP_BUNDLE"

echo "🔍 Verifying code signature..."
codesign --verify --verbose "$APP_BUNDLE"

# 5. Build DMG Installer
echo "💿 Creating drag-and-drop installer disk image ($DMG_PATH)..."
DMG_STAGING="$STAGING_DIR/dmg_staging"
mkdir -p "$DMG_STAGING"

cp -R "$APP_BUNDLE" "$DMG_STAGING/"
ln -s /Applications "$DMG_STAGING/Applications"

hdiutil create \
  -volname "Acuity" \
  -srcfolder "$DMG_STAGING" \
  -ov \
  -format UDZO \
  "$DMG_PATH"

# Copy final artifacts to dist
echo "📁 Copying final Acuity.app to $DIST_DIR..."
cp -R "$APP_BUNDLE" "$DIST_DIR/"

# Also copy Acuity.dmg to workspace root for web server distribution
cp "$DMG_PATH" "$SCRIPT_DIR/../Acuity.dmg"
cp "$DMG_PATH" "$SCRIPT_DIR/../Macky.dmg" 2>/dev/null || true

rm -rf "$STAGING_DIR"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  🎉 BUILD & PACKAGING COMPLETE!"
echo "═══════════════════════════════════════════════════════════"
echo "  📱 Application Bundle:  $DIST_DIR/$APP_NAME.app"
echo "  💿 Disk Image (.dmg):   $DMG_PATH"
echo "  🌐 Web Distribution:   http://localhost:8080/Acuity.dmg"
echo "═══════════════════════════════════════════════════════════"
