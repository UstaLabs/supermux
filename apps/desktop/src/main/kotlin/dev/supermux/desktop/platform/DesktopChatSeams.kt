// Desktop's implementations of the cluster-D chat seams on `Platform`: clipboard images, file
// save/open, the mic, read-aloud and transient notices. Everything AWT/`ProcessBuilder`/
// `javax.sound` shaped lives on this side of the seam so the shared chat screens never name it.
package dev.supermux.desktop.platform

import dev.supermux.chat.mimeForFileName
import dev.supermux.desktop.upload.FileChunkSource
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.FlowNotices
import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.FileAccess
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.platform.NoticeChannel
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.SavedFile
import dev.supermux.ui.platform.TtsEngine
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

// ── clipboard ────────────────────────────────────────────────────────────────

/**
 * AWT clipboard images, behind the shared [ClipboardAccess]. The caps/resize policy and the
 * app-owned paste cache are in `DesktopClipboard.kt` next door — this only maps their `File`s onto
 * streaming [PickedFile]s.
 *
 * [readImages] hops to [Dispatchers.IO]: a raster paste is a PNG encode of up to 2048², which is
 * far too slow for the frame the paste key arrived on. [hasImage] is the flavor-only probe and is
 * safe where it is called (the UI thread's key handler).
 */
internal class DesktopClipboardAccess : ClipboardAccess {
    override suspend fun readImages(): List<PickedFile> = withContext(Dispatchers.IO) {
        composerClipboardImageFiles().map { file ->
            PickedFile(file.name, probeMime(file), FileChunkSource(file))
        }
    }

    override fun hasImage(): Boolean = composerClipboardLikelyHasImage()
}

// ── files ────────────────────────────────────────────────────────────────────

/**
 * "Save as…" through the AWT save dialog, and "open with…" through [openLocalFile].
 *
 * Headless (CI without an X server) is the case worth naming: constructing a [java.awt.FileDialog]
 * there throws `HeadlessException`, so [saveAs] checks first and returns false — a cancelled save,
 * not a crash. That is also what `caps.saveAs` reports.
 */
internal class DesktopFileAccess : FileAccess {

    override suspend fun saveAs(name: String, mime: String, bytes: ByteArray): SavedFile? {
        if (GraphicsEnvironment.isHeadless()) return null
        // The dialog is modal on the EDT by AWT contract; the write is not, so it goes to IO.
        val target = withContext(Dispatchers.Swing) {
            runCatching { awtSaveFile(safeFileName(name)) }.getOrNull()
        } ?: return null
        val written = withContext(Dispatchers.IO) {
            runCatching { target.writeBytes(bytes) }.isSuccess
        }
        return if (written) SavedFile(target.name, target.absolutePath) else null
    }

    /** Opens the file the user actually chose — no second temp copy. */
    override suspend fun openSaved(saved: SavedFile): Boolean = withContext(Dispatchers.IO) {
        openLocalFile(File(saved.location))
    }

    override suspend fun openExternally(name: String, mime: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val file = writeToTempDir(safeFileName(name), bytes) ?: return@withContext false
            openLocalFile(file)
        }

    /**
     * The OS table first, the shared `:shared` table as fallback — the same order as Android's, so
     * both hosts answer identically for anything the OS knows and identically-by-table for the rest.
     * `probeContentType` works off the name here (the path need not exist).
     */
    override fun probeMime(name: String): String {
        val safe = safeFileName(name)
        val fromOs = runCatching { Files.probeContentType(Paths.get(safe)) }.getOrNull()
        return fromOs ?: mimeForFileName(safe) ?: "application/octet-stream"
    }

    /**
     * `file:///`-authority URI, deliberately: [java.io.File.toURI] yields `file:/path` (no
     * authority) and Compose Media Player's own local-file check looks for `"://"`, finds none,
     * treats the whole string as a bare path and rejects the clip as "File not found".
     * [java.nio.file.Path.toUri] yields `file:///path`, which it parses, and percent-encodes
     * spaces for free.
     */
    override suspend fun stageTemp(name: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            writeToTempDir(safeFileName(name), bytes)?.toPath()?.toUri()?.toString()
        }
}

/** Strip any directory part and refuse an empty name — the attachment name is broker-supplied. */
internal fun safeFileName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }

/**
 * Stage [bytes] under the JVM temp dir so the OS handler has a real path to open.
 *
 * `internal` (aliased as `stageAttachmentForTest`) because it is the only half of
 * [DesktopFileAccess.openExternally] a test can exercise — actually opening would fork a real
 * viewer on the developer's desktop.
 */
internal fun writeToTempDir(name: String, bytes: ByteArray): File? = runCatching {
    val dir = File(System.getProperty("java.io.tmpdir"), "supermux-attachments").apply { mkdirs() }
    File(dir, name).also { it.writeBytes(bytes) }
}.getOrNull()

/**
 * Open a local file with the OS default handler (viewer / player / folder).
 *
 * Fallback ORDER is the contract: `java.awt.Desktop.open` first (it is the only path that reports
 * failure), then the per-OS shell opener — `start` on Windows, `open` on macOS, `xdg-open`
 * everywhere else. Returns false when neither took it (a headless CI box with no `xdg-open`), so a
 * caller can show a notice instead of silently doing nothing.
 */
internal fun openLocalFile(file: File): Boolean {
    val viaDesktop = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
            true
        } else {
            false
        }
    }.getOrDefault(false)
    if (viaDesktop) return true
    val os = System.getProperty("os.name")?.lowercase(Locale.US).orEmpty()
    val cmd = when {
        os.contains("win") -> arrayOf("cmd", "/c", "start", "", file.absolutePath)
        os.contains("mac") || os.contains("darwin") -> arrayOf("open", file.absolutePath)
        else -> arrayOf("xdg-open", file.absolutePath)
    }
    return runCatching { ProcessBuilder(*cmd).inheritIO().start(); true }.getOrDefault(false)
}

// ── mic ──────────────────────────────────────────────────────────────────────

/**
 * The desktop mic: `javax.sound.sampled` capture at [DICTATION_FORMAT], encoded as WAV by
 * [WavEncoder] (the broker's whisper pipeline takes that directly).
 *
 * No permission model — the OS grants mic access to the process, so [requestPermission] is
 * immediately true; a mic that cannot actually be opened surfaces as `start() == false`, which the
 * dictation UI renders as "microphone unavailable". There is no on-device ASR on desktop, so
 * [liveTranscript] is null and the UI records-then-POSTs without live text.
 */
internal class DesktopMicCapture(
    private val recorder: RawMicCapture = MicRecorder(),
) : MicCapture {

    override fun start(): Boolean = recorder.start()

    override fun stop(): CapturedAudio? {
        val wav = recorder.stop() ?: return null
        return CapturedAudio(wav, "dictation-${System.currentTimeMillis()}.wav", "audio/wav")
    }

    override fun cancel() = recorder.cancel()

    override suspend fun requestPermission(): Boolean = true

    override val available: Boolean
        get() = runCatching {
            AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, DICTATION_FORMAT))
        }.getOrDefault(false)

    override val liveTranscript: LiveTranscript? get() = null
}

// ── tts ──────────────────────────────────────────────────────────────────────

/**
 * Desktop read-aloud: shell out to whatever speech/playback binary the machine has. The `which`
 * probe order IS the behaviour — `say` on macOS, PowerShell's `SpeechSynthesizer` on Windows, then
 * `spd-say`/`espeak-ng`/`espeak` on Linux; playback prefers `ffplay`, then `mpv`, then `afplay`.
 *
 * One child process at a time: [stop] kills it, which is what makes a second tap on a speaking
 * message stop it. A machine with none of the binaries silently speaks nothing — [speak] returns
 * immediately, and the caller's speaking marker clears on its own.
 */
internal class DesktopTtsEngine : TtsEngine {
    private val process = AtomicReference<Process?>(null)

    override suspend fun speak(text: String) {
        val cmd = speechCommand(text) ?: return
        runProcess(cmd)
    }

    override suspend fun playAudioChunk(bytes: ByteArray) {
        val file = runCatching {
            File.createTempFile("read-aloud-", ".mp3").also { it.deleteOnExit(); it.writeBytes(bytes) }
        }.getOrNull() ?: return
        try {
            val cmd = playCommand(file.absolutePath) ?: return
            runProcess(cmd)
        } finally {
            runCatching { file.delete() }
        }
    }

    override fun stop() {
        process.getAndSet(null)?.destroyForcibly()
    }

    /** Nothing process-global to release: [stop] already kills the only child. */
    override fun shutdown() = stop()

    private suspend fun runProcess(cmd: List<String>) = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process.set(p)
            p.waitFor()
        }
        process.compareAndSet(process.get(), null)
        Unit
    }
}

/**
 * THE desktop read-aloud engine, process-wide.
 *
 * Deliberately NOT one per [DesktopPlatform]: a platform is built per window root, and a
 * per-window engine would mean `stop()` in one window cannot kill the `say`/`ffplay` child another
 * window started — read-aloud would become unstoppable the moment a pane is detached.
 */
internal val SharedDesktopTts: DesktopTtsEngine by lazy { DesktopTtsEngine() }

/** OS speech command for [plain], or null when this machine has no synthesiser we know of. */
internal fun speechCommand(plain: String): List<String>? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> listOf("say", plain)
        os.contains("win") -> {
            val escaped = plain.replace("'", "''")
            listOf(
                "powershell",
                "-NoProfile",
                "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('$escaped')",
            )
        }
        which("spd-say") -> listOf("spd-say", "-e", plain)
        which("espeak-ng") -> listOf("espeak-ng", plain)
        which("espeak") -> listOf("espeak", plain)
        else -> null
    }
}

/** Audio playback command for [path], or null when no player is installed. */
internal fun playCommand(path: String): List<String>? = when {
    which("ffplay") -> listOf("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", path)
    which("mpv") -> listOf("mpv", "--no-video", "--really-quiet", path)
    which("afplay") -> listOf("afplay", path) // macOS
    else -> null
}

private fun which(bin: String): Boolean =
    runCatching { ProcessBuilder("which", bin).start().waitFor() == 0 }.getOrDefault(false)

// ── notices ──────────────────────────────────────────────────────────────────

/**
 * Desktop's transient notices.
 *
 * The bus itself is `:ui`'s [FlowNotices] — desktop and iOS both raise a shared screen's one-line
 * failure as a Compose snackbar (neither has an OS transient message), so the implementation is
 * shared and only the NAME stays here, where `DesktopPlatform` and its test already say it.
 */
typealias DesktopNotices = FlowNotices

/** [writeToTempDir] under the name the tests use, so its role there is obvious at the call site. */
internal fun stageAttachmentForTest(name: String, bytes: ByteArray): File? =
    writeToTempDir(safeFileName(name), bytes)
