// Android's implementations of the cluster-D chat seams on `Platform`: camera capture, clipboard
// images, SAF save / FileProvider open, the mic (record + optional on-device live transcript),
// read-aloud and Toasts. Everything `Intent`/`ClipboardManager`/`MediaRecorder`/`TextToSpeech`
// shaped lives on this side of the seam so the shared chat screens never name it.
package dev.supermux.android.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.supermux.android.DevConfig
import dev.supermux.android.chat.DictationEngine
import dev.supermux.android.chat.DictationStart
import dev.supermux.ui.chat.MessageTts
import dev.supermux.chat.mimeForFileName
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.FileAccess
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.platform.NoticeChannel
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.SavedFile
import dev.supermux.ui.platform.TtsEngine
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// ── clipboard ────────────────────────────────────────────────────────────────

/**
 * Images the user pasted, read off the primary clip.
 *
 * The clip carries content URIs, not bytes, so each one goes through the same
 * [pickedFileFromUri] staging the pickers use — identical name/size/streaming behaviour whether a
 * file arrived from the photo picker, the camera or a paste.
 *
 * [hasImage] reads the clip DESCRIPTION only (no `getPrimaryClip`, no resolver round-trip), which
 * is what makes it safe to call from a key handler on the UI thread.
 */
internal class AndroidClipboardAccess(private val context: Context) : ClipboardAccess {

    override suspend fun readImages(): List<PickedFile> = withContext(Dispatchers.IO) {
        val clip = runCatching { clipboardManager(context)?.primaryClip }.getOrNull()
            ?: return@withContext emptyList()
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
        imageEntries(uris) { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            .mapNotNull { pickedFileFromUri(context, it) }
    }

    override fun hasImage(): Boolean = runCatching {
        val desc = clipboardManager(context)?.primaryClipDescription ?: return false
        (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }
    }.getOrDefault(false)
}

private fun clipboardManager(context: Context): android.content.ClipboardManager? =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager

/**
 * Keep only the entries whose resolved content type is an image.
 *
 * Generic in the entry type on purpose: the production caller passes `Uri`s and a resolver lookup,
 * while a unit test (no device, so no real `Uri`) passes plain names and a map — the filtering rule
 * itself is what is worth pinning, and it is the same rule either way. Anything whose type cannot
 * be resolved is dropped: an unknown clip entry is far more likely to be text than an image.
 */
internal fun <T> imageEntries(entries: List<T>, mimeOf: (T) -> String?): List<T> =
    entries.filter { mimeOf(it)?.startsWith("image/") == true }

// ── files ────────────────────────────────────────────────────────────────────

/**
 * SAF "Save as…" and the `FileProvider` + `ACTION_VIEW` open chain.
 *
 * Android has no browsable file system (`caps.fileSystem == false`), so both halves go through a
 * URI the user or the provider grants: [saveAs] writes into the document the SAF `CreateDocument`
 * contract returns, [openExternally] stages the bytes in our own `cacheDir/attachments` and hands
 * out a `content://` URI with a read grant.
 */
internal class AndroidFileAccess(
    private val context: Context,
    private val saveHost: SaveHost,
) : FileAccess {

    override suspend fun saveAs(name: String, mime: String, bytes: ByteArray): SavedFile? {
        val safeName = safeFileName(name)
        val target = saveHost.save(mime.ifBlank { "application/octet-stream" }, safeName)
            ?: return null
        val written = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(target)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
        }
        return if (written) SavedFile(safeName, target.toString()) else null
    }

    /**
     * Not the Android flow: a SAF document belongs to the provider the user picked, and the
     * platform gesture after "save" is the system Files app — not an implicit intent from us. The
     * chip's own "open" affordance goes through [openExternally] instead, exactly as before.
     */
    override suspend fun openSaved(saved: SavedFile): Boolean = false

    /**
     * Write [bytes] to `cacheDir/attachments` and offer them to the system.
     *
     * The ORDER is the contract, unchanged from the timeline's old `openAttachment`: `ACTION_VIEW`
     * through a chooser first, then `ACTION_SEND` when nothing on the device can view the type (a
     * `.patch`, a broker log). False when neither chooser could start — the caller shows a notice.
     */
    override suspend fun openExternally(name: String, mime: String, bytes: ByteArray): Boolean {
        val safeName = safeFileName(name)
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                val file = File(dir, safeName)
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        } ?: return false
        val type = mime.ifBlank { null }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "*/*"
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val view = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, type); addFlags(flags) }
        val viewed = runCatching {
            context.startActivity(
                Intent.createChooser(view, "Open $safeName").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }.isSuccess
        if (viewed) return true
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(flags)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(send, "Share $safeName").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }.isSuccess
    }

    /** The OS table first (it knows far more extensions), the shared `:shared` table as fallback. */
    override fun probeMime(name: String): String {
        val ext = safeFileName(name).substringAfterLast('.', "").lowercase(Locale.US)
        val fromOs = if (ext.isEmpty()) null else runCatching {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }.getOrNull()
        return fromOs ?: mimeForFileName(name) ?: "application/octet-stream"
    }

    /**
     * Stage into `cacheDir/attachments` — the same directory [openExternally] uses — and hand back
     * a `file://` URI. The inline video player opens it in-process, so no provider grant is needed
     * (and `Uri.fromFile` keeps the extension the backend demuxes from).
     */
    override suspend fun stageTemp(name: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                val file = File(dir, safeFileName(name))
                file.writeBytes(bytes)
                Uri.fromFile(file).toString()
            }.getOrNull()
        }
}

/** Strip any directory part and refuse an empty name — attachment names are broker-supplied. */
internal fun safeFileName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }

// ── mic ──────────────────────────────────────────────────────────────────────

/**
 * The recorder half of [AndroidMicCapture], as an interface so the capture seam is unit-testable
 * without a device (`MediaRecorder` cannot be constructed off-device).
 * `dev.supermux.android.chat.VoiceRecorder` is the production implementation.
 */
interface AudioFileRecorder {
    fun start()

    /** The finished recording, or null when nothing was captured. */
    fun stop(): File?

    fun cancel()
}

/**
 * Android's mic: `MediaRecorder` capturing AAC into an MPEG-4 container (`.m4a`), which the broker
 * transcodes server-side — unlike desktop, which emits WAV the whisper pipeline eats directly.
 *
 * The RECORD_AUDIO prompt is the Android-only half of the seam: [requestPermission] short-circuits
 * when the grant is already held (no dialog on every dictation) and otherwise suspends on the
 * launcher [PermissionHost] owns.
 */
internal class AndroidMicCapture(
    private val recorder: AudioFileRecorder,
    override val available: Boolean,
    override val liveTranscript: LiveTranscript?,
    private val onRequestPermission: suspend () -> Boolean,
) : MicCapture {

    override fun start(): Boolean = runCatching { recorder.start(); true }.getOrDefault(false)

    override fun stop(): CapturedAudio? {
        val file = runCatching { recorder.stop() }.getOrNull() ?: return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        runCatching { file.delete() }
        if (bytes == null || bytes.isEmpty()) return null
        return CapturedAudio(bytes, file.name, ANDROID_DICTATION_MIME)
    }

    override fun cancel() {
        runCatching { recorder.cancel() }
    }

    override suspend fun requestPermission(): Boolean = onRequestPermission()
}

/** MPEG-4 audio, matching `VoiceRecorder`'s `OutputFormat.MPEG_4` + `AudioEncoder.AAC` `.m4a`. */
internal const val ANDROID_DICTATION_MIME = "audio/mp4"

/**
 * On-device live transcript over [DictationEngine]'s restart loop, exposed as a [StateFlow] the
 * shared dictation UI can collect. Only installed when
 * [dev.supermux.android.DevConfig.ENABLE_ONDEVICE_STT] is on — otherwise `Platform.mic
 * .liveTranscript` is null and the UI records-then-POSTs like desktop.
 */
internal class AndroidLiveTranscript(private val engine: DictationEngine) : LiveTranscript {
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    init {
        engine.onPartial = { _partial.value = it }
    }

    override fun start(glossary: List<String>): Boolean =
        runCatching { engine.start(glossary) }.getOrNull() == DictationStart.STARTED

    override suspend fun stop(): String {
        val text = runCatching { engine.stop() }.getOrDefault("")
        _partial.value = ""
        return text
    }

    override fun cancel() {
        runCatching { engine.cancel() }
        _partial.value = ""
    }
}

// ── tts ──────────────────────────────────────────────────────────────────────

/**
 * The slice of [android.speech.tts.TextToSpeech] the engine uses, as a seam so [AndroidTtsEngine]'s
 * sequencing is unit-testable without a device (a real `TextToSpeech` needs a live service).
 */
internal interface TtsBackend {
    /**
     * Speak [text]. [onDone] fires EXACTLY ONCE — on completion, on error, or immediately when the
     * engine could not be initialised. That totality is what lets [AndroidTtsEngine.speak] simply
     * await it without a timeout.
     */
    fun speak(text: String, utteranceId: String, onDone: () -> Unit)

    fun stop()

    fun shutdown()
}

/** Playback of the broker's `/speak` audio chunks, seamed for the same reason as [TtsBackend]. */
internal interface AudioChunkPlayer {
    suspend fun play(bytes: ByteArray)

    fun stop()
}

/**
 * Android read-aloud: the OS synthesiser for the "platform" voice, `MediaPlayer` for the mp3 chunks
 * the broker streams for the "codex" one.
 *
 * The only state here is a generation counter: [stop] bumps it and releases whatever is playing, so
 * an in-flight [speak] resumes promptly and a chunk that arrives late is dropped instead of talking
 * over the next message. `MessageTts` above it owns which message is speaking.
 */
internal class AndroidTtsEngine(
    private val backend: TtsBackend,
    private val player: AudioChunkPlayer,
) : TtsEngine {

    private val gen = AtomicInteger(0)

    /** The utterance currently being awaited, so [stop] can resume its caller. */
    private val inFlight = AtomicReference<CompletableDeferred<Unit>?>(null)

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        val g = gen.incrementAndGet()
        val done = CompletableDeferred<Unit>()
        inFlight.set(done)
        try {
            backend.speak(text, "msg-$g") { done.complete(Unit) }
            done.await()
        } finally {
            inFlight.compareAndSet(done, null)
        }
    }

    /** Ordering and abandonment are the caller's (`MessageTts.speakCodex` drains one queue and
     *  checks its own generation); [stop] has already released the player by the time this
     *  returns, so there is nothing left for this to decide. */
    override suspend fun playAudioChunk(bytes: ByteArray) = player.play(bytes)

    override fun stop() {
        gen.incrementAndGet()
        runCatching { backend.stop() }
        runCatching { player.stop() }
        inFlight.getAndSet(null)?.complete(Unit)
    }

    override fun shutdown() {
        stop()
        runCatching { backend.shutdown() }
    }
}

/**
 * THE Android read-aloud engine, process-wide.
 *
 * Deliberately NOT one per [dev.supermux.android.platform.AndroidPlatform]: the platform is built
 * from the ACTIVITY, so a rotation makes a new one — and a per-platform engine would leave the old
 * `TextToSpeech` connection talking with nothing able to stop it, leaking one service binding per
 * rotation. The old process-wide `object MessageTts` had this right; the seam keeps it.
 *
 * [create] is only consulted the first time. Keyed on nothing: an app has exactly one
 * `applicationContext`, and that is the context the engine is built from.
 */
internal object AndroidTts {
    private val instance = AtomicReference<TtsEngine?>(null)

    fun shared(create: () -> TtsEngine): TtsEngine {
        instance.get()?.let { return it }
        return synchronized(this) {
            instance.get() ?: create().also { instance.set(it) }
        }
    }

    /** Silence whatever is reading, release the engine, and forget it — the app is going away. */
    fun shutdown() {
        instance.getAndSet(null)?.let { engine ->
            // stop() first so the "this message is speaking" marker clears with the audio.
            runCatching { MessageTts.stop(engine) }
            runCatching { engine.shutdown() }
        }
    }

    /** Tests only: forget the instance so each case starts from a known state. */
    internal fun resetForTest() {
        instance.set(null)
    }
}

/**
 * The real [TtsBackend]: one lazily initialised [TextToSpeech] per process.
 *
 * Initialisation is asynchronous and can FAIL (no engine installed, no voice data). A failed init
 * is remembered ([ready] = -1) so every later speak short-circuits instead of re-creating the
 * engine on each tap, and [onDone] still fires — silence, not a hang.
 */
internal class PlatformTtsBackend(private val appContext: Context) : TtsBackend {
    private val engine = AtomicReference<TextToSpeech?>(null)
    private val ready = AtomicInteger(0) // 0=idle, 1=initing, 2=ready, -1=failed

    override fun speak(text: String, utteranceId: String, onDone: () -> Unit) {
        ensureEngine { tts ->
            if (tts == null) {
                onDone()
                return@ensureEngine
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = onDone()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onDone()
                override fun onError(utteranceId: String?, errorCode: Int) = onDone()
            })
            @Suppress("DEPRECATION")
            val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued != TextToSpeech.SUCCESS) onDone()
        }
    }

    override fun stop() {
        runCatching { engine.get()?.stop() }
    }

    override fun shutdown() {
        stop()
        runCatching { engine.getAndSet(null)?.shutdown() }
        ready.set(0)
    }

    private fun ensureEngine(then: (TextToSpeech?) -> Unit) {
        val existing = engine.get()
        if (existing != null && ready.get() == 2) return then(existing)
        if (ready.get() == -1) return then(null)
        ready.set(1)
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts.language = Locale.getDefault() }
                engine.set(tts)
                ready.set(2)
                then(tts)
            } else {
                ready.set(-1)
                then(null)
            }
        }
    }
}

/**
 * The real [AudioChunkPlayer]: one [MediaPlayer] at a time over a cache-file copy of the chunk.
 *
 * `MediaPlayer` has no byte-array data source, hence the file. It is deleted on completion, on
 * error and on cancellation, so an abandoned stream cannot leave the cache growing.
 */
internal class MediaPlayerChunkPlayer(private val appContext: Context) : AudioChunkPlayer {
    private var player: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun play(bytes: ByteArray) {
        // MediaPlayer's callbacks are delivered on the looper that created it.
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                var file: File? = null
                try {
                    player?.release()
                    val f = File(appContext.cacheDir, "read-aloud-${System.nanoTime()}.mp3")
                    file = f
                    f.writeBytes(bytes)
                    val mp = MediaPlayer()
                    player = mp
                    mp.setDataSource(f.absolutePath)
                    mp.setOnCompletionListener {
                        runCatching { f.delete() }
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        runCatching { f.delete() }
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    cont.invokeOnCancellation {
                        runCatching { mp.stop(); mp.release() }
                        runCatching { f.delete() }
                    }
                    mp.prepare()
                    mp.start()
                } catch (_: Exception) {
                    runCatching { file?.delete() }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    override fun stop() {
        val mp = player ?: return
        player = null
        // release() must happen on the creating looper.
        mainHandler.post { runCatching { mp.stop(); mp.release() } }
    }
}

// ── notices ──────────────────────────────────────────────────────────────────

/** A short [Toast]. Marshalled to the main looper: a notice can be raised from an IO coroutine. */
internal class AndroidNotices(private val context: Context) : NoticeChannel {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun show(text: String) {
        if (text.isBlank()) return
        val post = { runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }; Unit }
        if (Looper.myLooper() == Looper.getMainLooper()) post() else mainHandler.post(post)
    }
}

// ── permission helper ────────────────────────────────────────────────────────

/** Whether RECORD_AUDIO is already granted, so the mic never prompts twice. */
internal fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
