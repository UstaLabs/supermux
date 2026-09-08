#if COMPOSE_SHELL
import AVFoundation
import Foundation
import Speech
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

    /// The two dictation backends, one each, for the life of the bridge. Not per-capture: an
    /// `AVAudioEngine` and an `SFSpeechRecognizer` are expensive to stand up, and `SpeechDictation`
    /// counts on being able to tear its analyzer down between sessions to stay under the system's
    /// "maximum number of recognizers" limit — which it can only do if it is the same object.
    @MainActor private lazy var recorder = AudioRecorder()
    @MainActor private lazy var dictation = SpeechDictation()

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
    /// "Save to Files" and every app that can open the type. iOS has no separate save dialog.
    ///
    /// The result is the destination the shared caller shows in its "Saved to …" notice. iOS only
    /// tells us WHICH activity ran (`UIActivity.ActivityType`, e.g. `com.apple.CopyToPasteboard`),
    /// never where a file landed, so the last path component of that identifier is the most honest
    /// label available; "Files" is the fallback when iOS reports nothing. A dismissed sheet is a
    /// cancelled save and returns nil, which is what `completed == false` means here.
    func saveAs(name: String, mime: String, bytes: KotlinByteArray, onResult: @escaping (String?) -> Void) {
        share(name: name, bytes: bytes) { _, completed, activity in
            guard completed else { return onResult(nil) }
            onResult(activity?.rawValue.components(separatedBy: ".").last ?? "Files")
        }
    }

    /// True when the sheet was PRESENTED, per the Kotlin contract — deliberately not the activity's
    /// `completed` flag. iOS does not report what the user did with a shared file, and a plain
    /// dismissal sets `completed = false`; returning that would make the shared caller announce
    /// "couldn't open that" every time someone opened the sheet and changed their mind.
    func openExternally(name: String, mime: String, bytes: KotlinByteArray, onResult: @escaping (KotlinBoolean) -> Void) {
        share(name: name, bytes: bytes) { presented, _, _ in
            onResult(KotlinBoolean(bool: presented))
        }
    }

    /// Writes the bytes to a temp file and presents the share sheet over them.
    ///
    /// The completion fires twice-shaped information exactly once: `presented` says whether the
    /// sheet ever went up, and `completed`/`activity` describe what the user chose (or nothing, if
    /// it was never presented). The two callers above want different halves of that.
    private func share(
        name: String,
        bytes: KotlinByteArray,
        onResult: @escaping (_ presented: Bool, _ completed: Bool, _ activity: UIActivity.ActivityType?) -> Void
    ) {
        guard let presenter = presenter() else { return onResult(false, false, nil) }
        let data = IosBytesKt.dataFrom(bytes: bytes)
        // The name is broker-supplied and ends up in a path: strip any directory part so it cannot
        // escape the temp directory. Mirrors `safeFileName` on the Kotlin side.
        let leaf = name.components(separatedBy: CharacterSet(charactersIn: "/\\")).last
            .flatMap { $0.isEmpty ? nil : $0 } ?? "file"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(leaf)
        guard (try? data.write(to: url, options: .atomic)) != nil else { return onResult(false, false, nil) }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        // iPad has no "present from nowhere": a popover without an anchor is a runtime trap.
        sheet.popoverPresentationController?.sourceView = presenter.view
        sheet.popoverPresentationController?.sourceRect = CGRect(
            x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 0, height: 0
        )
        sheet.completionWithItemsHandler = { activity, completed, _, _ in
            onResult(true, completed, activity)
        }
        presenter.present(sheet, animated: true)
    }

    fileprivate static func pickedFile(from url: URL) -> IosPickedFile? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"
        return IosPickedFile(name: url.lastPathComponent, mime: mime, bytes: IosBytesKt.bytesFrom(data: data))
    }

    func scanQr(onResult: @escaping (String?) -> Void) { onResult(nil) }

    // MARK: microphone + dictation
    //
    // Two objects, never both at once: `AudioRecorder` captures a clip the broker transcribes,
    // `SpeechDictation` recognises on the device. The shared `DictationController` picks one per
    // capture (live when `startTranscript` succeeds, the recorder otherwise), which is what keeps
    // them from fighting over the audio session.
    //
    // Both are `@MainActor`. Every call below arrives on the main thread already — Kotlin's
    // `awaitCallback`/`onMainThread` hop first — so `MainActor.assumeIsolated` is a statement of
    // that fact rather than a hop of its own; a real hop would make these synchronous members
    // impossible.

    /// Whether the app may record. Granted or not-yet-asked → yes (asking is the mic button's own
    /// first step); a refusal is the one state where offering the button is offering nothing.
    /// Read while the composer COMPOSES, so it only reads a cached permission flag.
    func micAvailable() -> Bool {
        AVAudioApplication.shared.recordPermission != .denied
    }

    func requestMicPermission(onResult: @escaping (KotlinBoolean) -> Void) {
        // Speech recognition and the microphone are separate grants, and the live path needs both.
        // The mic is asked for first because a refusal there makes the speech prompt pointless,
        // and a granted mic still leaves the record-then-POST path fully working — so a speech
        // refusal is NOT a failure here.
        AVAudioApplication.requestRecordPermission { granted in
            guard granted else { return onResult(KotlinBoolean(bool: false)) }
            SFSpeechRecognizer.requestAuthorization { _ in
                onResult(KotlinBoolean(bool: true))
            }
        }
    }

    func startRecording() -> Bool {
        MainActor.assumeIsolated { recorder.startGranted() }
    }

    func stopRecording() -> IosCapturedAudio? {
        MainActor.assumeIsolated {
            guard let clip = recorder.stop() else { return nil }
            return IosCapturedAudio(
                bytes: IosBytesKt.bytesFrom(data: clip.data),
                filename: clip.filename,
                mime: "audio/mp4"
            )
        }
    }

    func cancelRecording() {
        MainActor.assumeIsolated { recorder.cancel() }
    }

    /// Optimistic by necessity — the Kotlin side documents why. What CAN be decided on the frame
    /// the user tapped is decided here: a speech authorization the user has refused means the live
    /// path cannot run at all, and answering false sends the shared controller down the
    /// record-then-POST path instead of into a dictation that would produce nothing.
    func startTranscript(glossary: [String], onPartial: @escaping (String) -> Void) -> Bool {
        guard SFSpeechRecognizer.authorizationStatus() != .denied,
              SFSpeechRecognizer.authorizationStatus() != .restricted else { return false }
        return MainActor.assumeIsolated {
            dictation.onPartial = onPartial
            Task { @MainActor in
                let result = await dictation.start(contextualStrings: glossary)
                if result != .started {
                    NSLog("[supermux dictation] on-device start failed: %@", "\(result)")
                }
            }
            return true
        }
    }

    func stopTranscript(onResult: @escaping (String) -> Void) {
        MainActor.assumeIsolated {
            Task { @MainActor in
                let (text, _) = await dictation.stop()
                dictation.onPartial = nil
                onResult(text)
            }
        }
    }

    func cancelTranscript() {
        MainActor.assumeIsolated {
            dictation.onPartial = nil
            dictation.cancel()
        }
    }
    // MARK: read-aloud
    //
    // The low half of `MessageSpeech` only. `MessageTts` in `:ui` decides WHAT to speak, which
    // message owns the speaking state and how the codex chunks are queued — the same split
    // `AndroidTtsEngine` makes. `MessageSpeech.shared` and not a new object, so the process has
    // exactly one `AVSpeechSynthesizer`: two would talk over each other and neither `stop()`
    // could silence the other.

    func speak(text: String, onDone: @escaping () -> Void) {
        MainActor.assumeIsolated { MessageSpeech.shared.speakText(text, onDone: onDone) }
    }

    func playAudioChunk(bytes: KotlinByteArray, onDone: @escaping () -> Void) {
        MainActor.assumeIsolated {
            MessageSpeech.shared.playChunk(IosBytesKt.dataFrom(bytes: bytes), onDone: onDone)
        }
    }

    func stopSpeaking() {
        MainActor.assumeIsolated { MessageSpeech.shared.stop() }
    }

    func shutdownSpeech() {
        MainActor.assumeIsolated { MessageSpeech.shared.shutdownEngine() }
    }
    // MARK: push

    /// The whole `PushManager` sequence, unchanged from the SwiftUI shell: authorisation →
    /// `registerForRemoteNotifications()` → APNs token → relay → broker device registration with
    /// the Keychain'd push keypair the notification service extension decrypts with.
    ///
    /// Nothing is returned. The Kotlin side documents why (`IosBridge.registerPushIfPaired`): the
    /// token arrives at the app delegate, not here, and every step after it already lives in
    /// `PushManager`.
    func registerPushIfPaired() {
        PushManager.shared.registerIfPaired()
    }

    /// Withdraw the delivered notifications for a chat the user is now looking at, and re-badge.
    /// `PushGroupState` (App Group) is the source of truth for the unread counts the NSE keeps.
    func cancelNotificationsFor(sessionId: String) {
        PushManager.shared.clearDelivered(sessionId: sessionId)
    }
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

    /// Also the navigation controller's gesture delegate — see `makeUIViewController`.
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var bridge: SwiftBridge?
        weak var navigation: UINavigationController?

        /// Allow the interactive pop gesture only when there is something to pop.
        ///
        /// UIKit disables `interactivePopGestureRecognizer` whenever the navigation bar is hidden,
        /// and this navigation controller hides it because the shared shell draws its own headers.
        /// Re-enabling it is therefore necessary — but it must NOT be unconditional: with a single
        /// view controller on the stack, letting the gesture begin drives UIKit into a pop it
        /// cannot complete and wedges the navigation controller, after which even programmatic
        /// navigation misbehaves. The count check is what keeps a missing feature from becoming a
        /// broken one.
        ///
        /// Today the stack IS one deep, so this returns false and the edge swipe does nothing;
        /// the shared shell's own back affordances work throughout. Giving Compose's
        /// `PredictiveBackHandler` a real interactive gesture needs a second controller on the
        /// stack to pop against, which is H3/H4 work.
        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            (navigation?.viewControllers.count ?? 0) > 1
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> UINavigationController {
        let bridge = SwiftBridge()
        context.coordinator.bridge = bridge
        let compose = MainViewControllerKt.MainViewController(bridge: bridge)
        let nav = UINavigationController(rootViewController: compose)
        nav.setNavigationBarHidden(true, animated: false)
        nav.interactivePopGestureRecognizer?.delegate = context.coordinator
        context.coordinator.navigation = nav
        // Present sheets from the navigation controller, not from the Compose controller: it is the
        // one that is actually in the window's hierarchy.
        bridge.root = nav
        return nav
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}
}
#endif
