package dev.supermux.ios

import platform.UIKit.UIViewController

/**
 * Everything only Swift can do, as ONE callback-style interface Swift implements.
 *
 * The shape is load-bearing, not incidental:
 *
 *  • NO `suspend` members and NO `@Composable` members. Kotlin/Native can expose a suspend function
 *    to Swift, but it cannot let Swift IMPLEMENT one — an `@objc` protocol has no continuation to
 *    resume. Every asynchronous seam here is therefore a plain function that takes a completion
 *    lambda, and [IosPlatform] adapts it back to the suspend `Platform` API with
 *    `suspendCancellableCoroutine`. Swift stays in callback land, where UIKit already lives.
 *  • ONE interface rather than five. Swift has to hand exactly one object to
 *    [MainViewController], and every capability here is owned by the same app delegate anyway
 *    (APNs registration, the audio session, the root view controller).
 *  • Bytes cross as `ByteArray`, never as a stream. These are recorded clips and small files; the
 *    resumable UPLOAD path does not come through here at all (it stays Kotlin, over Ktor).
 *
 * Every member is a no-op in [NoopIosBridge], which is what H1 links: the framework has to build
 * and boot before any of this is wired. H2 implements presentation and pickers, H3 the rest.
 */
interface IosBridge {

    /**
     * The view controller UIKit sheets must be presented from — the pickers, the share sheet, the
     * camera. Null before the window's root is set (during launch), in which case the caller
     * reports "cancelled" rather than crashing.
     *
     * A Compose surface has no view controller of its own to present from, which is the whole
     * reason this is a bridge member and not something [IosPlatform] can work out for itself.
     */
    fun rootViewController(): UIViewController?

    // ── Camera / QR (H3) ────────────────────────────────────────────────────────────────────

    /**
     * Present the AVFoundation QR scanner. [onResult] gets the decoded text, or null if the user
     * dismissed it or the camera is unavailable. Exactly one call to [onResult], always on the main
     * thread.
     */
    fun scanQr(onResult: (String?) -> Unit)

    // ── Files (H2) ──────────────────────────────────────────────────────────────────────────

    /**
     * Present the document / photo picker. [kind] is `"any"`, `"images"` or `"media"` (the wire
     * form of `PickKind` — an enum crossing the boundary would make the Swift side depend on
     * Kotlin's ordinal order). [onResult] gets zero or more files; an empty list means cancelled.
     */
    fun pickFiles(kind: String, onResult: (List<IosPickedFile>) -> Unit)

    /** Present the camera in still-image mode. [onResult] gets the capture, or null if cancelled. */
    fun captureImage(onResult: (IosPickedFile?) -> Unit)

    /** Present the camera in video mode. Same contract as [captureImage]. */
    fun captureVideo(onResult: (IosPickedFile?) -> Unit)

    /**
     * Present the share sheet for [bytes] as a SAVE. [onResult] gets a human-readable destination
     * (shown in the "Saved to …" notice) or null if the user dismissed it.
     *
     * The share sheet and not `UIDocumentPickerViewController(forExporting:)`: iOS has no separate
     * save dialog, and the picker would offer ONLY the file system, hiding every other app that
     * can take the file. iOS never reports where a file actually landed, so the destination is
     * derived from the activity the user chose.
     */
    fun saveAs(name: String, mime: String, bytes: ByteArray, onResult: (String?) -> Unit)

    /**
     * Present the share sheet for [bytes] as an OPEN ("Open with…"). [onResult] is true if the
     * sheet was PRESENTED at all — iOS does not report what the user did with it afterwards, and
     * a plain dismissal must not read as a failure to open.
     */
    fun openExternally(name: String, mime: String, bytes: ByteArray, onResult: (Boolean) -> Unit)

    // ── Microphone + dictation (H3) ─────────────────────────────────────────────────────────

    /**
     * Whether the app may use the microphone — gates the mic button being offered at all.
     *
     * True while the record permission is granted OR still undetermined (asking is the mic
     * button's own first step); false only once the user has actually refused, which is the one
     * state where offering the button would be offering something that cannot work.
     *
     * Read during COMPOSITION, so it must be cheap and must never present anything.
     */
    fun micAvailable(): Boolean

    /** Ask for the microphone (and speech-recognition) permission. [onResult] gets the verdict. */
    fun requestMicPermission(onResult: (Boolean) -> Unit)

    /**
     * Begin recording a clip. False if the audio session could not be taken.
     *
     * Synchronous, because the shared `MicCapture.start()` is: the recorder has to be running by
     * the time the composer redraws itself as a RecordingBar, or the first word is lost. Every
     * step is synchronous on the Swift side too (the audio session, then `AVAudioRecorder.record()`) —
     * the permission prompt, which is not, has already been answered by [requestMicPermission].
     */
    fun startRecording(): Boolean

    /** Stop recording and hand back the clip, or null if nothing usable was captured. */
    fun stopRecording(): IosCapturedAudio?

    /** Abandon the recording and release the audio session; nothing is handed back. */
    fun cancelRecording()

    /**
     * Start live on-device transcription (`SpeechAnalyzer` on iOS 26, `SFSpeechRecognizer`
     * before it). [glossary] is the contextual vocabulary (session and project names — the words a
     * general model gets wrong); [onPartial] fires repeatedly with the running text.
     *
     * False when recognition is unavailable, and the composer then falls back to recording a clip
     * for the broker to transcribe. The check is necessarily SHALLOW — the honest answer needs a
     * locale plan and possibly a model download, and this returns on the frame the user tapped —
     * so it reports what can be known synchronously (permission not refused, a recogniser exists).
     * A deeper failure surfaces as an empty [stopTranscript], which the shared state machine
     * already renders as "Didn't catch that".
     */
    fun startTranscript(glossary: List<String>, onPartial: (String) -> Unit): Boolean

    /**
     * Finish transcription; [onResult] gets the final text (possibly empty).
     *
     * A callback and not a return value: iOS 26's `SpeechAnalyzer` holds the tail of the last
     * sentence as "volatile" and only finalises it when the analyzer is drained, so answering
     * synchronously could only mean answering with the last partial.
     */
    fun stopTranscript(onResult: (String) -> Unit)

    /** Abandon transcription, discarding whatever was heard. */
    fun cancelTranscript()

    // ── Speech output (H3) ──────────────────────────────────────────────────────────────────

    /** Speak [text] with `AVSpeechSynthesizer`. [onDone] fires when it finishes OR is stopped. */
    fun speak(text: String, onDone: () -> Unit)

    /** Play one chunk of broker-synthesised audio. [onDone] fires when the chunk has played out. */
    fun playAudioChunk(bytes: ByteArray, onDone: () -> Unit)

    /** Stop whatever is being spoken or played right now. Never fails. */
    fun stopSpeaking()

    /** Release the synthesiser and the audio session — the app is going away. */
    fun shutdownSpeech()

    // ── Push (H3) ───────────────────────────────────────────────────────────────────────────

    /**
     * Ask for the notification permission, register with APNs, and hand the resulting token to the
     * relay and the broker — the whole `PushManager.registerIfPaired()` sequence. A no-op when the
     * device is not paired. Idempotent: iOS answers a repeated authorisation request from the
     * decision already on file, and re-registering an unchanged APNs token is free.
     *
     * Nothing comes BACK. The plan sketched this as `registerForPush(onToken:)`, but the token is
     * useless to Kotlin: it arrives asynchronously at the app delegate, and everything that
     * happens next — resolving the broker's relay URL, POSTing the token to the relay, registering
     * the push keypair with the broker — is `PushManager`'s existing four-step flow, sharing the
     * Keychain'd keypair with the notification-service extension. Handing the token over would
     * invite a SECOND registration path in Kotlin that raced the Swift one and registered the same
     * device twice.
     */
    fun registerPushIfPaired()

    /** Withdraw already-delivered notifications for [sessionId] (the user just opened it). */
    fun cancelNotificationsFor(sessionId: String)

    // ── Terminal (H5) ───────────────────────────────────────────────────────────────────────

    /**
     * The SwiftTerm view vendor, or null on a host that has none (see [NoopIosBridge]).
     *
     * Nullable rather than absent so `IosPlatform.terminalView()` can fall back to
     * `UnavailableTerminalViewFactory` — the shared "this client has no terminal" hint — instead of
     * a factory that claims to be available and then cannot build anything.
     *
     * It is a member of THIS interface rather than a second bridge object because Swift hands
     * Compose exactly one bridge; [IosTerminalVendor] is the per-terminal factory hanging off it,
     * and [IosTerminalHandle] the per-terminal instance.
     */
    fun terminalVendor(): IosTerminalVendor?

    // ── System (H2) ─────────────────────────────────────────────────────────────────────────

    /** Open [url] with `UIApplication.openURL`. Fire-and-forget. */
    fun openUrl(url: String)

    // ── Windows (iPad) ──────────────────────────────────────────────────────────────────────

    /**
     * Whether this device can show a pane in a window of its own: an iPad with multiple scenes.
     * An iPhone has exactly one window per app, so false there.
     */
    fun supportsExtraWindows(): Boolean

    /** Open a scene of the `extra` WindowGroup for [claim] (`PersistedWindowHost.encode()`). */
    fun openExtraWindow(claim: String)

    /** Close THIS bridge's scene — an extra window whose claim is gone. No-op on the main one. */
    fun closeWindow()

    /** Bring the main window back (an extra window's placeholder, once the main one closed). */
    fun openMainWindow()
}

/**
 * A file Swift picked or captured, as bytes.
 *
 * `Platform.PickedFile` carries a streaming `ChunkSource` so a 2 GB video never lands in the heap;
 * [IosPlatform] wraps these bytes in one. It is bytes rather than a stream HERE because a
 * `UIDocumentPicker` hands back a security-scoped URL whose access must be stopped before the
 * callback returns — reading it lazily from Kotlin later would read a revoked URL.
 */
data class IosPickedFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IosPickedFile) return false
        return name == other.name && mime == other.mime && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = (name.hashCode() * 31 + mime.hashCode()) * 31 + bytes.contentHashCode()
}

/**
 * One finished voice clip, as Swift's `AudioRecorder` hands it over.
 *
 * The shared `CapturedAudio` is the same three fields; this exists only so the boundary type is
 * `:ios`'s own and Swift is not constructing `:ui` values. Bytes rather than a file URL for the
 * same reason [IosPickedFile] carries bytes: the recording lives in a temp file the recorder
 * deletes as it cleans up, so a URL handed to Kotlin would be a URL to nothing.
 */
data class IosCapturedAudio(
    val bytes: ByteArray,
    val filename: String,
    val mime: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IosCapturedAudio) return false
        return bytes.contentEquals(other.bytes) && filename == other.filename && mime == other.mime
    }

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + filename.hashCode()) * 31 + mime.hashCode()
}

/**
 * The bridge that does nothing — what [MainViewController] links until Swift supplies a real one.
 *
 * Every callback fires immediately with the "not available / cancelled" answer rather than never
 * firing, so a caller awaiting one is never left suspended forever. That property is what makes
 * this safe as a default: a screen that asks for a picker gets an empty result and carries on.
 */
object NoopIosBridge : IosBridge {
    override fun rootViewController(): UIViewController? = null

    override fun scanQr(onResult: (String?) -> Unit) = onResult(null)

    override fun pickFiles(kind: String, onResult: (List<IosPickedFile>) -> Unit) = onResult(emptyList())
    override fun captureImage(onResult: (IosPickedFile?) -> Unit) = onResult(null)
    override fun captureVideo(onResult: (IosPickedFile?) -> Unit) = onResult(null)
    override fun saveAs(name: String, mime: String, bytes: ByteArray, onResult: (String?) -> Unit) =
        onResult(null)

    override fun openExternally(name: String, mime: String, bytes: ByteArray, onResult: (Boolean) -> Unit) =
        onResult(false)

    override fun micAvailable(): Boolean = false
    override fun requestMicPermission(onResult: (Boolean) -> Unit) = onResult(false)
    override fun startRecording(): Boolean = false
    override fun stopRecording(): IosCapturedAudio? = null
    override fun cancelRecording() = Unit
    override fun startTranscript(glossary: List<String>, onPartial: (String) -> Unit): Boolean = false
    override fun stopTranscript(onResult: (String) -> Unit) = onResult("")
    override fun cancelTranscript() = Unit

    override fun speak(text: String, onDone: () -> Unit) = onDone()
    override fun playAudioChunk(bytes: ByteArray, onDone: () -> Unit) = onDone()
    override fun stopSpeaking() = Unit
    override fun shutdownSpeech() = Unit

    override fun registerPushIfPaired() = Unit
    override fun cancelNotificationsFor(sessionId: String) = Unit

    override fun terminalVendor(): IosTerminalVendor? = null

    override fun openUrl(url: String) = Unit

    override fun supportsExtraWindows(): Boolean = false
    override fun openExtraWindow(claim: String) = Unit
    override fun closeWindow() = Unit
    override fun openMainWindow() = Unit
}
