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
import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.platform.MicTrouble
import dev.supermux.ui.platform.NoticeChannel
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.SavedFile
import dev.supermux.ui.platform.TtsEngine
import dev.supermux.work.DownloadedFile
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import dev.supermux.fs.fileNameOf

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
 *
 * A finished download (#95) is handed over as the FILE it already is — [saveFile] copies it, [openFile] opens it under
 * its real name — so none of it is read into memory.
 */
internal class DesktopFileAccess : FileAccess {

    override suspend fun saveAs(name: String, mime: String, bytes: ByteArray): SavedFile? {
        val target = askWhereToSave(name) ?: return null
        // The dialog was modal on the EDT; the write is not, so it goes to IO.
        val written = withContext(Dispatchers.IO) {
            runCatching { target.writeBytes(bytes) }.isSuccess
        }
        return if (written) SavedFile(target.name, target.absolutePath) else null
    }

    /** The dialog and the OS handler are all this host has: no share sheet. */
    override val fileActions: Set<FileAction> = setOf(FileAction.Open, FileAction.Save)

    /**
     * The downloaded file itself, copied to where the user chose with a streamed `Files.copy` — a download can be
     * gigabytes. Null: nothing on disk to copy (a browser handle), cancelled, headless, or the copy failed.
     */
    override suspend fun saveFile(file: DownloadedFile): SavedFile? {
        // Before the dialog: with nothing to copy, asking where to put it would be asking for nothing.
        val stored = file.path ?: return null
        val target = askWhereToSave(file.name) ?: return null
        val copied = withContext(Dispatchers.IO) {
            runCatching {
                Files.copy(Paths.get(stored), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
        }
        return if (copied) SavedFile(target.name, target.absolutePath) else null
    }

    /** The downloaded file under its real name ([stageNamed]), handed to the OS handler. */
    override suspend fun openFile(file: DownloadedFile): Boolean = withContext(Dispatchers.IO) {
        val staged = stageNamed(file) ?: return@withContext false
        openLocalFile(staged)
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

    /** Where the user wants [name] saved; null when they cancelled, or there is no display to ask on (headless). */
    private suspend fun askWhereToSave(name: String): File? {
        if (GraphicsEnvironment.isHeadless()) return null
        // The dialog is modal on the EDT by AWT contract.
        return withContext(Dispatchers.Swing) {
            runCatching { awtSaveFile(safeFileName(name)) }.getOrNull()
        }
    }
}

/**
 * The attachment's name (broker-supplied) as one file this OS can store: no directory part, never empty, `.` or `..`.
 * On Windows also the broker's own rules (`paths.safeFolderName`, the shared vectors in
 * packages/platform/tests/windows-names.json): no reserved character (`:` would write an NTFS alternate stream), no
 * trailing dot or space (Windows drops them), no device name (`CON`, `NUL`, `COM1`...).
 */
internal fun safeFileName(name: String, windows: Boolean = isWindowsOs()): String {
    val last = fileNameOf(name)
    val clean = if (windows) last.replace(WINDOWS_RESERVED, "-").trimEnd('.', ' ') else last
    return when {
        clean.isBlank() || clean == "." || clean == ".." -> "file"
        windows && WINDOWS_DEVICE.matches(clean) -> "_$clean"
        else -> clean
    }
}

private val WINDOWS_RESERVED = Regex("[<>:\"|?*\\u0000-\\u001f]")
private val WINDOWS_DEVICE = Regex("(?i)(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(\\..*)?")

/**
 * Stage [bytes] in this process's private folder under the JVM temp dir, so the OS handler has a real path to open.
 *
 * `internal` (aliased as `stageAttachmentForTest`) because it is the only half of
 * [DesktopFileAccess.openExternally] a test can exercise — actually opening would fork a real
 * viewer on the developer's desktop.
 */
internal fun writeToTempDir(name: String, bytes: ByteArray): File? = runCatching {
    File(attachmentsDir, name).also { it.writeBytes(bytes) }
}.getOrNull()

/**
 * A finished download under its REAL name, where an OS handler can be given a path to it: `<staging>/<unique>/<name>`.
 * The media store keeps the file under an id of its own, which would open as "3f9c4d….bin"; `<unique>`, a hash of where
 * the file is stored, keeps two different `report.pdf`s from overwriting each other.
 *
 * What the handler gets is a COPY, never the stored file or a link to it: a viewer that saves in place would otherwise
 * rewrite the media store's entry (the cache never revalidates a file id), and a link would keep the bytes alive after
 * Storage › Clear. Nothing is read into memory: [Files.copy] streams the file, and the OS copies or clones it itself where
 * it can (APFS). A file already staged at the stored one's size is used as it is, so a second Open of a big file does not
 * copy it again; another size means that path was fetched again since, and the old one is replaced. Null: no file on disk
 * to stage (a browser handle), or staging failed.
 *
 * `internal` because it is the half of [DesktopFileAccess.openFile] a test can run — actually opening would fork a real
 * viewer on the developer's desktop.
 */
internal fun stageNamed(file: DownloadedFile): File? {
    val stored = file.path ?: return null
    return runCatching {
        val source = Paths.get(stored)
        val staged = File(File(attachmentsDir, stagingFolderName(stored)), safeFileName(file.name))
        if (staged.isFile && staged.length() == Files.size(source)) return@runCatching staged
        val target = staged.toPath()
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        staged
    }.getOrNull()
}

/** The folder a download is staged in: 16 hex digits of a hash of where it is stored — the same for one file, apart for any two. */
private fun stagingFolderName(stored: String): String =
    MessageDigest.getInstance("SHA-256").digest(stored.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

/**
 * This process's staging folder, made on first use. [java.nio.file.Files.createTempDirectory] gives it a name no one
 * can guess and, on POSIX, owner-only permissions. A fixed `<tmp>/supermux-attachments` was shared on Linux, where /tmp
 * belongs to every user: another one could make it first, read what was staged there, or plant a link in it.
 */
private val attachmentsDir: File by lazy { java.nio.file.Files.createTempDirectory("supermux-attachments-").toFile() }

/**
 * Open a local file with the OS default handler (viewer / player / folder).
 *
 * Fallback ORDER is the contract: `java.awt.Desktop.open` first (it is the only path that reports
 * failure), then [openCommand] — ShellExecute through PowerShell on Windows, `open` on macOS,
 * `xdg-open` everywhere else, the name never parsed by a shell. Returns false when neither took it
 * (a headless CI box with no `xdg-open`), so a caller can show a notice instead of silently doing
 * nothing.
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
    return runCatching { openCommand(file.absolutePath).start(ProcessBuilder.Redirect.INHERIT); true }.getOrDefault(false)
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

    override val level: StateFlow<Float>? get() = recorder.level

    override suspend fun snapshot(): CapturedAudio? {
        val wav = recorder.snapshot() ?: return null
        return CapturedAudio(wav, "dictation-live.wav", "audio/wav")
    }

    override fun pcmSince(byteOffset: Long): ByteArray? = recorder.pcmSince(byteOffset)

    override suspend fun requestPermission(): Boolean = true

    override val available: Boolean
        get() = runCatching {
            AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, DICTATION_FORMAT))
        }.getOrDefault(false)

    /** Asked of the OS itself (see MicPresence): Java Sound lists a "default" line with no microphone behind it. */
    override suspend fun trouble(): MicTrouble = withContext(Dispatchers.IO) { askMicTrouble(DesktopOs.current) }

    override val liveTranscript: LiveTranscript? get() = null
}

// ── tts ──────────────────────────────────────────────────────────────────────

/**
 * Desktop read-aloud: shell out to whatever speech/playback binary the machine has. The `which`
 * probe order IS the behaviour — [speechCommand]'s `say` on macOS, PowerShell's `SpeechSynthesizer`
 * on Windows, then `spd-say`/`espeak-ng`/`espeak` on Linux, each given the agent's text as data;
 * playback prefers `ffplay`, then `mpv`, then `afplay`.
 *
 * One child process at a time: [stop] kills it, which is what makes a second tap on a speaking
 * message stop it — except under `spd-say`, whose message speech-dispatcher finishes anyway (see
 * [speechCommand]). A machine with none of the binaries silently speaks nothing — [speak] returns
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
            Files.createTempFile("read-aloud-", ".mp3").toFile().also { it.deleteOnExit(); it.writeBytes(bytes) }
        }.getOrNull() ?: return
        try {
            val cmd = playCommand(file.absolutePath) ?: return
            runProcess(OsCommand(cmd))
        } finally {
            runCatching { file.delete() }
        }
    }

    /** ffplay and mpv open an http URL and play as it downloads; afplay cannot. */
    override val canPlayUrl: Boolean by lazy { playUrlCommand("http://x") != null }

    override suspend fun playUrl(url: String) {
        val cmd = playUrlCommand(url) ?: return
        runProcess(OsCommand(cmd))
    }

    override fun stop() {
        process.getAndSet(null)?.destroyForcibly()
    }

    /** Nothing process-global to release: [stop] already kills the only child. */
    override fun shutdown() = stop()

    private suspend fun runProcess(cmd: OsCommand) = withContext(Dispatchers.IO) {
        runCatching {
            val p = cmd.start()
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

/** Audio playback command for [path], or null when no player is installed. */
internal fun playCommand(path: String): List<String>? = when {
    which("ffplay") -> listOf("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", path)
    which("mpv") -> listOf("mpv", "--no-video", "--really-quiet", path)
    which("afplay") -> listOf("afplay", path) // macOS
    else -> null
}

/** Streaming playback command for an http [url], or null when only a file player is installed. */
internal fun playUrlCommand(url: String): List<String>? = when {
    which("ffplay") -> listOf("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", url)
    which("mpv") -> listOf("mpv", "--no-video", "--really-quiet", url)
    else -> null
}

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
