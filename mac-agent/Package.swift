// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "mac-agent",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "mac-agent", targets: ["mac-agent"])
    ],
    dependencies: [],
    targets: [
        .binaryTarget(
            name: "WebRTC",
            path: "Frameworks/WebRTC.xcframework"
        ),
        .executableTarget(
            name: "mac-agent",
            dependencies: [
                "WebRTC"
            ],
            path: "Sources/mac-agent",
            linkerSettings: [
                .linkedFramework("ScreenCaptureKit"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreVideo"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreImage"),
                .linkedFramework("AppKit"),
                .unsafeFlags([
                    "-Xlinker", "-rpath",
                    "-Xlinker", "@executable_path/../Frameworks/WebRTC.xcframework/macos-x86_64_arm64",
                    "-Xlinker", "-rpath",
                    "-Xlinker", "@loader_path/../Frameworks/WebRTC.xcframework/macos-x86_64_arm64"
                ])
            ]
        ),
    ]
)
