package dev.supermux.desktop.platform

import dev.supermux.desktop.editor.DesktopEditorEngineFactory
import dev.supermux.desktop.notify.DesktopNotifications
import dev.supermux.desktop.shell.DesktopWindowHostController
import dev.supermux.desktop.update.DesktopAppUpdater
import dev.supermux.desktop.upload.FileChunkSource
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.platform.AppUpdater
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.FileAccess
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.TtsEngine
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.NotificationManager
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.platform.PushRegistrar
import dev.supermux.ui.platform.WindowHostController
import dev.supermux.ui.display.VideoSurfaceFactory
import dev.supermux.ui.terminal.SharedTerminal
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.NoHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files

/**
 * Desktop's [Platform]: `java.awt.Desktop.browse` for links, the AWT system clipboard, the AWT
 * file dialog for pickers, and no haptics (no actuator on a desktop).
 *
 * `fileSystem = true` and `localBroker = true` are what actually separate desktop from Android in
 * the screens: it can browse real paths and it can supervise its own broker process.
 */
class DesktopPlatform : Platform {

    override val caps: Caps = Caps(
        push = false,
        camera = false,
        tray = true,
        externalDisplay = true,
        hardwareVideoDecode = false,
        localBroker = true,
        multiWindow = true,
        fileSystem = true,
        // Both are AWT, so both are false on a headless box — where the save dialog would throw
        // and the clipboard is empty — rather than offering an affordance that cannot work.
        clipboardImages = !GraphicsEnvironment.isHeadless(),
        saveAs = !GraphicsEnvironment.isHeadless(),
        // DesktopWalkthroughSeam is installed on every HostStore this app builds (Main.kt).
        walkthrough = true,
        // E7: the desktop app owns its own look too — the shared Appearance screen writes the same
        // `SettingsKeys.APPEARANCE` the sidebar's theme toggle does, so the hub offers the row.
        appearanceControls = true,
        // No wallpaper-derived scheme off Android: the Material You row would be an inert switch.
        dynamicColor = false,
        // The app updates ITSELF here as well (Route.AppUpdate → the shared `ui/update/AppUpdate.kt`).
        appUpdate = true,
        // The shared Ghostty renderer is bound (`terminalView()` never returns the
        // unavailable factory here).
        terminal = true,
        // No hardware H.264 decoder: desktop displays are VNC, so `videoDecoder()` is null.
        scrcpy = false,
    )

    /** The shared Compose terminal. No `SwingPanel` is left on the terminal path: the grid is
     * drawn by Compose, so the desktop pane is no longer a heavyweight interop child. */
    override fun terminalView(): TerminalViewFactory = SharedTerminal

    /** No MediaCodec here; every display falls back to its VNC framebuffer. */
    override fun videoDecoder(): VideoSurfaceFactory? = null

    /** Process-wide so a theme remount does not orphan an in-flight download's status. */
    override val updates: AppUpdater = DesktopAppUpdater.shared

    /** The tray manager `Main.kt` installs once `Tray(...)` exists; a no-op before that. */
    override val notifications: NotificationManager = DesktopNotifications

    /** Detached workspace windows — the shell binds the live registry into this. */
    override val windows: WindowHostController = DesktopWindowHostController

    /** No FCM off Android; desktop's own tray notifications cover the live process. */
    override val push: PushRegistrar? = null

    /** The direct-JCEF browser that hosts CodeMirror; one per app, wrapping the process-global
     *  [dev.supermux.desktop.editor.JcefRuntime]. */
    override val editorEngine: EditorEngineFactory = DesktopEditorEngineFactory.shared

    /** Delegates to [openInBrowser], which keeps the daemon-thread hand-off and the
     *  `openInBrowserOverride` / `supermux.tests` guards every desktop test relies on. */
    override fun openUrl(url: String) = openInBrowser(url)

    /** AWT system clipboard. Headless (CI without an X server) throws — swallowed, as a failed
     *  copy must never take the app down. */
    override fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    /**
     * The AWT dialog, mapped to streaming [FileChunkSource]s. Multi-select (unlike Android).
     *
     * `requester` is ignored: an AWT dialog cannot outlive its caller, so there is never an
     * orphaned result to re-route.
     *
     * Hopped onto [Dispatchers.Swing] explicitly: AWT requires the EDT, and a caller may well be on
     * a background dispatcher (Compose Desktop's Main happens to be the EDT, but nothing in this
     * signature promises the caller is on it).
     */
    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> = withContext(Dispatchers.Swing) {
        awtPickFiles(kind).map { file -> PickedFile(file.name, probeMime(file), FileChunkSource(file)) }
    }

    /** No camera on a desktop (`caps.camera == false`), so nothing offers this; null keeps the
     *  contract total for a caller that asks anyway. */
    override suspend fun scanQr(): String? = null

    /** No camera (`caps.camera == false`) — see [scanQr]. */
    override suspend fun captureImage(requester: String): PickedFile? = null

    /** No camera (`caps.camera == false`) — see [scanQr]. */
    override suspend fun captureVideo(requester: String): PickedFile? = null

    /** An AWT dialog cannot outlive its caller, so a pick is never orphaned and there is nothing
     *  to re-deliver. */
    override fun pendingPicks(requester: String): Flow<PickedFile> = emptyFlow()

    override val clipboard: ClipboardAccess = DesktopClipboardAccess()

    override val files: FileAccess = DesktopFileAccess()

    override val mic: MicCapture = DesktopMicCapture()

    /** Process-wide, not per-window — see [SharedDesktopTts]. */
    override val tts: TtsEngine get() = SharedDesktopTts

    /** Rendered by `DesktopTheme`'s snackbar host — one bus per window root, which is where
     *  [DesktopPlatform] itself is constructed. */
    override val notices: DesktopNotices = DesktopNotices()

    override val haptics: Haptics = NoHaptics
}

/**
 * THE desktop file picker: a modal AWT [FileDialog] in LOAD mode, multi-select.
 *
 * Blocking by AWT contract and modal on the EDT — Compose Desktop's main dispatcher IS the EDT, so
 * calling it from a click handler (or a `scope.launch` on Main) behaves exactly like the inline
 * dialogs it replaced. Kept `internal` + non-suspend so the composer's injectable
 * `() -> List<File>` test seam can still default to it.
 *
 * [PickKind.Any] deliberately installs no filter (unchanged behaviour for "Attach files"); the
 * media kinds use a name filter, which AWT honours on X11/macOS and ignores on Windows — a
 * best-effort hint, never a guarantee, the same as the platform pickers.
 */
internal fun awtPickFiles(kind: PickKind): List<File> {
    val dialog = pickDialogFor(kind)
    dialog.isVisible = true
    return dialog.files?.toList() ?: emptyList()
}

/** The configured-but-not-yet-shown dialog, so the [PickKind] → title/mode/filter mapping is
 *  assertable without a modal window. */
internal fun pickDialogFor(kind: PickKind): FileDialog {
    val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    when (kind) {
        PickKind.Any -> Unit
        PickKind.Images -> dialog.setFilenameFilter { _, name -> name.hasExtensionIn(IMAGE_EXTENSIONS) }
        PickKind.Media -> dialog.setFilenameFilter { _, name ->
            name.hasExtensionIn(IMAGE_EXTENSIONS) || name.hasExtensionIn(VIDEO_EXTENSIONS)
        }
    }
    return dialog
}

/**
 * THE desktop save dialog: a modal AWT [FileDialog] in SAVE mode, pre-filled with [defaultName].
 * Returns the chosen target file, or null when the user cancelled. Not (yet) on the shared
 * [Platform] interface — no shared screen saves a file in cluster A; the timeline's attachment
 * download is the only caller and it moves in cluster D.
 */
internal fun awtSaveFile(defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, "Save attachment", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val fileName = dialog.file ?: return null
    return File(dir, fileName)
}

/** Best-effort content type; `application/octet-stream` when the OS cannot tell. The ONE mime
 *  guess on desktop — the launcher and the composer both route through it. */
internal fun probeMime(file: File): String =
    runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi")

private fun String.hasExtensionIn(extensions: Set<String>): Boolean =
    substringAfterLast('.', "").lowercase() in extensions
