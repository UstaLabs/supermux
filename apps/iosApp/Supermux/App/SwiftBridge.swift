#if COMPOSE_SHELL
import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import SupermuxKit

/// The Swift side of `IosBridge` — everything the Compose root cannot do for itself.
///
/// The shape is dictated by the Kotlin side and explained there: no `suspend` and no `@Composable`
/// members, because Swift can CALL a Kotlin suspend function but cannot implement one. Every
/// asynchronous seam is therefore "here is a completion, call it exactly once", and Kotlin turns
/// that back into a suspending `Platform` call (`awaitCallback`).
///
/// What is implemented here is the H2 set: the root view controller everything else is presented
/// from, opening a URL, and the file seams (pick, capture, save, share). Push, the microphone,
/// speech and the QR scanner are the H3 set and answer "not available" for now — immediately, never
/// by staying silent, so nothing awaiting one can hang.
///
/// Retention: the picker delegates are UIKit objects that the presenting controller does NOT own,
/// so each helper below keeps itself alive in `pendingDelegates` until its completion fires.
/// Without that the delegate deallocates the moment the call returns and the callback never
/// arrives — the classic "the picker opens and nothing ever happens" bug.
final class SwiftBridge: NSObject, IosBridge {

    /// Set by `SupermuxApp` once the Compose controller is inside its navigation controller.
    weak var root: UIViewController?

    private var pendingDelegates: [NSObject] = []

    private func retain(_ delegate: NSObject) { pendingDelegates.append(delegate) }
    private func release(_ delegate: NSObject) { pendingDelegates.removeAll { $0 === delegate } }

    // MARK: presentation

    func rootViewController() -> UIViewController? { root }

    /// The controller a sheet may actually be presented from: the deepest one already presenting,
    /// so a picker raised from a screen that itself sits in a sheet does not fail with
    /// "already presenting".
    private func presenter() -> UIViewController? {
        var vc = root
        while let presented = vc?.presentedViewController { vc = presented }
        return vc
    }

    // MARK: system

    func openUrl(url: String) {
        guard let target = URL(string: url) else { return }
        UIApplication.shared.open(target)
    }

    // MARK: files

    func pickFiles(kind: String, onResult: @escaping ([IosPickedFile]) -> Void) {
        guard let presenter = presenter() else { return onResult([]) }
        let types: [UTType]
        switch kind {
        case "images": types = [.image]
        case "media": types = [.image, .movie]
        default: types = [.item]
        }
        // `asCopy: true` is what makes reading safe: iOS hands back a copy in our own temp
        // container, so there is no security-scoped URL whose access must be balanced — and no
        // revoked URL for Kotlin to read later.
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        let delegate = DocumentPickDelegate { urls in
            onResult(urls.compactMap { SwiftBridge.pickedFile(from: $0) })
        }
        picker.delegate = delegate
        picker.allowsMultipleSelection = true
        retain(delegate)
        delegate.onFinish = { [weak self] in self?.release(delegate) }
        presenter.present(picker, animated: true)
    }

    func captureImage(onResult: @escaping (IosPickedFile?) -> Void) {
        capture(mediaTypes: [UTType.image.identifier], onResult: onResult)
    }

    func captureVideo(onResult: @escaping (IosPickedFile?) -> Void) {
        capture(mediaTypes: [UTType.movie.identifier], onResult: onResult)
    }

    private func capture(mediaTypes: [String], onResult: @escaping (IosPickedFile?) -> Void) {
        guard UIImagePickerController.isSourceTypeAvailable(.camera), let presenter = presenter() else {
            // No camera (every simulator) — answer immediately rather than presenting a black sheet.
            return onResult(nil)
        }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = mediaTypes
        let delegate = CaptureDelegate(onResult: onResult)
        picker.delegate = delegate
        retain(delegate)
        delegate.onFinish = { [weak self] in self?.release(delegate) }
        presenter.present(picker, animated: true)
    }

    /// "Save as…" and "Open with…" are the same sheet on iOS: the share sheet, which offers both
    /// "Save to Files" and every app that can open the type. iOS has no separate save dialog, and
    /// `UIDocumentPickerViewController(forExporting:)` would offer ONLY the file system.
    func saveAs(name: String, mime: String, bytes: KotlinByteArray, onResult: @escaping (String?) -> Void) {
        share(name: name, bytes: bytes) { completed in onResult(completed ? "Files" : nil) }
    }

    func openExternally(name: String, mime: String, bytes: KotlinByteArray, onResult: @escaping (KotlinBoolean) -> Void) {
        share(name: name, bytes: bytes) { presented in onResult(KotlinBoolean(bool: presented)) }
    }

    private func share(name: String, bytes: KotlinByteArray, onResult: @escaping (Bool) -> Void) {
        guard let presenter = presenter() else { return onResult(false) }
        let data = IosBytesKt.dataFrom(bytes: bytes)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        guard (try? data.write(to: url, options: .atomic)) != nil else { return onResult(false) }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        // iPad has no "present from nowhere": a popover without an anchor is a runtime trap.
        sheet.popoverPresentationController?.sourceView = presenter.view
        sheet.popoverPresentationController?.sourceRect = CGRect(
            x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 0, height: 0
        )
        sheet.completionWithItemsHandler = { _, completed, _, _ in onResult(completed) }
        presenter.present(sheet, animated: true)
    }

    fileprivate static func pickedFile(from url: URL) -> IosPickedFile? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"
        return IosPickedFile(name: url.lastPathComponent, mime: mime, bytes: IosBytesKt.bytesFrom(data: data))
    }

    // MARK: H3 — not wired yet
    //
    // Each answers the "unavailable / cancelled" value IMMEDIATELY. That is the contract the Kotlin
    // side documents for `NoopIosBridge`: a member that simply did nothing would leave whatever is
    // awaiting it suspended forever, which reads as a frozen screen rather than a missing feature.

    func scanQr(onResult: @escaping (String?) -> Void) { onResult(nil) }
    func micAvailable() -> Bool { false }
    func requestMicPermission(onResult: @escaping (KotlinBoolean) -> Void) { onResult(KotlinBoolean(bool: false)) }
    func startRecording() -> Bool { false }
    func stopRecording(onResult: @escaping (KotlinByteArray?, String?, String?) -> Void) { onResult(nil, nil, nil) }
    func cancelRecording() {}
    func startTranscript(glossary: [String], onPartial: @escaping (String) -> Void) -> Bool { false }
    func stopTranscript(onResult: @escaping (String) -> Void) { onResult("") }
    func cancelTranscript() {}
    func speak(text: String, onDone: @escaping () -> Void) { onDone() }
    func playAudioChunk(bytes: KotlinByteArray, onDone: @escaping () -> Void) { onDone() }
    func stopSpeaking() {}
    func shutdownSpeech() {}
    func registerForPush(onToken: @escaping (String?) -> Void) { onToken(nil) }
    func cancelNotificationsFor(sessionId: String) {}
}

/// `UIDocumentPickerDelegate` as a retained one-shot. Both delegate methods must resolve the
/// completion — a dismissed picker calls `wasCancelled`, not `didPickDocumentsAt`.
private final class DocumentPickDelegate: NSObject, UIDocumentPickerDelegate {
    private let onResult: ([URL]) -> Void
    var onFinish: (() -> Void)?
    private var fired = false

    init(onResult: @escaping ([URL]) -> Void) { self.onResult = onResult }

    private func finish(_ urls: [URL]) {
        guard !fired else { return }
        fired = true
        onResult(urls)
        onFinish?()
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        finish(urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finish([])
    }
}

/// The camera's delegate, same one-shot discipline.
private final class CaptureDelegate: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    private let onResult: (IosPickedFile?) -> Void
    var onFinish: (() -> Void)?
    private var fired = false

    init(onResult: @escaping (IosPickedFile?) -> Void) { self.onResult = onResult }

    private func finish(_ file: IosPickedFile?, _ picker: UIImagePickerController) {
        guard !fired else { return }
        fired = true
        picker.dismiss(animated: true)
        onResult(file)
        onFinish?()
    }

    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        if let movie = info[.mediaURL] as? URL {
            finish(SwiftBridge.pickedFile(from: movie), picker)
        } else if let image = info[.originalImage] as? UIImage, let png = image.pngData() {
            finish(
                IosPickedFile(name: "photo.png", mime: "image/png", bytes: IosBytesKt.bytesFrom(data: png)),
                picker
            )
        } else {
            finish(nil, picker)
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        finish(nil, picker)
    }
}

/// The shared Compose root, as a SwiftUI view.
///
/// The `UINavigationController` is not decoration. Compose Multiplatform delivers the interactive
/// swipe-back that `PredictiveBackHandler` (and therefore every back gesture in the shared shell)
/// listens for by driving the hosting navigation controller's pop interaction — hosting the Compose
/// controller bare would leave the app with no edge-swipe back at all, on a platform where that is
/// the primary way anyone navigates. The bar itself is hidden because the shared shell draws its
/// own headers.
///
/// The `SwiftBridge` is held by the coordinator: nothing else retains it (the Kotlin side holds it
/// weakly-in-effect through an interface reference that does not own the Swift object's lifetime),
/// and a deallocated bridge would turn every picker and share sheet into a silent no-op.
struct ComposeRootView: UIViewControllerRepresentable {

    final class Coordinator {
        var bridge: SwiftBridge?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> UINavigationController {
        let bridge = SwiftBridge()
        context.coordinator.bridge = bridge
        let compose = MainViewControllerKt.MainViewController(bridge: bridge)
        let nav = UINavigationController(rootViewController: compose)
        nav.setNavigationBarHidden(true, animated: false)
        // Present sheets from the navigation controller, not from the Compose controller: it is the
        // one that is actually in the window's hierarchy.
        bridge.root = nav
        return nav
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}
}
#endif
