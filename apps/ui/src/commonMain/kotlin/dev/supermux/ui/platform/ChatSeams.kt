package dev.supermux.ui.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * The chat-shaped half of [Platform] (cluster D): clipboard images, file save/open, the mic, TTS
 * and transient notices.
 *
 * Same rule as the rest of [Platform] — a shared screen NEVER names `ClipboardManager`,
 * `MediaRecorder`, AWT's `Desktop`, or `Toast`; it reads `LocalPlatform` and calls one of these.
 * Every member is total: a host that cannot do a thing returns the "nothing happened" value
 * (`false`, `null`, an empty list) rather than throwing, so a caller that skipped the [Caps] check
 * degrades instead of crashing.
 */

/**
 * Images on the system clipboard, as upload-ready [PickedFile]s.
 *
 * Only images: pasting text is the text field's own business, and the composer's paste-to-attach
 * path is the only consumer. Desktop pastes a raster snapshot (a screenshot, a copy from an image
 * viewer) or a file-manager selection of image files; Android pastes the content URIs a keyboard or
 * gallery put on the clip.
 */
interface ClipboardAccess {
    /**
     * Decode whatever images are on the clipboard right now. Empty on a text-only clip, on any
     * failure, and on a host with `caps.clipboardImages == false`.
     *
     * **May be slow** — desktop re-encodes a raster snapshot to PNG (capped and downscaled first).
     * Call it from a coroutine, never inside an event handler that must return this frame.
     */
    suspend fun readImages(): List<PickedFile>

    /**
     * Cheap "is there probably an image?" probe, safe on the UI thread: it looks at clip
     * descriptors / AWT flavors only and never decodes. Used to decide whether to consume a
     * Ctrl/Cmd+V before paying for [readImages].
     */
    fun hasImage(): Boolean
}

/**
 * Handing bytes the app already holds to the rest of the machine: "Save as…" and "Open with…".
 *
 * Both take the bytes rather than a path, because the caller (a timeline attachment) has them in
 * memory and neither host can be given a path it may write: Android has no browsable file system
 * ([Caps.fileSystem]), only the SAF URI the user just picked.
 */
interface FileAccess {
    /**
     * Ask the user where to put [bytes] and write them there, returning WHERE it landed — or null
     * when the user cancelled, when the write failed, or on a host with `caps.saveAs == false` (a
     * headless desktop under CI, say — which must return null, not throw).
     *
     * It returns a [SavedFile] rather than a bare boolean so "save, then open what I just saved"
     * is one file, not two: handing the bytes to [openExternally] afterwards would stage a SECOND
     * copy in a temp directory and open that instead of the user's file.
     */
    suspend fun saveAs(name: String, mime: String, bytes: ByteArray): SavedFile?

    /**
     * Hand a file the user just saved (via [saveAs]) to the OS. True when a handler took it.
     *
     * Android returns false and does nothing: a SAF document is not ours to launch, and the
     * platform flow after "save" is the system Files app, not an implicit intent from us. Ask
     * [Caps.saveAs] — a host that cannot save cannot open a save either.
     */
    suspend fun openSaved(saved: SavedFile): Boolean

    /**
     * Write [bytes] somewhere the OS can reach and hand them to whatever opens [mime] — desktop's
     * `Desktop.open` → `xdg-open`/`open`/`start` chain, Android's `FileProvider` +
     * `ACTION_VIEW`, falling back to `ACTION_SEND` when nothing can view the type. False when no
     * handler took it.
     */
    suspend fun openExternally(name: String, mime: String, bytes: ByteArray): Boolean

    /** Best-effort content type for a file NAME (no I/O required); `application/octet-stream`
     *  when the host cannot tell. The pure part of the guess lives in `:shared`'s `MediaMime`. */
    fun probeMime(name: String): String
}

/**
 * Where a [FileAccess.saveAs] actually landed.
 *
 * [location] is deliberately opaque to shared code — an absolute path on desktop, a SAF document
 * URI on Android — and is only ever handed back to [FileAccess.openSaved]. [name] is the leaf the
 * user ended up with, which is the only part a screen may show.
 */
data class SavedFile(val name: String, val location: String)

/** One finished recording, ready to POST to the broker's transcribe endpoint. */
data class CapturedAudio(
    /** The encoded container bytes (Android AAC/m4a, desktop 16 kHz mono WAV). */
    val bytes: ByteArray,
    /** A filename with the right extension — the broker infers the codec from it. */
    val filename: String,
    /** The container's MIME type (`audio/mp4` / `audio/wav`). */
    val mime: String,
) {
    // Data class over a ByteArray: identity-based equals would silently break `assertEquals` in
    // tests, so compare the content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedAudio) return false
        return bytes.contentEquals(other.bytes) && filename == other.filename && mime == other.mime
    }

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + filename.hashCode()) * 31 + mime.hashCode()
}

/**
 * The mic, as the dictation state machine sees it: start, stop-and-get-bytes, cancel.
 *
 * Desktop's `MicCapture` seam verbatim (so `DesktopDictationController` keeps its fake-driven
 * tests), widened by the two things Android needs and desktop does not: a permission request and
 * an optional live on-device transcript.
 */
interface MicCapture {
    /** Opens the mic and starts capturing. False when the mic could not be opened (no line, in use,
     *  permission not granted) — the caller shows "microphone unavailable", not a crash. */
    fun start(): Boolean

    /** Stops capture and returns the encoded audio, or null when nothing was captured. */
    fun stop(): CapturedAudio?

    /** Discards an in-flight capture without producing bytes. */
    fun cancel()

    /**
     * Ask the OS for microphone permission, suspending until the user answers. True when recording
     * is allowed. Desktop returns true immediately — mic access there is granted to the process,
     * there is no per-app prompt.
     */
    suspend fun requestPermission(): Boolean

    /** Whether this machine has a usable capture backend at all. False → offer no mic button. */
    val available: Boolean

    /**
     * On-device streaming recognition, when the host has one — Android's `SpeechRecognizer` behind
     * `DevConfig.ENABLE_ONDEVICE_STT`. Null on a host with no on-device ASR (desktop), and the
     * dictation UI then simply records and POSTs to the broker instead of showing live text.
     */
    val liveTranscript: LiveTranscript?
}

/** A live, partial-emitting on-device transcript running alongside (or instead of) a recording. */
interface LiveTranscript {
    /** Start recognising, biased by [glossary] (project/agent names). False when unavailable. */
    fun start(glossary: List<String>): Boolean

    /** Stop and return the full accumulated transcript (may be blank). */
    fun stop(): String

    /** Abandon the session and discard the transcript. */
    fun cancel()

    /** The running transcript as it grows — committed segments plus the current partial. */
    val partial: StateFlow<String>
}

/**
 * Read-aloud output: the OS speech synthesiser, and playback of audio the broker streams back for
 * the non-platform (codex) voice. The `MessageTts` state machine keeps deciding WHAT to speak and
 * which message is speaking; this only makes noise.
 */
interface TtsEngine {
    /** Speak [text] with the OS synthesiser, suspending until it finishes, is [stop]ped or fails. */
    suspend fun speak(text: String)

    /** Silence anything in flight — both [speak] and [playAudioChunk] resume promptly. */
    fun stop()

    /** Play one encoded audio chunk (mp3, from the broker's `/speak` stream), suspending until it
     *  has finished playing so the caller can queue the next one gaplessly. */
    suspend fun playAudioChunk(bytes: ByteArray)

    /** Release the underlying engine. Called when the app is going away, not between messages. */
    fun shutdown()
}

/**
 * Transient, non-blocking "that didn't work" text — Android's `Toast`, desktop's snackbar. Never a
 * question and never dismissible-by-the-caller: anything the user must answer is a dialog.
 */
interface NoticeChannel {
    fun show(text: String)
}
