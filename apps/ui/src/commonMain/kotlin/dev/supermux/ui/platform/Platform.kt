package dev.supermux.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.net.ChunkSource
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.theme.Haptics

/**
 * Everything a shared screen needs from the machine it happens to be running on.
 *
 * Screens NEVER touch a platform API directly — no `Intent`, no `java.awt.Desktop`, no
 * `ClipboardManager`, no `rememberLauncherForActivityResult`. They read [LocalPlatform] and call
 * one of the members below, so the same composable compiles and behaves on Android and desktop.
 * Each app implements this once (`AndroidPlatform` / `DesktopPlatform`) and provides it at its
 * entry point.
 *
 * **This is deliberately the cluster-A subset.** The spec's full `Platform` (see
 * `docs/superpowers/specs/2026-09-04-screens-into-ui-design.md` §Foundations 3) grows these
 * further seams as the screens that need them move into `:ui` — do NOT invent parallel
 * abstractions for them, add them here:
 *  - `val tts: TtsEngine` — `android.speech.tts` / desktop broker-stream player (cluster D, voice).
 *  - `val mic: MicCapture` — `AudioRecord` / `javax.sound` (cluster D, dictation).
 *  - `fun terminalView(): TerminalViewFactory` — termlib / jediterm (cluster C).
 *  - `fun videoDecoder(): VideoSurfaceFactory?` — MediaCodec / null (cluster D, displays).
 *  - `val updates: AppUpdater` — APK install / DMG-MSI download (cluster G).
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
 * @property push native push notifications are delivered (Android FCM; desktop has none).
 * @property camera an in-app capture affordance can be offered.
 * @property tray the app lives in a system tray / menu-bar item.
 * @property externalDisplay the remote-display (scrcpy) surface can be shown.
 * @property hardwareVideoDecode a hardware H.264 decoder is available (Android MediaCodec).
 * @property localBroker the app can run and supervise a broker process itself.
 * @property multiWindow panes can be detached into real OS windows.
 * @property fileSystem the app can read and write arbitrary local paths (desktop only; Android is
 *   confined to SAF-granted URIs, which is NOT a general file system).
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
