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
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.platform.FlowNotices
import dev.supermux.ui.platform.NoticeOverlay
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
import platform.UIKit.UIPasteboard

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

    /** `UIApplication.openURL` lives on the Swift side — it is app-delegate territory, and a
     *  Compose surface has no application object of its own to reach for. Fire-and-forget. */
    override fun openUrl(url: String) = bridge.openUrl(url)

    /** Parity with Android's `ClipData.newPlainText`; iOS has no clip label to set. */
    override fun copyToClipboard(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    /**
     * `UIDocumentPickerViewController` / `PHPickerViewController` through the bridge, suspended
     * until the user picks or dismisses. Cancelling yields an empty list.
     *
     * [requester] is ignored, and that is the correct answer rather than an omission: it exists so
     * Android can re-deliver a pick to the screen that asked after an activity recreation, and iOS
     * never re-creates the view controller under a presented sheet. See [pendingPicks].
     *
     * The bytes arrive whole rather than as a stream because a `UIDocumentPicker` hands Swift a
     * security-scoped URL whose access must be stopped before the callback returns — reading it
     * lazily from Kotlin later would be reading a revoked URL. They are wrapped in a
     * [ByteArrayChunkSource] so the upload path is byte-identical to the other two hosts'.
     */
    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> =
        awaitCallback<List<IosPickedFile>> { done -> bridge.pickFiles(kind.wire, done) }
            .map { it.toPickedFile() }

    /** `UIImagePickerController(.camera)` in still mode, through the bridge. Null = backed out. */
    override suspend fun captureImage(requester: String): PickedFile? =
        awaitCallback<IosPickedFile?> { done -> bridge.captureImage(done) }?.toPickedFile()

    /** The same, in video mode. */
    override suspend fun captureVideo(requester: String): PickedFile? =
        awaitCallback<IosPickedFile?> { done -> bridge.captureVideo(done) }?.toPickedFile()

    override val haptics: Haptics = IosHaptics()

    override val clipboard: ClipboardAccess = IosClipboardAccess()

    override val files: FileAccess = IosFileAccess(bridge)

    /**
     * A Compose snackbar at the root, NOT a system alert.
     *
     * iOS has no Toast: an app that wants a transient line draws it. `UIAlertController` would be
     * the wrong shape twice over — it is modal, and it demands a dismissal for a message the user
     * is meant to be able to ignore. `MainViewController` composes [NoticeOverlay] over the app
     * root and drains this, exactly as desktop's theme does.
     */
    override val notices: FlowNotices = FlowNotices()

    // ── H3: native bridges ──────────────────────────────────────────────────────────────────
    //
    // Still throwing, deliberately. Each of these is a Swift capability the bridge already declares
    // (AVFoundation's scanner, `SFSpeechRecognizer`, `AVSpeechSynthesizer`) but that H3 wires and
    // proves on a device. A stub that quietly returned "unavailable" instead would be worse than a
    // throw: `caps.camera` is TRUE, so the add-host screen offers a Scan button, and a silent null
    // would read to the user as a scanner that is broken rather than one that is not here yet.

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
    // `MainViewController` installs `IosWalkthroughSeam` in its HostStore factory, exactly as
    // Android's `AppViewModel` does — so the diff pane may offer the walkthrough slideshow.
    // Reading a walkthrough holder on a host that never installed the seam would throw.
    walkthrough = true,
    appearanceControls = true,
    // Material You is an Android 12+ wallpaper API. iOS has no equivalent, so the row is not
    // offered at all rather than shown as an inert switch.
    dynamicColor = false,
    appUpdate = false,
    terminal = false,
    scrcpy = false,
)

/**
 * The wire form of [PickKind] the bridge takes.
 *
 * A string and not the enum itself: crossing the boundary as an enum would make the Swift side
 * depend on Kotlin's declaration ORDER (an `@objc` enum is its ordinal), so inserting a kind here
 * would silently re-point every existing Swift branch.
 */
internal val PickKind.wire: String
    get() = when (this) {
        PickKind.Any -> "any"
        PickKind.Images -> "images"
        PickKind.Media -> "media"
    }

/** The bridge's byte-carrying file, as the streaming [PickedFile] the shared upload path takes. */
internal fun IosPickedFile.toPickedFile(): PickedFile =
    PickedFile(name, mime, ByteArrayChunkSource(bytes))
