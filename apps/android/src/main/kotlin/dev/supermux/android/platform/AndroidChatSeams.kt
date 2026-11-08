// Android's implementations of the cluster-D chat seams on `Platform`: camera capture, clipboard
// images, SAF save / FileProvider open, the mic (record + optional on-device live transcript),
// read-aloud and Toasts. Everything `Intent`/`ClipboardManager`/`MediaRecorder`/`TextToSpeech`
// shaped lives on this side of the seam so the shared chat screens never name it.
package dev.supermux.android.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
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
import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.platform.MicTrouble
import dev.supermux.ui.platform.NoticeChannel
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.SavedFile
import dev.supermux.ui.platform.TtsEngine
import dev.supermux.work.DownloadedFile
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
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
import dev.supermux.fs.fileNameOf

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
 *
 * A finished download (#95) is handed over as the FILE it already is, so none of it is read into memory:
 * [saveFile] streams it into the document the user chose, [openFile] and [shareFile] offer a streamed copy of it
 * under its real name in `cacheDir/attachments` ([stageNamed]).
 */
/** A poster is a small picture: good enough at this quality, a few tens of KB. */
private const val POSTER_JPEG_QUALITY = 82

internal class AndroidFileAccess(
    private val context: Context,
    private val saveHost: SaveHost,
) : FileAccess {
    /** The first frame the picker already grabbed for a clip's chip ([mediaPreview]), as a JPEG. */
    override fun posterJpeg(preview: Any?): ByteArray? {
        val frame = preview as? android.graphics.Bitmap ?: return null
        val out = java.io.ByteArrayOutputStream()
        return if (frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, POSTER_JPEG_QUALITY, out)) out.toByteArray() else null
    }


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

    /** What a finished download offers on a phone: the system's Open and Share, and the SAF save dialog. */
    override val fileActions: Set<FileAction> = setOf(FileAction.Open, FileAction.Save, FileAction.Share)

    /**
     * Ask where to put the downloaded file (the SAF create-document dialog [saveAs] uses) and stream it into the
     * document the user chose ([saveStoredFile]). Null: cancelled, failed, or no file on disk to copy.
     */
    override suspend fun saveFile(file: DownloadedFile): SavedFile? = saveStoredFile<Uri>(
        file,
        chooseTarget = { mime, name -> saveHost.save(mime, name) },
        openTarget = { context.contentResolver.openOutputStream(it) },
    )

    /**
     * The downloaded file under its real name ([stageNamed]), offered to the system the way [openExternally] offers
     * bytes: `ACTION_VIEW` through a chooser, then `ACTION_SEND` when nothing on the device can view the type.
     */
    override suspend fun openFile(file: DownloadedFile): Boolean {
        val uri = stagedUri(file) ?: return false
        return viewOrSend(uri, safeFileName(file.name), file.mime)
    }

    /** The system share sheet for the downloaded file under its real name: `ACTION_SEND` with the stream and a read grant. */
    override suspend fun shareFile(file: DownloadedFile): Boolean {
        val uri = stagedUri(file) ?: return false
        return send(uri, safeFileName(file.name), typeOf(uri, file.mime))
    }

    /**
     * Write [bytes] to `cacheDir/attachments` and offer them to the system ([viewOrSend]).
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
        return viewOrSend(uri, safeName, mime)
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

    /**
     * [file] staged under its real name in `cacheDir/attachments` — the folder the `FileProvider`'s `cache-path` covers,
     * subfolders included — as a `content://` URI the system can read. Null: no file on disk to stage, or the provider
     * would not name it.
     */
    private suspend fun stagedUri(file: DownloadedFile): Uri? = withContext(Dispatchers.IO) {
        val staged = stageNamed(file, File(context.cacheDir, "attachments")) ?: return@withContext null
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged) }.getOrNull()
    }

    /** What to offer [uri] as: the content type the caller knows, else what the provider makes of its name, else anything. */
    private fun typeOf(uri: Uri, mime: String): String =
        mime.ifBlank { null }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "*/*"

    /**
     * Offer [uri] to the system. The ORDER is the contract, unchanged from the timeline's old `openAttachment`:
     * `ACTION_VIEW` through a chooser first, then `ACTION_SEND` when nothing on the device can view the type (a
     * `.patch`, a broker log). False when neither chooser could start — the caller shows a notice.
     */
    private fun viewOrSend(uri: Uri, name: String, mime: String): Boolean {
        val type = typeOf(uri, mime)
        val view = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, type); addFlags(READ_IN_A_NEW_TASK) }
        return startChooser(view, "Open $name") || send(uri, name, type)
    }

    /** `ACTION_SEND` of [uri] through a chooser, with a read grant for whichever app is picked. */
    private fun send(uri: Uri, name: String, type: String): Boolean {
        val share = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(READ_IN_A_NEW_TASK)
        }
        return startChooser(share, "Share $name")
    }

    private fun startChooser(target: Intent, title: String): Boolean = runCatching {
        context.startActivity(Intent.createChooser(target, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }.isSuccess
}

/** What both of the intents that offer a file carry: the picked app may read the URI, and it starts in a task of its own. */
private const val READ_IN_A_NEW_TASK = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

/** A copy moves through a buffer this size, so a download of any size costs this much memory and no more. */
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * A finished download under its REAL name in [attachments] (`cacheDir/attachments`, the folder the `FileProvider`
 * shares): `<attachments>/<unique>/<name>`. The media store keeps the file under an id of its own, which would open as
 * "3f9c4d….bin"; `<unique>`, 16 hex digits of a hash of where the file is stored, keeps two different `report.pdf`s from
 * overwriting each other.
 *
 * Nothing is read into memory: the file is streamed through a [COPY_BUFFER_BYTES] buffer. What is offered is a copy,
 * never the stored file: a viewer that saves in place would otherwise rewrite the media store's entry (the cache never
 * revalidates a file id). The copy is made beside its place and moved there when whole, so a second Open that comes
 * while it is still being made never meets half a file. A file already staged at the stored one's size is used as it
 * is, so a second Open of a big file does not copy it again; another size means that path was fetched again since, and
 * the old one is replaced. Null: no file on disk to stage (a browser handle, or one cleared since), or staging failed.
 *
 * Takes the folder because `cacheDir` needs a `Context`, which a JVM test cannot make.
 */
internal fun stageNamed(file: DownloadedFile, attachments: File): File? {
    val stored = file.path ?: return null
    return runCatching {
        val source = File(stored)
        if (!source.isFile) return@runCatching null
        val folder = File(attachments, stagingFolderName(stored))
        val staged = File(folder, safeFileName(file.name))
        if (staged.isFile && staged.length() == source.length()) return@runCatching staged
        check(folder.isDirectory || folder.mkdirs()) { "no folder to stage into" }
        val part = java.nio.file.Files.createTempFile(folder.toPath(), "staging-", ".part").toFile()
        try {
            source.inputStream().use { input ->
                part.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
            // A rename replaces what is there, in one step: nobody sees the old file gone and the new one not yet there.
            check(part.renameTo(staged)) { "could not move the copy into place" }
        } finally {
            part.delete()
        }
        staged
    }.getOrNull()
}

/** The folder a download is staged in: 16 hex digits of a hash of where it is stored — the same for one file, apart for any two. */
private fun stagingFolderName(stored: String): String =
    MessageDigest.getInstance("SHA-256").digest(stored.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

/**
 * Where the user chose to save a finished download ([chooseTarget]: the SAF create-document dialog), with the file
 * streamed into the stream [openTarget] opens on it — never through memory. The save and the stream are parameters
 * because only a device has either: [Target] is a `Uri` in the app and anything a test likes.
 *
 * [chooseTarget] is not asked at all for a download with no file on disk (a browser handle): nothing to copy, nothing to
 * ask for. Null: no file, a cancelled dialog, a document the provider will not open, or a copy that failed.
 */
internal suspend fun <Target : Any> saveStoredFile(
    file: DownloadedFile,
    chooseTarget: suspend (mime: String, name: String) -> Target?,
    openTarget: (Target) -> OutputStream?,
): SavedFile? {
    val stored = file.path ?: return null
    val name = safeFileName(file.name)
    val target = chooseTarget(file.mime.ifBlank { "application/octet-stream" }, name) ?: return null
    val copied = withContext(Dispatchers.IO) {
        runCatching {
            File(stored).inputStream().use { input ->
                val output = openTarget(target) ?: return@use false
                output.use { input.copyTo(it, COPY_BUFFER_BYTES) }
                true
            }
        }.getOrDefault(false)
    }
    return if (copied) SavedFile(name, target.toString()) else null
}

/** Strip any directory part and refuse an empty name — attachment names are broker-supplied. */
internal fun safeFileName(name: String): String =
    fileNameOf(name).ifBlank { "file" }

// ── mic ──────────────────────────────────────────────────────────────────────

/**
 * The recorder half of [AndroidMicCapture], as an interface so the capture seam is unit-testable
 * without a device (`AudioRecord` cannot be constructed off-device).
 * `dev.supermux.android.chat.VoiceRecorder` is the production implementation.
 */
interface PcmRecorder {
    fun start()

    /** The finished recording as WAV bytes, or null when nothing was captured. */
    fun stop(): ByteArray?

    /** The recording so far as WAV bytes, without stopping; null when nothing is captured yet. */
    fun snapshot(): ByteArray?

    /** The raw PCM after the first [byteOffset] bytes, without stopping; null when not capturing. */
    fun pcmSince(byteOffset: Long): ByteArray? = null

    fun cancel()

    /** Loudness of the latest frames, 0..1. */
    val level: StateFlow<Float>?
}

/**
 * Android's mic: `AudioRecord` PCM framed as WAV (see `VoiceRecorder` for why not `.m4a`), plus the
 * level and mid-recording snapshot the live dictation passes need.
 *
 * The RECORD_AUDIO prompt is the Android-only half of the seam: [requestPermission] short-circuits
 * when the grant is already held (no dialog on every dictation) and otherwise suspends on the
 * launcher [PermissionHost] owns.
 */
internal class AndroidMicCapture(
    private val recorder: PcmRecorder,
    override val available: Boolean,
    override val liveTranscript: LiveTranscript?,
    private val onRequestPermission: suspend () -> Boolean,
    /** Why it won't open, asked of the device ([androidMicTrouble]). */
    private val onTrouble: () -> MicTrouble = { MicTrouble.DIDNT_OPEN },
) : MicCapture {

    override suspend fun trouble(): MicTrouble = onTrouble()

    override fun start(): Boolean = runCatching { recorder.start(); true }.getOrDefault(false)

    override fun stop(): CapturedAudio? {
        val wav = runCatching { recorder.stop() }.getOrNull() ?: return null
        if (wav.isEmpty()) return null
        return CapturedAudio(wav, "voice-${System.currentTimeMillis()}.wav", ANDROID_DICTATION_MIME)
    }

    override suspend fun snapshot(): CapturedAudio? {
        val wav = runCatching { recorder.snapshot() }.getOrNull() ?: return null
        if (wav.isEmpty()) return null
        return CapturedAudio(wav, "voice-live.wav", ANDROID_DICTATION_MIME)
    }

    override val level: StateFlow<Float>? get() = recorder.level

    override fun pcmSince(byteOffset: Long): ByteArray? = runCatching { recorder.pcmSince(byteOffset) }.getOrNull()

    override fun cancel() {
        runCatching { recorder.cancel() }
    }

    override suspend fun requestPermission(): Boolean = onRequestPermission()
}

/** 16 kHz mono 16-bit PCM in a WAV container, matching `VoiceRecorder`. */
internal const val ANDROID_DICTATION_MIME = "audio/wav"

/**
 * On-device live transcript over [DictationEngine]'s restart loop, exposed as a [StateFlow] the
 * shared dictation UI can collect. Used when this device's dictation mode is "On this device"
 * (Settings → Voice); otherwise the UI records and POSTs to the broker like desktop.
 */
internal class AndroidLiveTranscript(private val engine: DictationEngine) : LiveTranscript {
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    init {
        engine.onPartial = { _partial.value = it }
        engine.onRms = { _level.value = recognizerLevel(it) }
    }

    override fun start(glossary: List<String>): Boolean =
        runCatching { engine.start(glossary) }.getOrNull() == DictationStart.STARTED

    override suspend fun stop(): String {
        val text = runCatching { engine.stop() }.getOrDefault("")
        _partial.value = ""
        _level.value = 0f
        return text
    }

    override fun cancel() {
        runCatching { engine.cancel() }
        _partial.value = ""
        _level.value = 0f
    }
}

/** `SpeechRecognizer`'s meter (about −2 dB silent to 10 dB loud) as normalised loudness 0..1. */
internal fun recognizerLevel(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

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

    /** Play a progressive MP3 over http, starting on its first bytes. */
    suspend fun playUrl(url: String)

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

    override val canPlayUrl: Boolean get() = true

    override suspend fun playUrl(url: String) = player.playUrl(url)

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
    @Volatile private var urlDone: (() -> Unit)? = null
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

    /**
     * `MediaPlayer` streams an http source itself and starts once it has buffered the first
     * frames. `prepareAsync`, not `prepare`: preparing a network source blocks until data arrives,
     * and this runs on the main looper (where the callbacks are delivered).
     */
    override suspend fun playUrl(url: String) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                try {
                    player?.release()
                    val mp = MediaPlayer()
                    player = mp
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    // A released MediaPlayer fires nothing, so [stop] resumes the caller itself.
                    val finish = { if (cont.isActive) cont.resume(Unit) }
                    urlDone = finish
                    mp.setDataSource(url)
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnCompletionListener { finish() }
                    mp.setOnErrorListener { _, _, _ ->
                        finish()
                        true
                    }
                    cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
                    mp.prepareAsync()
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    override fun stop() {
        urlDone?.let { done -> urlDone = null; mainHandler.post { done() } }
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

/** Why this device's mic won't open ([androidMicTrouble]): its microphone feature, its audio inputs, RECORD_AUDIO. */
internal fun androidMicTrouble(context: Context): MicTrouble = androidMicTrouble(
    allowed = hasRecordAudioPermission(context),
    hasMicFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
    inputs = runCatching {
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.map { it.type }
    }.getOrNull(),
)
