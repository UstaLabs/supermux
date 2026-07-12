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
        let vc = ScannerController()
        vc.coordinator = context.coordinator
        return vc
    }

    func updateUIViewController(_ uiViewController: ScannerController, context: Context) {}

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        private let onCode: (String) -> Void
        private var fired = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !fired,
                  let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  let value = obj.stringValue, !value.isEmpty else { return }
            fired = true
            // A haptic tick + hop to the main actor for the SwiftUI callback.
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            DispatchQueue.main.async { self.onCode(value) }
        }
    }

    /// Hosts the capture session + a full-bleed preview layer, and drives start/stop with the view
    /// lifecycle. Start/stop run off the main thread (AVCaptureSession requirement).
    final class ScannerController: UIViewController {
        weak var coordinator: Coordinator?
        private let session = AVCaptureSession()
        private let sessionQueue = DispatchQueue(label: "dev.supermux.qrscanner")
        private var preview: AVCaptureVideoPreviewLayer?

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            configureSession()
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            view.layer.addSublayer(layer)
            preview = layer
        }

        private func configureSession() {
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(coordinator, queue: .main)
            output.metadataObjectTypes = [.qr]
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            sessionQueue.async { [session] in if !session.isRunning { session.startRunning() } }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            sessionQueue.async { [session] in if session.isRunning { session.stopRunning() } }
        }
    }
}
#endif
