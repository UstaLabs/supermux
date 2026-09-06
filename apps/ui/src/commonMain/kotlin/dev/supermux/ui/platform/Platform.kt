package dev.supermux.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.net.ChunkSource
import kotlinx.coroutines.flow.Flow
import dev.supermux.ui.display.VideoSurfaceFactory
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.theme.Haptics

/**
 * Everything a shared screen needs from the machine it happens to be running on.
 *
 * Screens NEVER touch a platform API directly — no `Intent`, no AWT `Desktop`, no
 * `ClipboardManager`, no `rememberLauncherForActivityResult`. They read [LocalPlatform] and call
 * one of the members below, so the same composable compiles and behaves on Android and desktop.
 * Each app implements this once (`AndroidPlatform` / `DesktopPlatform`) and provides it at its
 * entry point.
 *
 * Cluster G1 completed the set (the spec's full `Platform`, see
 * `docs/superpowers/specs/2026-09-04-screens-into-ui-design.md` §Foundations 3): the terminal
 * engine, the H.264 decoder, self-update, OS notifications, extra OS windows and push all reach a
 * shared screen through the members below. Do NOT invent a parallel abstraction for a new one —
 * add it here.
 */
interface Platform {
    /** What this machine can do. Screens branch on capabilities, never on "is this Android". */
    val caps: Caps

    /** Open [url] in the system browser. Fire-and-forget: never throws, never blocks the frame. */
    fun openUrl(url: String)

    /** Put [text] on the system clipboard. Fire-and-forget: never throws. */
    fun copyToClipboard(text: String)

    /**
     * Show the system file picker and suspend until the user picks or cancels; cancelling returns
     * an empty list. Android returns at most one file (SAF / photo picker are single-select here),
     * desktop's AWT dialog is multi-select — callers must handle 0..n either way.
     *
     * The result streams: each [PickedFile] carries a [ChunkSource] that can be handed straight to
     * `HostStore.uploadResumable` without buffering the bytes in the heap.
     *
     * [requester] identifies the calling screen (e.g. `"chat-composer"`). It only matters where the
     * platform can lose the caller mid-pick: on Android an activity recreation (rotation while the
     * picker is up) kills the awaiting coroutine, and the result is later re-delivered to the
     * screen that asked for it — never to whichever screen happens to be composed first. Platforms
     * with a stable caller (desktop) ignore it.
     */
    suspend fun pickFiles(kind: PickKind, requester: String = DEFAULT_REQUESTER): List<PickedFile>

    /**
     * Show the camera QR scanner and suspend until it decodes or the user backs out; cancelling —
     * or a platform with no camera — returns `null`.
     *
     * Ask [Caps.camera] before offering a "Scan" affordance at all: desktop has no camera and
     * returns `null` immediately, which would look like a broken button.
     *
     * One scan at a time: a second call while a scanner is up returns `null` without launching.
     * Unlike [pickFiles] there is no recreation stash — a scan interrupted by an activity restart
     * is simply re-taken, because nothing was staged and re-pointing the camera is cheap.
     */
    suspend fun scanQr(): String?

    /** Platform haptics (Android's `View.performHapticFeedback`; a no-op where there is no actuator). */
    val haptics: Haptics

    /**
     * Take a photo with the system camera and stage it for upload, or null when the user backed out
     * (and immediately on a machine with no camera).
     *
     * Ask [Caps.camera] before offering the affordance at all — desktop has none. Recreation-safe
     * the same way [pickFiles] is: a capture that finishes while the activity is being re-created is
     * re-delivered on [pendingPicks] under [requester] instead of being lost.
     */
    suspend fun captureImage(requester: String = DEFAULT_REQUESTER): PickedFile?

    /** Record a video with the system camera. Same contract as [captureImage]. */
    suspend fun captureVideo(requester: String = DEFAULT_REQUESTER): PickedFile?

    /**
     * Results of picks and captures that completed with nobody left to await them — on Android the
     * user rotated the device while the system picker/camera was in the foreground, so the
     * coroutine inside [pickFiles]/[captureImage] died with the old composition. The re-created
     * screen collects this and stages the file exactly as if its own call had returned it.
     *
     * Only the screen that asked sees it: [requester] must match the one it passed. Collect it for
     * the lifetime of the screen — a one-shot read races the delivery. Desktop (whose dialogs
     * cannot outlive their caller) emits nothing.
     */
    fun pendingPicks(requester: String): Flow<PickedFile>

    /** Images on the system clipboard, for paste-to-attach. Gated by [Caps.clipboardImages]. */
    val clipboard: ClipboardAccess

    /** "Save as…" / "Open with…" for bytes the app already holds. [FileAccess.saveAs] is gated by
     *  [Caps.saveAs]; opening externally works everywhere. */
    val files: FileAccess

    /** The microphone, for dictation. [MicCapture.available] says whether to offer a mic button. */
    val mic: MicCapture

    /** Read-aloud output (OS synthesiser + broker audio playback). */
    val tts: TtsEngine

    /** Transient "that didn't work" text — a toast on Android, a snackbar on desktop. */
    val notices: NoticeChannel

    /**
     * The host's terminal engine — jediterm inside a `SwingPanel` on desktop, ConnectBot termlib
     * inside an `AndroidView` on Android. Gated by [Caps.terminal]; a host with no engine installs
     * [dev.supermux.ui.terminal.UnavailableTerminalViewFactory], which draws a hint instead of a
     * grid. A function rather than a `val` because the factory may be built lazily per call site
     * (both hosts return the same instance today).
     */
    fun terminalView(): TerminalViewFactory

    /**
     * The host's hardware H.264 decoder for scrcpy displays, or null where there is none (desktop).
     * Gated by [Caps.scrcpy]: a display panel renders VNC whenever this is null or the cap is off.
     */
    fun videoDecoder(): VideoSurfaceFactory?

    /** The app updating ITSELF. Gated by [Caps.appUpdate]; a build that must not self-update
     *  installs [NoAppUpdater]. */
    val updates: AppUpdater

    /** OS notifications for agent replies. [NoopNotificationManager] where the host shows none
     *  itself (Android, whose replies arrive as pushes). Gated by [Caps.tray] on desktop. */
    val notifications: NotificationManager

    /** Detaching panes into real OS windows, or null where there is only one window
     *  ([Caps.multiWindow]). */
    val windows: WindowHostController?

    /** Native push registration, or null where the platform has no push ([Caps.push]). */
    val push: PushRegistrar?

    /**
     * Builds the browser that hosts CodeMirror — a `WebView` on Android, a direct-JCEF browser on
     * desktop. The shared editor surface reads this and never names either. A machine with no
     * browser at all installs `UnavailableEditorEngineFactory`, and every editor pane degrades to
     * its native fallback.
     */
    val editorEngine: EditorEngineFactory
}

/** Default [Platform.pickFiles] requester for screens that only ever have one picker in play. */
const val DEFAULT_REQUESTER: String = "default"

/**
 * Platform capabilities, as plain booleans decided once per app at construction time.
 *
 * @property push native push notifications are delivered (Android FCM; desktop has none), and
 *   [Platform.push] is therefore non-null.
 * @property terminal a real terminal engine is bound, so terminal views/tabs can be offered at all.
 * @property scrcpy an H.264 display transport can be decoded here ([Platform.videoDecoder] is
 *   non-null); false means every display falls back to its VNC framebuffer.
 * @property camera an in-app capture affordance can be offered.
 * @property tray the app lives in a system tray / menu-bar item, so [Platform.notifications] can
 *   actually raise one.
 * @property externalDisplay the remote-display (scrcpy) surface can be shown.
 * @property hardwareVideoDecode a hardware H.264 decoder is available (Android MediaCodec).
 * @property localBroker the app can run and supervise a broker process itself.
 * @property multiWindow panes can be detached into real OS windows ([Platform.windows] is
 *   non-null).
 * @property fileSystem the app can read and write arbitrary local paths (desktop only; Android is
 *   confined to SAF-granted URIs, which is NOT a general file system).
 * @property clipboardImages the system clipboard can hand back pasted images.
 * @property saveAs the user can be asked where to write a file ("Save as…"). False on a host with
 *   no save dialog at all (a headless desktop), where [FileAccess.saveAs] returns false.
 * @property appearanceControls the app owns its own look — theme mode, Material You, text scale —
 *   so the Settings hub offers the Appearance row (Android today; desktop in cluster E7).
 * @property dynamicColor the OS can supply a wallpaper-derived colour scheme (Android 12+), so
 *   the Appearance screen offers the Material You row at all. False everywhere else — the row
 *   would be an inert switch. NOTE the setting is a no-op for COLOUR on every platform (the brand
 *   palette is the only palette); this gates whether the choice is even offered.
 * @property appUpdate the app can update ITSELF (not the broker), so the hub offers the
 *   "Check for updates" row routing to the host's updater screen (cluster G owns that screen).
 * @property walkthrough the app builds its `HostStore` with a `WalkthroughSeam`, so the diff pane
 *   can offer the walkthrough slideshow. False on a host that never installs the seam — reading a
 *   walkthrough holder there would throw.
 */
data class Caps(
    val push: Boolean,
    val camera: Boolean,
    val tray: Boolean,
    val externalDisplay: Boolean,
    val hardwareVideoDecode: Boolean,
    val localBroker: Boolean,
    val multiWindow: Boolean,
    val fileSystem: Boolean,
    val clipboardImages: Boolean = false,
    val saveAs: Boolean = false,
    val walkthrough: Boolean = false,
    val appearanceControls: Boolean = false,
    val dynamicColor: Boolean = false,
    val appUpdate: Boolean = false,
    val terminal: Boolean = false,
    val scrcpy: Boolean = false,
)

/**
 * What [Platform.pickFiles] should offer. The union of what both pickers can express today:
 *  - [Any] — every file type (Android `GetContent` with a wildcard MIME, AWT dialog with no filter).
 *  - [Images] — still images only (Android `PickVisualMedia(ImageOnly)`, AWT name filter).
 *  - [Media] — images and video (Android `PickVisualMedia(ImageAndVideo)`, AWT name filter).
 */
enum class PickKind { Any, Images, Media }

/**
 * One file chosen by the user, ready to upload.
 *
 * @property name the display name (no directory part) shown on the attachment chip.
 * @property mime best-effort content type; `application/octet-stream` when unknown.
 * @property source streaming bytes — `ContentResolverChunkSource` on Android, `FileChunkSource` on
 *   desktop. Reads are on-demand, so a 2 GB video never lands in the heap.
 */
data class PickedFile(
    val name: String,
    val mime: String,
    val source: ChunkSource,
)

/**
 * The platform in scope. Static, because it never changes for the lifetime of a composition —
 * unprovided use is a wiring bug in an entry point, so it fails loudly rather than silently
 * no-op'ing a "copy" or "open link" affordance.
 */
val LocalPlatform = staticCompositionLocalOf<Platform> { error("No Platform provided") }
