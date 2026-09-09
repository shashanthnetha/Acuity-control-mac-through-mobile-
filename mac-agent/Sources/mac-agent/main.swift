import AppKit

// Ensure unbuffered stdout so logs are printed immediately even when piped
setbuf(stdout, nil)

// Handle CLI management flags if launched from terminal
let args = CommandLine.arguments

if args.contains("--list-trusted") {
    DeviceApprovalManager.shared.listTrustedDevices()
    exit(0)
}

if let idx = args.firstIndex(of: "--revoke"), idx + 1 < args.count {
    let target = args[idx + 1]
    DeviceApprovalManager.shared.revokeDevice(target: target)
    exit(0)
}

if args.contains("--help") || args.contains("-h") {
    print("""
    Usage: Acuity [options]
    Runs as a native macOS menu bar application.

    Options:
      --list-trusted       List all authorized client devices
      --revoke <fp|all>    Revoke trust for a client device fingerprint (or 'all')
      -h, --help           Show this help message
    """)
    exit(0)
}

// Launch native AppKit menu bar application
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
