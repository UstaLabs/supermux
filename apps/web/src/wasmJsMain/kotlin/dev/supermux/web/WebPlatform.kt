package dev.supermux.web

import dev.supermux.ui.display.VideoSurfaceFactory
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.platform.AppUpdater
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.FileAccess
import dev.supermux.ui.platform.FlowNotices
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.platform.NoAppUpdater
import dev.supermux.ui.platform.NoopNotificationManager
import dev.supermux.ui.platform.NotificationManager
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.platform.PushRegistrar
import dev.supermux.ui.platform.TtsEngine
import dev.supermux.ui.platform.WindowHostController
import dev.supermux.ui.terminal.SharedTerminal
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.NoHaptics
import dev.supermux.web.editor.WebEditorEngineFactory
import dev.supermux.web.seams.WebClipboard
import dev.supermux.web.seams.WebFiles
import dev.supermux.web.seams.WebMic
import dev.supermux.web.seams.WebTts
import dev.supermux.web.seams.pickFilesViaInput
import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach

/**
 * What the browser can do. Plan 3 turns on terminal (xterm.js) and clipboardImages; plan 4 push
 * and [Caps.setupWizard].
 *
 * `setupWizard` is true HERE and nowhere else: the browser is the surface a brand-new broker is
 * opened on, so it is the one host that must be able to run first-run setup. Android/iOS/desktop
 * pair into a broker somebody already set up, and a wizard there would be asking the wrong device
 * to install agents on the broker's machine.
 */
val WEB_CAPS = Caps(
    push = true, camera = false, tray = false, externalDisplay = true, hardwareVideoDecode = false,
    localBroker = false, multiWindow = false, fileSystem = false,
    clipboardImages = true, saveAs = true, walkthrough = true, appearanceControls = true,
    dynamicColor = false, appUpdate = false, terminal = true, scrcpy = false,
    setupWizard = true,
)

/**
 * The browser's [Platform] — the fourth host of the shared Compose root. Every member ANSWERS
 * (several are read during composition); "not here" is said through [caps], never by throwing.
 */
class WebPlatform(
    /**
     * Web Push, when the page has a broker to subscribe against. Constructor-injected rather than
     * built here because [dev.supermux.web.push.WebPushRegistrar] needs a `BrokerApi` and the
     * platform is created BEFORE the fleet exists — `main()` owns that ordering, so `main()` owns
     * the registrar. Null keeps every push read in the shell on the "this host has none" path.
     */
    override val push: PushRegistrar? = null,
) : Platform {
    init {
        // The `paste` hook has to be listening before the user pastes — see [WebClipboard].
        WebClipboard.install()
    }

    override val caps: Caps = WEB_CAPS
    /** Gesture-bound: `window.open` off the gesture's own tick is popup-blocked silently (see
     *  [dev.supermux.web.seams.WebFiles]), so never await anything before calling this. */
    override fun openUrl(url: String) { window.open(url, "_blank", "noopener") }
    override fun copyToClipboard(text: String) { copyTextJs(text) }
    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> = pickFilesViaInput(kind)
    override suspend fun scanQr(): String? = null
    override val haptics: Haptics = NoHaptics
    override suspend fun captureImage(requester: String): PickedFile? = null
    override suspend fun captureVideo(requester: String): PickedFile? = null
    override fun pendingPicks(requester: String): Flow<PickedFile> = emptyFlow()

    /** A `/pair?t=` link opened while already paired = "add this host", same door as iOS. */
    override fun pendingScans(): Flow<String> =
        WebAppState.pendingPairLink.filterNotNull().onEach { WebAppState.consumePendingPairLink() }

    override val clipboard: ClipboardAccess = WebClipboard
    override val files: FileAccess = WebFiles
    override val mic: MicCapture = WebMic
    override val tts: TtsEngine = WebTts

    /** Typed [FlowNotices], not [dev.supermux.ui.platform.NoticeChannel]: `WebTheme` hands this
     *  very instance to `NoticeOverlay`, which takes the concrete bus. */
    override val notices: FlowNotices = FlowNotices()

    /** xterm.js behind the shared seam — one instance, like every other host's factory object. */
    override fun terminalView(): TerminalViewFactory = SharedTerminal
    override fun videoDecoder(): VideoSurfaceFactory? = null
    override val updates: AppUpdater = NoAppUpdater
    override val notifications: NotificationManager = NoopNotificationManager
    override val windows: WindowHostController? = null
    // The committed cm6 bundle in a same-origin iframe, driven by desktop's bridge protocol.
    override val editorEngine: EditorEngineFactory = WebEditorEngineFactory()
}

@Suppress("UNUSED_PARAMETER")
private fun copyTextJs(text: String): Unit = js("{ if (navigator.clipboard) navigator.clipboard.writeText(text); }")
