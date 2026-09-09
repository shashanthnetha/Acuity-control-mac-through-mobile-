import AppKit

/// View controller displayed inside an NSPopover showing the active pairing QR code and room code.
public final class QRPopoverViewController: NSViewController {

    private var roomCode: String
    private var serverURL: String

    private var qrImageView: NSImageView!
    private var roomCodeLabel: NSTextField!
    private var copyButton: NSButton!

    public init(roomCode: String, serverURL: String) {
        self.roomCode = roomCode
        self.serverURL = serverURL
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public override func loadView() {
        self.view = NSView(frame: NSRect(x: 0, y: 0, width: 260, height: 320))
        setupUI()
        updateContent()
    }

    public func update(roomCode: String, serverURL: String) {
        self.roomCode = roomCode
        self.serverURL = serverURL
        if isViewLoaded {
            updateContent()
        }
    }

    private func setupUI() {
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor(srgbRed: 0x0A/255.0, green: 0x0E/255.0, blue: 0x14/255.0, alpha: 1.0).cgColor

        // Title
        let titleLabel = NSTextField(labelWithString: "Pair Android Device")
        titleLabel.font = NSFont.systemFont(ofSize: 13, weight: .bold)
        titleLabel.textColor = NSColor(srgbRed: 0xF0/255.0, green: 0xF6/255.0, blue: 0xFC/255.0, alpha: 1.0)
        titleLabel.alignment = .center
        titleLabel.frame = NSRect(x: 10, y: 286, width: 240, height: 20)
        view.addSubview(titleLabel)

        // QR Background Container (Crisp white padding for reliable camera barcode scanning)
        let qrContainer = NSBox(frame: NSRect(x: 35, y: 95, width: 190, height: 190))
        qrContainer.boxType = .custom
        qrContainer.borderWidth = 1.0
        qrContainer.borderColor = NSColor(srgbRed: 0x20/255.0, green: 0x26/255.0, blue: 0x33/255.0, alpha: 1.0)
        qrContainer.cornerRadius = 10
        qrContainer.fillColor = .white
        view.addSubview(qrContainer)

        // QR Image View
        let imgView = NSImageView(frame: NSRect(x: 8, y: 8, width: 174, height: 174))
        imgView.imageScaling = .scaleProportionallyUpOrDown
        qrContainer.contentView?.addSubview(imgView)
        self.qrImageView = imgView

        // Room Code Container
        let roomCodeBox = NSBox(frame: NSRect(x: 20, y: 46, width: 220, height: 40))
        roomCodeBox.boxType = .custom
        roomCodeBox.borderWidth = 1.0
        roomCodeBox.borderColor = NSColor(srgbRed: 0x20/255.0, green: 0x26/255.0, blue: 0x33/255.0, alpha: 1.0)
        roomCodeBox.cornerRadius = 8
        roomCodeBox.fillColor = NSColor(srgbRed: 0x15/255.0, green: 0x1A/255.0, blue: 0x24/255.0, alpha: 1.0)

        let label = NSTextField(labelWithString: roomCode)
        label.font = NSFont.monospacedSystemFont(ofSize: 18, weight: .heavy)
        label.textColor = NSColor(srgbRed: 0x4D/255.0, green: 0xD0/255.0, blue: 0xFF/255.0, alpha: 1.0)
        label.alignment = .center
        label.frame = NSRect(x: 10, y: 8, width: 135, height: 24)
        roomCodeBox.contentView?.addSubview(label)
        self.roomCodeLabel = label

        let copyBtn = NSButton(title: "Copy", target: self, action: #selector(copyCodeClicked))
        copyBtn.bezelStyle = .rounded
        copyBtn.controlSize = .small
        copyBtn.frame = NSRect(x: 148, y: 7, width: 62, height: 26)
        roomCodeBox.contentView?.addSubview(copyBtn)
        self.copyButton = copyBtn

        view.addSubview(roomCodeBox)

        // Helper instruction
        let helperLabel = NSTextField(wrappingLabelWithString: "Scan QR code or enter code in Acuity app")
        helperLabel.font = NSFont.monospacedSystemFont(ofSize: 10, weight: .regular)
        helperLabel.textColor = NSColor(srgbRed: 0x8B/255.0, green: 0x94/255.0, blue: 0x9E/255.0, alpha: 1.0)
        helperLabel.alignment = .center
        helperLabel.frame = NSRect(x: 10, y: 10, width: 240, height: 28)
        view.addSubview(helperLabel)
    }

    private func updateContent() {
        roomCodeLabel.stringValue = roomCode
        let joinURL = QRCodeGenerator.makeJoinURL(roomCode: roomCode, serverURL: serverURL)
        if let qrImage = QRCodeGenerator.createQRImage(from: joinURL, scaleFactor: 16.0) {
            qrImageView.image = qrImage
        }
    }

    @objc private func copyCodeClicked() {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(roomCode, forType: .string)
        copyButton.title = "Copied!"
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.copyButton.title = "Copy"
        }
    }
}
