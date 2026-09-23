package dev.supermux.ios

import dev.supermux.ui.display.VideoSurfaceFactory
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.IosEditorEngineFactory
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
import dev.supermux.ui.terminal.SharedTerminal
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.theme.Haptics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import platform.UIKit.UIPasteboard

/**
 * iOS's [Platform] — the third host of the shared Compose root, beside `AndroidPlatform` and
 * `DesktopPlatform`.
 *
 * Every member ANSWERS. Nothing here throws, and that is a deliberate correction to H1, which
 * threw from every unwired seam so that H2 would find them by running the app.
 *
 * Running the app did find them — and showed why the technique cannot be used for these seams at
 * all. `Platform.mic` and `Platform.tts` are read while a screen COMPOSES, not when the user acts,
 * so a throwing getter killed the chat composition the moment a session was opened; and because
 * `installIosCrashGuard` deliberately keeps the process alive after an unhandled exception, the
 * only symptom was a session list whose rows did nothing when tapped. A seam that composition
 * reads must always answer; the capability flags (`caps`, `MicCapture.available`) are how a host
 * says "not here", and every shared screen already branches on them.
 */
class IosPlatform(
    /** Everything only Swift can do. H1 links [NoopIosBridge]; H2/H3 pass the real one. */
    val bridge: IosBridge = NoopIosBridge,
) : Platform {

    /** An iPad shows panes in extra windows ([IosWindowHostController]); an iPhone cannot. */
    private val extraWindows = bridge.supportsExtraWindows()

    override val caps: Caps = IOS_CAPS.copy(multiWindow = extraWindows)

    // ── Answered ────────────────────────────────────────────────────────────────────────────

    /**
     * Empty, and CORRECT rather than unfinished: these flows exist because Android can destroy the
     * awaiting composition while a system picker is in the foreground (an activity recreation on
     * rotation). iOS never re-creates the view controller under a presented sheet, so a pick's own
     * continuation is always still there to receive it — exactly as on desktop.
     */
    override fun pendingPicks(requester: String): Flow<PickedFile> = emptyFlow()

    /**
     * NOT empty, unlike [pendingPicks] — and for a reason that has nothing to do with activity
     * recreation.
     *
     * The contract this flow actually carries is "a pairing string arrived from outside the Add
     * host screen, claim it": `AddHostScreen` collects it and runs its paste path over whatever
     * comes through. On Android that is a QR scan redelivered after the scanner activity tore the
     * composition down. On iOS it is a `supermux://pair` link the user opened while the app was
     * already paired — the same "here is a host, add it" event through the same door, rather than
     * a second mechanism doing the identical thing.
     *
     * The value is consumed as it is handed over so re-entering Add host later does not re-claim a
     * link the user already used.
     */
    override fun pendingScans(): Flow<String> =
        IosAppState.pendingPairLink.filterNotNull().onEach { IosAppState.consumePendingPairLink() }

    /**
     * The shared Compose terminal (Plan 4 Task 3).
     *
     * There is no Swift vendor to be missing any more: the engine is a static archive linked into
     * the same framework as this file, so the terminal is either there or the app did not build.
     */
    override fun terminalView(): TerminalViewFactory = SharedTerminal

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

    /** Extra windows on an iPad: each is a scene of the `extra` WindowGroup. Null on an iPhone. */
    override val windows: WindowHostController? =
        if (extraWindows) IosWindowHostController(bridge) else null

    /**
     * Swift's `PushManager`, behind the shared seam. See [IosPushRegistrar] for why two of its
     * four members are deliberately no-ops on this platform.
     */
    override val push: PushRegistrar = IosPushRegistrar(bridge)

    /**
     * The `EditorWeb` cm6 bundle in a `WKWebView` (cluster H5) — the same committed bundle Android
     * loads from its assets and desktop loads under JCEF, driven by the same shared
     * `EditorPushPlanner` and the same `cm*` JS.
     */
    override val editorEngine: EditorEngineFactory = IosEditorEngineFactory

    // ── H2: UIKit ───────────────────────────────────────────────────────────────────────────

    /** `UIApplication.openURL` lives on the Swift side — it is app-delegate territory, and a
     *  Compose surface has no application object of its own to reach for. Fire-and-forget. */
    override fun openUrl(url: String) = onMainThread { bridge.openUrl(url) }

    /** Parity with Android's `ClipData.newPlainText`; iOS has no clip label to set. */
    override fun copyToClipboard(text: String) = onMainThread {
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

    // ── H3: native bridges ─────────────────────────────────────────────────────────────────
    //
    // Each of these is a Swift object that existed for the SwiftUI shell, wrapped rather than
    // reimplemented: `PushManager` (above), `AudioRecorder` + `SpeechDictation`, `MessageSpeech`,
    // `QRScannerView`. Nothing about push, speech or the camera got better by being rewritten in
    // Kotlin, and each of them shares state with something outside this process — the push keypair
    // with the notification service extension, the audio session with the OS.
    //
    // They still ANSWER rather than throw, and H1's opposite choice here was a bug worth recording.
    // The rule that matters is not "is it wired yet" but WHO READS IT. `mic` and `tts` are read
    // while the chat screen COMPOSES — the composer asks `mic.available` to decide whether to
    // offer a mic button, the timeline reads `tts` per message row — so a throwing getter killed
    // the composition the moment a session was opened. With `installIosCrashGuard` keeping the
    // process alive, the only visible symptom was that tapping a session did nothing whatsoever:
    // no crash, no message, just a list that would not open. A seam composition reads must always
    // answer; only a seam reached by an explicit user action may fail loudly. That is why
    // `micAvailable()` is a permission read and not a device probe.

    /** Swift presents the AVFoundation scanner (`QRScannerView`'s own controller) and reports the
     *  decoded text, or null if the user backed out or the camera is unavailable — which is every
     *  simulator. Null is "cancelled", which every caller already handles. */
    override suspend fun scanQr(): String? = awaitCallback { done -> bridge.scanQr(done) }

    /**
     * Swift's `AudioRecorder` + `SpeechDictation`. The live transcript is offered unconditionally
     * — unlike Android, which gates it on a dev flag — because on-device recognition is a first
     * class iOS capability the SwiftUI composer already used, and the shared controller falls back
     * to record-then-POST by itself the moment `start()` answers false.
     */
    override val mic: MicCapture = IosMicCapture(bridge, IosLiveTranscript(bridge))

    override val tts: TtsEngine = IosTtsEngine(bridge)

}

/**
 * What an iPhone/iPad can do.
 *
 * `terminal` is true from H5 (SwiftTerm through `UIKitView`). `scrcpy` and `hardwareVideoDecode`
 * stay false: every display renders through the Skia `VncFramebuffer`, which is a complete path
 * rather than a degraded one, and a VideoToolbox decoder is deferred (see the H5 plan). The App Store
 * owns updating, so `appUpdate` stays false forever. `push` is true from H3: `IosPushRegistrar`
 * drives the same APNs → relay → broker registration the SwiftUI shell used, and the notification
 * service extension that decrypts the sealed alerts is untouched by this cluster. Everything else is a permanent property of the
 * platform: no tray, no arbitrary file system (the app is confined to what a document
 * picker grants it), no local broker process.
 */
val IOS_CAPS: Caps = Caps(
    push = true,
    camera = true,
    tray = false,
    externalDisplay = true,
    hardwareVideoDecode = false,
    localBroker = false,
    // An iPhone's table; `IosPlatform` flips it on an iPad (see `IosWindows.kt`).
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
    // `Platform.terminalView()` builds the shared Compose terminal. NB nothing in `:ui` reads this
    // cap to gate the terminal — so it is the FACTORY that must be right; this flag is the honest
    // description beside it.
    terminal = true,
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
