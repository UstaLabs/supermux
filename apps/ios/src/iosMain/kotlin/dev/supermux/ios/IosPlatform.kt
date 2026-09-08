package dev.supermux.ios

import dev.supermux.ui.display.VideoSurfaceFactory
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.UnavailableEditorEngineFactory
import dev.supermux.ui.platform.AppUpdater
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.FileAccess
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.platform.NoAppUpdater
import dev.supermux.ui.platform.NoticeChannel
import dev.supermux.ui.platform.NoopNotificationManager
import dev.supermux.ui.platform.NotificationManager
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.platform.PushRegistrar
import dev.supermux.ui.platform.TtsEngine
import dev.supermux.ui.platform.WindowHostController
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.terminal.UnavailableTerminalViewFactory
import dev.supermux.ui.theme.Haptics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS's [Platform] — the third host of the shared Compose root, beside `AndroidPlatform` and
 * `DesktopPlatform`.
 *
 * H1 wires the members that are ANSWERS, not stubs: the capability set, the flows that are
 * genuinely empty on iOS, and the three "this host has none" factories. Everything that needs UIKit
 * throws [UnsupportedOperationException] naming itself, so H2 finds the remaining work by running
 * the app rather than by grepping for TODOs — and so a half-wired member can never masquerade as a
 * working one by silently doing nothing.
 *
 * The throwing members are all `get()`-based, deliberately: a `val x = unsupported(...)` would
 * throw while the object is being CONSTRUCTED, which would take the whole app down at launch
 * instead of at the call site that is actually missing.
 */
class IosPlatform(
    /** Everything only Swift can do. H1 links [NoopIosBridge]; H2/H3 pass the real one. */
    val bridge: IosBridge = NoopIosBridge,
) : Platform {

    override val caps: Caps = IOS_CAPS

    // ── Answered ────────────────────────────────────────────────────────────────────────────

    /**
     * Empty, and CORRECT rather than unfinished: these flows exist because Android can destroy the
     * awaiting composition while a system picker is in the foreground (an activity recreation on
     * rotation). iOS never re-creates the view controller under a presented sheet, so a pick's own
     * continuation is always still there to receive it — exactly as on desktop.
     */
    override fun pendingPicks(requester: String): Flow<PickedFile> = emptyFlow()
    override fun pendingScans(): Flow<String> = emptyFlow()

    /** No terminal engine until H5 hosts Swift's SwiftTerm view in a `UIKitView`; `caps.terminal`
     *  is false, and this factory draws the "no terminal here" hint if anything asks anyway. */
    override fun terminalView(): TerminalViewFactory = UnavailableTerminalViewFactory

    /**
     * No hardware decoder, so every display renders through `VncFramebuffer` — whose iOS actual is
     * the Skia one, which takes the RFB wire format directly. That is a complete display path, not
     * a degraded one; a VideoToolbox decoder is an optional H5 optimisation.
     */
    override fun videoDecoder(): VideoSurfaceFactory? = null

    /** The App Store and TestFlight own updating this app; there is nothing for the app to do. */
    override val updates: AppUpdater = NoAppUpdater

    /**
     * The app raises no notifications itself: agent replies arrive as APNs pushes, which the system
     * presents. Same reasoning as Android's.
     */
    override val notifications: NotificationManager = NoopNotificationManager

    /** One window. Panes cannot be torn out into an OS window on iOS. */
    override val windows: WindowHostController? = null

    /**
     * H3 supplies the real registrar over [IosBridge.registerForPush]. Null until then, which is
     * consistent with `caps.push = false` below — a screen that offers a push affordance now would
     * offer one that cannot work.
     */
    override val push: PushRegistrar? = null

    /**
     * No embedded browser until H5 runs the `EditorWeb` cm6 bundle in a `WKWebView`. Every editor
     * pane therefore renders its native fallback — still editable, still saving — rather than an
     * empty box.
     */
    override val editorEngine: EditorEngineFactory =
        UnavailableEditorEngineFactory("no editor engine on iOS yet")

    // ── H2: UIKit ───────────────────────────────────────────────────────────────────────────

    override fun openUrl(url: String): Unit = unsupported("openUrl")

    override fun copyToClipboard(text: String): Unit = unsupported("copyToClipboard")

    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> =
        unsupported("pickFiles")

    override suspend fun captureImage(requester: String): PickedFile? = unsupported("captureImage")

    override suspend fun captureVideo(requester: String): PickedFile? = unsupported("captureVideo")

    override val haptics: Haptics get() = unsupported("haptics")

    override val clipboard: ClipboardAccess get() = unsupported("clipboard")

    override val files: FileAccess get() = unsupported("files")

    override val notices: NoticeChannel get() = unsupported("notices")

    // ── H3: native bridges ──────────────────────────────────────────────────────────────────

    override suspend fun scanQr(): String? = unsupported("scanQr")

    override val mic: MicCapture get() = unsupported("mic")

    override val tts: TtsEngine get() = unsupported("tts")

    /**
     * Named rather than generic on purpose: the message that reaches a crash report says exactly
     * which seam is missing, so an H2/H3 gap is one line of triage instead of a stack walk.
     */
    private fun unsupported(member: String): Nothing =
        throw UnsupportedOperationException("IosPlatform.$member is not wired yet (cluster H2/H3)")
}

/**
 * What an iPhone/iPad can do.
 *
 * `push`, `terminal`, `scrcpy`, `hardwareVideoDecode` and `appUpdate` are all false FOR NOW and flip
 * in their own clusters (H3 for push, H5 for the terminal and the decoder); the App Store owns
 * updating, so `appUpdate` stays false forever. Everything else is a permanent property of the
 * platform: one window, no tray, no arbitrary file system (the app is confined to what a document
 * picker grants it), no local broker process.
 */
val IOS_CAPS: Caps = Caps(
    push = false,
    camera = true,
    tray = false,
    externalDisplay = true,
    hardwareVideoDecode = false,
    localBroker = false,
    multiWindow = false,
    fileSystem = false,
    clipboardImages = true,
    saveAs = true,
    walkthrough = false,
    appearanceControls = true,
    // Material You is an Android 12+ wallpaper API. iOS has no equivalent, so the row is not
    // offered at all rather than shown as an inert switch.
    dynamicColor = false,
    appUpdate = false,
    terminal = false,
    scrcpy = false,
)
