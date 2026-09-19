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

    // MARK: windows (iPad)

    /// True for the bridge of an `extra` scene: `closeWindow` closes only those, never the main one.
    var isExtraWindow = false

    func supportsExtraWindows() -> Bool {
        UIApplication.shared.supportsMultipleScenes && UIDevice.current.userInterfaceIdiom == .pad
    }

    // Both come from a tap or a composition, on the main thread — same as the recorder calls.
    func openExtraWindow(claim: String) {
        MainActor.assumeIsolated { SceneWindows.open?(claim) }
    }

    func closeWindow() {
        guard isExtraWindow, let session = root?.view.window?.windowScene?.session else { return }
        // Bring the main window forward first: destroying the only foreground scene would
        // otherwise drop the user on the Home Screen, with supermux still running behind it.
        MainActor.assumeIsolated { SceneWindows.activateMain() }
        UIApplication.shared.requestSceneSessionDestruction(session, options: nil)
    }

    func openMainWindow() {
        MainActor.assumeIsolated {
            if !SceneWindows.activateMain() { SceneWindows.openMain?() }
        }
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

    // MARK: QR

    /// Present the pairing-code scanner. `onResult` fires EXACTLY ONCE — with the decoded string,
    /// or nil when the user backs out, when no controller is available to present from, or when
    /// there is no camera at all (every simulator). Never firing would leave the Add host screen
    /// suspended on a sheet the user has already dismissed.
    ///
    /// It presents `QRScannerView.ScannerController` — the existing scanner's own UIKit
    /// controller — DIRECTLY, rather than the `UIViewControllerRepresentable` wrapped back up in a
    /// `UIHostingController`. That round trip would add a SwiftUI layer whose only job is to host
    /// the controller we already have. What the SwiftUI screen supplied and a bare controller does
    /// not is a way OUT: hence the navigation controller and its Cancel button, without which a
    /// camera the user changes their mind about is a dead end.
    ///
    /// There are TWO ways out, though, and the second is the easy one to miss: this is presented
    /// as a sheet, so it can also be swiped down. That dismissal runs through neither Cancel nor a
    /// decode, so without the presentation-controller delegate below the completion would never
    /// fire — leaving Kotlin suspended forever, the delegate leaked in `pendingDelegates`, and the
    /// Add host screen's scan button permanently dead. The gesture is deliberately NOT disabled
    /// (`isModalInPresentation`): swiping a sheet away is what people expect, so it is reported as
    /// a cancel rather than forbidden.
    func scanQr(onResult: @escaping (String?) -> Void) {
        guard let presenter = presenter() else { return onResult(nil) }
        let delegate = ScanDelegate(onResult: onResult)
        retain(delegate)
        delegate.onFinish = { [weak self] in self?.release(delegate) }
        let scanner = QRScannerView.ScannerController(
            coordinator: QRScannerView.Coordinator { code in delegate.finish(code) }
        )
        scanner.title = "Scan pairing code"
        let nav = UINavigationController(rootViewController: scanner)
        scanner.navigationItem.leftBarButtonItem = UIBarButtonItem(
            systemItem: .cancel,
            primaryAction: UIAction { [weak nav] _ in
                nav?.dismiss(animated: true)
                delegate.finish(nil)
            }
        )
        delegate.presented = nav
        nav.presentationController?.delegate = delegate
        presenter.present(nav, animated: true)
    }
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

    // MARK: terminal (H5)

    /// The SwiftTerm vendor. One per bridge and stateless — every terminal it makes owns its own
    /// view, coordinator and prediction pipeline, so nothing is shared between panes.
    private lazy var terminals = ComposeTerminalVendor()

    func terminalVendor() -> (any IosTerminalVendor)? { terminals }
}

/// The scanner's one-shot completion, and the dismissal that goes with it.
///
/// Same retention discipline as the picker delegates: nothing else owns this object, so the bridge
/// holds it until the completion fires. A decode dismisses the sheet itself (the user is done and
/// the camera should stop); a Cancel has already dismissed it before finishing.
private final class ScanDelegate: NSObject, UIAdaptivePresentationControllerDelegate {
    private let onResult: (String?) -> Void
    var onFinish: (() -> Void)?
    weak var presented: UIViewController?
    private var fired = false

    init(onResult: @escaping (String?) -> Void) { self.onResult = onResult }

    func finish(_ code: String?) {
        guard !fired else { return }
        fired = true
        if code != nil { presented?.dismiss(animated: true) }
        onResult(code)
        onFinish?()
    }

    /// The sheet was swiped away. UIKit has already dismissed it, so this only has to report the
    /// cancel — the third exit, beside Cancel and a decode, and the one that silently hung.
    /// Not called for a programmatic `dismiss`, so a decode cannot double-fire (and `fired`
    /// guards that regardless).
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        finish(nil)
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
/// The `UINavigationController` is here to PRESENT, not to navigate. It is the controller that is
/// actually in the window's hierarchy, so it is the one every sheet the bridge puts up — pickers,
/// the share sheet, the QR scanner — is presented from; the Compose controller inside it is not a
/// reliable presenter. Its bar is hidden because the shared shell draws its own headers.
///
/// It is explicitly NOT what delivers the back gesture, whatever the comment here used to say.
/// Compose Multiplatform (since 1.10.3) installs its OWN pair of `UIKitBackGestureRecognizer`s on
/// the window's DIRECT CHILD — the `UITransitionView` under the SwiftUI hosting controller, which
/// is an ANCESTOR of this navigation controller's view — and feeds them straight into the
/// `NavigationEventDispatcher` that the shared `PredictiveBackHandler` listens on. Nothing is
/// configured and nothing is needed here. (Measured by walking the hierarchy and printing every
/// recogniser; see D1 in the H4 execution log.)
/// The start edge (left, in LTR) is permanently bound to back; the opposite edge is governed by
/// `ComposeUIViewControllerConfiguration.endEdgePanGestureBehavior`, which stays at its `Disabled`
/// default on purpose, since a right-edge swipe means nothing on iOS in a left-to-right layout.
///
/// The `SwiftBridge` is held by the coordinator: nothing else retains it (the Kotlin side holds it
/// weakly-in-effect through an interface reference that does not own the Swift object's lifetime),
/// and a deallocated bridge would turn every picker and share sheet into a silent no-op.
struct ComposeRootView: UIViewControllerRepresentable {

    /// Also the navigation controller's gesture delegate — see `makeUIViewController`.
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var bridge: SwiftBridge?
        weak var navigation: UINavigationController?

        /// Refuse UIKit's interactive pop. The stack is one deep and stays that way, so there is
        /// nothing to pop, and letting the gesture begin drives UIKit into a pop it cannot complete
        /// and wedges the navigation controller.
        ///
        /// This is NOT the app's back gesture and never was — Compose owns that (see the type
        /// doc). Keeping the refusal is still worth it: UIKit's recogniser lives on this
        /// controller's view, a DESCENDANT of the `UITransitionView` that carries Compose's, so it
        /// sees the same touches first and a version of it that began would take the edge away
        /// from Compose.
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
        // What an extra window brings forward when it closes (`SceneWindows.activateMain`).
        SceneWindows.mainRoot = nav
        return nav
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}
}

/// How Kotlin reaches SwiftUI's `openWindow`, which exists only as an environment value inside a
/// view. `ExtraWindowOpener` (on the main scene's root) installs the two closures; the bridges
/// call them.
enum SceneWindows {
    /// The `WindowGroup` id of the extra-window scenes; its value is the window's claim.
    static let groupId = "extra"
    /// The main scene's `WindowGroup` id.
    static let mainGroupId = "main"

    @MainActor static var open: ((String) -> Void)?
    @MainActor static var openMain: (() -> Void)?

    /// The main window's root controller, set as it is built. Weak: the scene owns it.
    @MainActor static weak var mainRoot: UIViewController?

    /// Bring the EXISTING main window forward. False when there is none to bring (it was closed):
    /// `openWindow` would make a new main window, which is what `openMainWindow` then does.
    @MainActor @discardableResult
    static func activateMain() -> Bool {
        guard let session = mainRoot?.view.window?.windowScene?.session else { return false }
        UIApplication.shared.requestSceneSessionActivation(session, userActivity: nil, options: nil)
        return true
    }
}

/// Installs [SceneWindows]' closures from the environment of whatever view it modifies.
struct ExtraWindowOpener: ViewModifier {
    @Environment(\.openWindow) private var openWindow

    func body(content: Content) -> some View {
        content.onAppear {
            SceneWindows.open = { claim in openWindow(id: SceneWindows.groupId, value: claim) }
            SceneWindows.openMain = { openWindow(id: SceneWindows.mainGroupId) }
        }
    }
}

/// One extra window: the Kotlin `ExtraWindowViewController` for its claim, with its own bridge so
/// sheets present from THIS window and `closeWindow` closes it.
///
/// The claim is the scene's `WindowGroup` value, so iPadOS restores the window with it. Kotlin
/// hands it back whenever it changes (a tab opened in this window joins the claim), and the value
/// is updated to match. When the scene's content goes away — the user closed the window, or the
/// system discarded the scene — the claim is released and its views go back to the main window;
/// a restored scene claims them again.
struct ExtraWindowView: UIViewControllerRepresentable {
    @Binding var claim: String?

    final class Coordinator {
        var bridge: SwiftBridge?
        /// The claim the controller was built with — its host id is what gets released.
        var initialClaim: String?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> UIViewController {
        let bridge = SwiftBridge()
        bridge.isExtraWindow = true
        let initial = claim ?? ""
        let binding = $claim
        let controller = ExtraWindowViewControllerKt.ExtraWindowViewController(
            bridge: bridge,
            claim: initial,
            onClaim: { updated in
                DispatchQueue.main.async { binding.wrappedValue = updated }
            }
        )
        bridge.root = controller
        context.coordinator.bridge = bridge
        context.coordinator.initialClaim = initial
        return controller
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        if let claim = coordinator.initialClaim {
            ExtraWindowViewControllerKt.releaseExtraWindow(claim: claim)
        }
    }
}
