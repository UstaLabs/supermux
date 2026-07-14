#if os(iOS)
import SwiftUI
import AVFoundation

/// A live camera QR scanner (spec §3.4 add-host "Scan"). Wraps an `AVCaptureSession` feeding a
/// metadata output filtered to `.qr`; the first decoded payload fires `onCode` (once) and the
/// session stops. iOS-only — the Mac add-host flow uses paste/URL. Camera use is declared by
/// `NSCameraUsageDescription` in Info.plist ("scans the pairing QR code printed by your broker").
struct QRScannerView: UIViewControllerRepresentable {
    var onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIViewController(context: Context) -> ScannerController {
        ScannerController(coordinator: context.coordinator)
    }

    func updateUIViewController(_ uiViewController: ScannerController, context: Context) {}

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        private let onCode: (String) -> Void
        private var fired = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard let value = metadataObjects
                .compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue })
                .first(where: { !$0.isEmpty }) else { return }
            accept(value)
        }

        /// Kept separate from AVFoundation delivery so the one-shot decode contract is testable.
        func accept(_ value: String) {
            guard !fired, !value.isEmpty else { return }
            fired = true
            // A haptic tick + hop to the main actor for the SwiftUI callback.
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            DispatchQueue.main.async { self.onCode(value) }
        }
    }

    /// Hosts the capture session + a full-bleed preview layer, and drives start/stop with the view
    /// lifecycle. Start/stop run off the main thread (AVCaptureSession requirement).
    final class ScannerController: UIViewController {
        private let coordinator: Coordinator
        private let session = AVCaptureSession()
        private let sessionQueue = DispatchQueue(label: "dev.supermux.qrscanner")
        private var preview: AVCaptureVideoPreviewLayer?
        private var configured = false
        private var visible = false
        private let statusLabel = UILabel()

        init(coordinator: Coordinator) {
            self.coordinator = coordinator
            super.init(nibName: nil, bundle: nil)
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            view.layer.addSublayer(layer)
            preview = layer

            statusLabel.textColor = .white
            statusLabel.font = .preferredFont(forTextStyle: .headline)
            statusLabel.textAlignment = .center
            statusLabel.numberOfLines = 0
            statusLabel.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(statusLabel)
            NSLayoutConstraint.activate([
                statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 28),
                statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -28),
                statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            ])
        }

        private func prepareCamera() {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                configureAndStart()
            case .notDetermined:
                showStatus("Allow camera access to scan the pairing QR code.")
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        if granted { self.configureAndStart() }
                        else { self.showDenied() }
                    }
                }
            case .denied, .restricted:
                showDenied()
            @unknown default:
                showDenied()
            }
        }

        private func configureAndStart() {
            showStatus(nil)
            sessionQueue.async { [weak self] in
                guard let self else { return }
                if !self.configured { self.configureSession() }
                guard self.configured, self.visible, !self.session.isRunning else { return }
                self.session.startRunning()
            }
        }

        private func configureSession() {
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else {
                DispatchQueue.main.async { [weak self] in self?.showStatus("Camera unavailable. Paste the pairing link instead.") }
                return
            }
            session.beginConfiguration()
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else {
                session.commitConfiguration()
                DispatchQueue.main.async { [weak self] in self?.showStatus("QR scanning is unavailable. Paste the pairing link instead.") }
                return
            }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(coordinator, queue: .main)
            output.metadataObjectTypes = [.qr]
            session.commitConfiguration()
            configured = true
        }

        private func showDenied() {
            showStatus("Camera access is off. Enable it in Settings, or close the scanner and paste the pairing link.")
        }

        private func showStatus(_ text: String?) {
            statusLabel.text = text
            statusLabel.isHidden = text == nil
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            visible = true
            prepareCamera()
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            visible = false
            sessionQueue.async { [session] in if session.isRunning { session.stopRunning() } }
        }
    }
}
#endif
