// The assistant overlay on the desktop (#62): the Agent floating over every app in small windows of its
// own (the head, its bubble, its chat card), there while supermux's main window is closed and the app
// lives in the menu bar. The same character and the same model as on Android: click to chat, hold to
// talk, drag to move; its replies come up in the bubble.
package dev.supermux.desktop.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import dev.supermux.desktop.platform.DesktopOs
import dev.supermux.desktop.platform.isMacOs
import dev.supermux.state.FleetStore
import dev.supermux.ui.overlay.CompanionGesture
import dev.supermux.ui.overlay.CompanionPlacement
import dev.supermux.ui.overlay.Edge
import dev.supermux.ui.overlay.OverlayAgent
import dev.supermux.ui.overlay.OverlayBubble
import dev.supermux.ui.overlay.OverlayChat
import dev.supermux.ui.overlay.OverlayGlassesChip
import dev.supermux.ui.overlay.OverlayShot
import dev.supermux.ui.overlay.doubleTapWanted
import dev.supermux.ui.overlay.shareImageSize
import dev.supermux.ui.overlay.OverlayHead
import dev.supermux.ui.overlay.OverlayReplies
import dev.supermux.ui.overlay.OverlayScreen
import dev.supermux.ui.overlay.OverlayUiState
import dev.supermux.ui.overlay.OverlayVoice
import dev.supermux.ui.overlay.bubbleSpace
import dev.supermux.ui.overlay.overlayChoiceToStore
import dev.supermux.ui.overlay.rememberOverlayAgent
import dev.supermux.ui.overlay.rememberOverlayRig
import dev.supermux.ui.prefs.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.Window as AwtWindow

private const val HOLD_MS = 320L
private const val HEAD_DP = 56
/** The head's window: the head and room for its listening ring. */
private const val HEAD_BOX = 72
private const val MARGIN = 12
private const val GAP = 8
private const val CARD_W = 380
private const val CARD_H = 560
/** A click waits this long for a second one (a double-click puts its glasses on). */
private const val DOUBLE_CLICK_MS = 300L
/** How long it keeps its glasses on with nobody talking to it. */
private const val GLASSES_IDLE_MS = 5 * 60_000L
/** The longest edge of the screen picture sent with a talk. */
private const val SHARE_MAX_EDGE = 1600
// Each window has its own title: macOS finds a window by its title to float it everywhere (MacOverlayNative).
internal const val HEAD_TITLE = "supermux assistant"
internal const val BUBBLE_TITLE = "supermux assistant bubble"
internal const val CHAT_TITLE = "supermux assistant chat"

internal val isMac = isMacOs()

/** Where the overlay can float ([overlaySupportedOn]). */
val desktopOverlaySupported: Boolean = overlaySupportedOn(DesktopOs.current)

/**
 * The overlay, while the Appearance switch is on. [themed] wraps a window's content in the app's theme
 * (and its platform: the mic for hold-to-talk); [onOpenInApp] opens a chat in supermux's main window.
 */
@Composable
fun DesktopAssistantOverlay(
    fleet: FleetStore,
    prefs: UiPrefs,
    themed: @Composable (@Composable () -> Unit) -> Unit,
    onOpenInApp: (sessionId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val overlay = remember { DesktopOverlay(scope) }
    val choice by remember { prefs.assistantOverlayAgent.map { Choice(it) } }.collectAsState(null)
    val agent = rememberOverlayAgent(fleet, chosenId = choice?.id, ready = choice != null)
    SideEffect { overlay.agent = agent }
    val rig = rememberOverlayRig(fleet, agent?.id)
    val ui = overlay.ui
    val screenAllowed by prefs.assistantOverlayScreen.collectAsState(true)
    SideEffect { overlay.allowScreen(screenAllowed) }
    // The hold-to-talk key works from any app while the overlay is up: the one chosen in Settings.
    val talkKey by remember { prefs.assistantOverlayTalkKey.map { TalkKey.parse(it) } }.collectAsState(null)
    LaunchedEffect(overlay, talkKey) { talkKey?.let { overlay.useTalkKey(it) } }
    // Diagnostics: when the bubble comes and goes, and what decided it.
    LaunchedEffect(ui) {
        snapshotFlow { ui.bubbleVisible }.collect { shown ->
            println(
                "[OverlayBubble] ${if (shown) "up" else "down"}: listening=${ui.listening} finishing=${ui.finishing} " +
                    "reply=${ui.reply != null} folded=${ui.replyFolded} chatOpen=${ui.chatOpen} hidden=${ui.hidden} " +
                    "capturing=${ui.capturing} snapping=${ui.snapping} dragging=${ui.dragging} head=(${overlay.headX},${overlay.headY})",
            )
        }
    }
    DisposableEffect(overlay) { onDispose { overlay.stopTalkKey() } }

    OverlayWindow(HEAD_TITLE, overlay.headX, overlay.headY, HEAD_BOX, HEAD_BOX, focusable = false, onCreated = { overlay.headWindow = it }) {
        themed {
            // Hosted once, here: its replies pop the bubble, and hold-to-talk records through its mic.
            OverlayReplies(fleet, agent, ui)
            agent?.let { a -> key(a.id) { OverlayVoice(fleet, a.id, ui) } }
            Box(
                Modifier.size(HEAD_BOX.dp).pointerInput(overlay) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val p = MouseInfo.getPointerInfo()?.location ?: continue
                            val at = System.currentTimeMillis()
                            when (e.type) {
                                PointerEventType.Press -> if (e.buttons.isPrimaryPressed) overlay.down(p.x, p.y, at)
                                PointerEventType.Move -> overlay.move(p.x, p.y, at)
                                PointerEventType.Release -> overlay.up(at)
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                if (!ui.hidden) OverlayHead(agent, rig, ui, size = HEAD_DP.dp)
            }
        }
    }

    if (ui.bubbleVisible) {
        var size by remember { mutableStateOf(0 to 0) }
        val (x, y) = overlay.bubbleSpot(size.first, size.second)
        OverlayWindow(BUBBLE_TITLE, x, y, maxOf(size.first, 1), maxOf(size.second, 1), focusable = false) {
            themed {
                val density = LocalDensity.current.density
                // Measured at its natural size (not the window's), and the window follows it.
                Box(
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .onSizeChanged { size = (it.width / density).toInt() to (it.height / density).toInt() },
                ) {
                    OverlayBubble(
                        ui,
                        onOpen = { overlay.openChat() },
                        onClose = { overlay.closeReply(fleet) },
                        onSendLocked = { overlay.endTalk(send = true) },
                        onCancelLocked = { overlay.endTalk(send = false) },
                        maxWidth = 340.dp,
                    )
                }
            }
        }
    }

    val shown = agent
    if (ui.chatOpen && shown != null) {
        val (x, y) = overlay.chatSpot()
        OverlayWindow(CHAT_TITLE, x, y, CARD_W, CARD_H, focusable = true, onCreated = { w ->
            // A click anywhere else puts the card away, as a tap outside does on a phone.
            w.addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = overlay.closeChat()
            })
        }) {
            themed {
                OverlayChat(
                    fleet, shown, rig, ui, compact = false,
                    onClose = { overlay.closeChat() },
                    onOpenInApp = {
                        overlay.closeChat()
                        onOpenInApp(shown.id)
                    },
                    modifier = Modifier.size(CARD_W.dp, CARD_H.dp),
                    composerChips = {
                        if (ui.screenWithVoice) OverlayGlassesChip(ui, shown.name, onToggle = { overlay.toggleGlasses() })
                    },
                    onPickAgent = { id -> overlay.pickAgent(prefs, fleet, id) },
                )
            }
        }
    }
}

private class Choice(val id: String?)

/**
 * One of the overlay's windows: undecorated, see-through, above every other window, at ([x], [y]) in
 * screen units. Not [focusable] (the head, the bubble): a click never takes the keyboard from the app
 * you are in.
 */
@Composable
private fun OverlayWindow(
    title: String,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    focusable: Boolean,
    onCreated: (ComposeWindow) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Window(
        create = {
            // Its own title: macOS finds it by title, and a bubble just closed can linger under the same one.
            val unique = uniqueOverlayTitle(title)
            ComposeWindow().apply {
                this.title = unique
                // macOS: a non-activating panel, so a click doesn't pull you out of the app you're in.
                if (!focusable) runCatching { type = AwtWindow.Type.POPUP }
                isUndecorated = true
                isTransparent = true
                isAlwaysOnTop = true
                isResizable = false
                focusableWindowState = focusable
                setBounds(x, y, width, height)
                onCreated(this)
                if (isMac) {
                    addWindowListener(object : WindowAdapter() {
                        override fun windowOpened(e: WindowEvent) {
                            MacOverlayNative.floatEverywhere(unique)
                            if (title == BUBBLE_TITLE) {
                                println("[OverlayWindow] $unique opened at ${e.window.x},${e.window.y} ${e.window.width}x${e.window.height}")
                                MacOverlayNative.describe(unique)
                                // And once it has settled, where it is and how big.
                                javax.swing.Timer(600) {
                                    println("[OverlayWindow] $unique settled at ${e.window.x},${e.window.y} ${e.window.width}x${e.window.height}")
                                    MacOverlayNative.describe(unique)
                                }.apply { isRepeats = false }.start()
                            }
                        }
                    })
                }
            }
        },
        dispose = { it.dispose() },
        update = { w ->
            if (w.x != x || w.y != y || w.width != width || w.height != height) w.setBounds(x, y, width, height)
        },
    ) { content() }
}

/** What the overlay's windows share, and what the head's gestures do (the Android service's job). */
@Stable
private class DesktopOverlay(private val scope: CoroutineScope) {
    val ui = OverlayUiState().apply { screenWithVoice = false }
    var agent: OverlayAgent? = null
    var headWindow: ComposeWindow? = null
    private val gesture = CompanionGesture(HOLD_MS, slop = 6f, cancelDistance = 70f, lockDistance = 70f)
    private var holdJob: Job? = null
    private var tapJob: Job? = null
    private var dragFrom = 0 to 0

    // The head's top-left, in screen units: it starts on the left edge of the main screen, above the Dock.
    private val startBox = usableBounds(null)
    private val startSpot = CompanionPlacement.rest(startBox.overlayScreen(), HEAD_BOX.toFloat(), MARGIN.toFloat(), null)
    var headX by mutableIntStateOf(startBox.x + startSpot.x.toInt())
    var headY by mutableIntStateOf(startBox.y + startSpot.y.toInt())

    init {
        measureRoom()
    }

    fun down(x: Int, y: Int, at: Long) {
        tapJob?.cancel()
        ui.pressed = true
        gesture.towardMiddle = if (ui.edge == Edge.Left) 1 else -1
        gesture.doubleTapMs = if (doubleTapWanted(ui.screenWithVoice, ui.chatOpen, asking = false, talking = ui.listening)) DOUBLE_CLICK_MS else 0
        handle(gesture.down(x.toFloat(), y.toFloat(), at))
        holdJob = scope.launch {
            delay(HOLD_MS)
            handle(gesture.hold(System.currentTimeMillis()))
        }
    }

    fun move(x: Int, y: Int, at: Long) = handle(gesture.move(x.toFloat(), y.toFloat(), at))

    fun up(at: Long) {
        holdJob?.cancel()
        handle(gesture.up(at))
        ui.pressed = gesture.tapWaiting
        if (gesture.tapWaiting) {
            tapJob = scope.launch {
                delay(gesture.doubleTapMs)
                ui.pressed = false
                handle(gesture.tapTimeout(System.currentTimeMillis()))
            }
        }
    }

    private fun handle(intents: List<CompanionGesture.Intent>) {
        for (i in intents) when (i) {
            CompanionGesture.Intent.Tap -> if (ui.chatOpen) closeChat() else openChat()
            CompanionGesture.Intent.DoubleTap -> toggleGlasses()
            CompanionGesture.Intent.ListenStart -> {
                closeChat()
                ui.reply = null
                ui.listening = true
                ui.locked = false
                ui.cancelArmed = false
                ui.lockArmed = false
                ui.voice?.start()
                // Wearing its glasses, it takes the screen as you start; without them only your words go.
                if (ui.screenSharing) {
                    glassesIdle?.cancel()
                    snap()
                }
            }
            is CompanionGesture.Intent.ListenSlide -> {
                ui.cancelArmed = i.cancelArmed
                ui.lockArmed = i.lockArmed
            }
            CompanionGesture.Intent.ListenLock -> {
                ui.locked = true
                ui.lockArmed = false
            }
            CompanionGesture.Intent.ListenSend -> endTalk(send = true)
            CompanionGesture.Intent.ListenCancel -> endTalk(send = false)
            CompanionGesture.Intent.DragStart -> {
                closeChat()
                ui.dragging = true
                dragFrom = headX to headY
            }
            is CompanionGesture.Intent.DragTo -> {
                headX = dragFrom.first + i.dx.toInt()
                headY = dragFrom.second + i.dy.toInt()
                headWindow?.setLocation(headX, headY)
            }
            CompanionGesture.Intent.DragEnd -> {
                ui.dragging = false
                snapToEdge()
            }
        }
    }

    // ── the hold-to-talk key, from any app: a combo (no permission), or right ⌥ held on its own ──

    private val keyHold = TalkKeyHold(HOLD_MS)
    private var keyTick: Job? = null
    private var keyTap: MacTalkKeyTap? = null
    private var hotKey: MacHotKey? = null
    /** The combo started the talk going on, at this time. */
    private var comboFrom: Long? = null
    private var comboLabel = ""

    /**
     * Listens for [key] from any app, in place of the one before. A combo is macOS's to hand over, no
     * permission asked; right ⌥ alone needs Input Monitoring (allowed from Settings), so it waits for that.
     */
    suspend fun useTalkKey(key: TalkKey) {
        stopTalkKey()
        if (!isMac) return
        if (key.combo) {
            comboLabel = key.label
            val combo = hotKey ?: MacHotKey(
                // AppKit's thread: everything goes on to the overlay's.
                onPress = { scope.launch { comboDown() } },
                onRelease = { scope.launch { comboUp() } },
            ).also { hotKey = it }
            combo.register(key.carbonKey, key.carbonModifiers)
            return
        }
        while (!MacOverlayNative.canListenToKeys()) delay(2_000)
        val tap = MacTalkKeyTap(
            // The tap's own thread: everything goes on to the overlay's.
            onKey = { down -> scope.launch { onTalkKey(down) } },
            onOther = { scope.launch { keyOutputs(keyHold.otherKey(System.currentTimeMillis())) } },
        )
        if (tap.start()) keyTap = tap
    }

    fun stopTalkKey() {
        hotKey?.unregister()
        keyTap?.stop()
        keyTap = null
        keyTick?.cancel()
    }

    // The combo talks from the moment it's down: nothing to tell apart from typing, so no wait.
    private fun comboDown() {
        println("[OverlayKey] $comboLabel down")
        if (ui.listening) return
        comboFrom = System.currentTimeMillis()
        handle(listOf(CompanionGesture.Intent.ListenStart))
    }

    private fun comboUp() {
        val from = comboFrom
        if (from == null) {
            println("[OverlayKey] $comboLabel up")
            return
        }
        comboFrom = null
        val held = System.currentTimeMillis() - from
        // Let go of at once, it was a slip, not a talk.
        val send = held >= HOLD_MS
        println("[OverlayKey] $comboLabel up after $held ms: ${if (send) "sending" else "a slip, dropped"}")
        endTalk(send = send)
    }

    private fun onTalkKey(down: Boolean) {
        val at = System.currentTimeMillis()
        if (down) {
            keyOutputs(keyHold.keyDown(at))
            keyTick?.cancel()
            keyTick = scope.launch {
                delay(HOLD_MS)
                keyOutputs(keyHold.tick(System.currentTimeMillis()))
            }
        } else {
            keyTick?.cancel()
            keyOutputs(keyHold.keyUp(at))
        }
    }

    private fun keyOutputs(outs: List<TalkKeyHold.Out>) {
        for (o in outs) when (o) {
            TalkKeyHold.Out.Start -> if (!ui.listening) handle(listOf(CompanionGesture.Intent.ListenStart))
            TalkKeyHold.Out.Send -> endTalk(send = true)
            TalkKeyHold.Out.Cancel -> endTalk(send = false)
        }
    }

    fun endTalk(send: Boolean) {
        if (!ui.listening) return
        ui.listening = false
        ui.locked = false
        ui.cancelArmed = false
        ui.lockArmed = false
        if (!send) ui.shot = null
        if (send) ui.voice?.send() else ui.voice?.cancel()
        restartGlassesIdle()
    }

    // ── its glasses: while it wears them, your screen goes with what you say ──

    private var glassesIdle: Job? = null

    /** Settings' master switch: off takes the glasses off, and a double-click does nothing. */
    fun allowScreen(allowed: Boolean) {
        ui.screenWithVoice = allowed
        if (!allowed && ui.screenSharing) glassesOff()
    }

    /** A double-click, or the chip in its chat. macOS asks for Screen Recording first; a "no" leaves them off. */
    fun toggleGlasses() {
        if (ui.screenSharing) {
            glassesOff()
            return
        }
        if (!ui.screenWithVoice) return
        if (isMac && !MacOverlayNative.canRecordScreen()) {
            // macOS's own prompt the first time; after a "no", its settings page.
            if (!MacOverlayNative.askToRecordScreen()) MacOverlayNative.openPrivacySettings("ScreenCapture")
            return
        }
        ui.screenSharing = true
        restartGlassesIdle()
    }

    private fun glassesOff() {
        ui.screenSharing = false
        glassesIdle?.cancel()
    }

    private fun restartGlassesIdle() {
        glassesIdle?.cancel()
        if (ui.screenSharing) glassesIdle = scope.launch {
            delay(GLASSES_IDLE_MS)
            glassesOff()
        }
    }

    /**
     * Takes the screen the head is on for this talk: a glint across the head, and the picture goes with
     * your words once you let go. The overlay's own windows are kept out of it natively (macOS sharingType).
     */
    private fun snap() {
        val bounds = screenBounds(headX + HEAD_BOX / 2 to headY + HEAD_BOX / 2)
        scope.launch {
            val shot = withContext(Dispatchers.IO) { grabScreen(bounds) } ?: return@launch
            if (!ui.listening) return@launch
            ui.shots++
            val thumb = withContext(Dispatchers.Default) { shot.scaled(240).toComposeImageBitmap() }
            // Ready by the time you let go; it only goes up if you send.
            val png = scope.async(Dispatchers.Default) { runCatching { shot.png(SHARE_MAX_EDGE) }.getOrNull() }
            ui.shot = OverlayShot(thumb, png)
        }
    }

    fun openChat() {
        if (agent == null || ui.chatOpen) return
        ui.reply = null
        ui.chatOpen = true
    }

    fun closeChat() {
        ui.chatOpen = false
    }

    fun closeReply(fleet: FleetStore) {
        ui.replyFolded = true
        ui.replyExpanded = false
        val id = agent?.id ?: return
        fleet.markRead(id)
        fleet.markReadOnServer(listOf(id))
    }

    fun pickAgent(prefs: UiPrefs, fleet: FleetStore, id: String) {
        val defaultId = fleet.sessions.value.firstOrNull { it.role == "personal_assistant" && it.isDefault == true }?.id
        ui.reply = null
        ui.thinking = false
        scope.launch { prefs.putAssistantOverlayAgent(overlayChoiceToStore(id, defaultId)) }
    }

    /** Lets go of a drag at the nearest side edge of the screen it was dropped on, gliding there. */
    private fun snapToEdge() {
        val box = usableBounds(headX + HEAD_BOX / 2 to headY + HEAD_BOX / 2)
        val screen = box.overlayScreen()
        val spot = CompanionPlacement.snap(screen, HEAD_BOX.toFloat(), MARGIN.toFloat(), (headX - box.x).toFloat(), (headY - box.y).toFloat())
        ui.edge = spot.edge
        val rest = CompanionPlacement.rest(screen, HEAD_BOX.toFloat(), MARGIN.toFloat(), spot)
        val fromX = headX
        val fromY = headY
        val toX = box.x + rest.x.toInt()
        val toY = box.y + rest.y.toInt()
        scope.launch {
            for (step in 1..10) {
                val k = 1f - (1f - step / 10f) * (1f - step / 10f)
                headX = fromX + ((toX - fromX) * k).toInt()
                headY = fromY + ((toY - fromY) * k).toInt()
                headWindow?.setLocation(headX, headY)
                delay(16)
            }
            measureRoom()
        }
    }

    private fun space() = usableBounds(headX + HEAD_BOX / 2 to headY + HEAD_BOX / 2).let { box ->
        bubbleSpace((headY - box.y).toFloat(), HEAD_BOX.toFloat(), box.height.toFloat(), 0f, 0f, GAP.toFloat(), MARGIN.toFloat())
    }

    /** How tall the bubble may grow from where the head now rests ("Read it all"). */
    private fun measureRoom() {
        ui.bubbleRoom = space().room.dp
    }

    /** The bubble's top-left: above the head (below it when the head is high), on the head's side. */
    fun bubbleSpot(width: Int, height: Int): Pair<Int, Int> {
        val below = space().below
        val x = if (ui.edge == Edge.Left) headX else headX + HEAD_BOX - width
        val y = if (below) headY + HEAD_BOX + GAP else headY - GAP - height
        return x to y
    }

    /** The chat card's top-left: beside the head, toward the middle of its screen. */
    fun chatSpot(): Pair<Int, Int> {
        val box = usableBounds(headX + HEAD_BOX / 2 to headY + HEAD_BOX / 2)
        val p = chatCardSpot(
            (headX - box.x).toFloat(), (headY - box.y).toFloat(), HEAD_BOX.toFloat(), CARD_W.toFloat(), CARD_H.toFloat(),
            box.overlayScreen(), GAP.toFloat(),
        )
        return box.x + p.x.toInt() to box.y + p.y.toInt()
    }
}

/** The whole of the screen holding [point], in screen units. */
private fun screenBounds(point: Pair<Int, Int>): Rectangle {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val device = env.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(point.first, point.second) } ?: env.defaultScreenDevice
    return device.defaultConfiguration.bounds
}

/** The screen in [bounds] at its full resolution (a Retina screen's pixels, not its points); null when refused. */
private fun grabScreen(bounds: Rectangle): BufferedImage? = runCatching {
    val image = Robot().createMultiResolutionScreenCapture(bounds)
    val best = image.resolutionVariants.maxByOrNull { it.getWidth(null) } ?: return@runCatching null
    if (best is BufferedImage) best else BufferedImage(best.getWidth(null), best.getHeight(null), BufferedImage.TYPE_INT_RGB).also {
        it.createGraphics().apply { drawImage(best, 0, 0, null); dispose() }
    }
}.getOrNull()

private fun BufferedImage.scaled(maxEdge: Int): BufferedImage {
    val (w, h) = shareImageSize(width, height, maxEdge)
    if (w == width && h == height) return this
    return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also {
        it.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(this@scaled, 0, 0, w, h, null)
            dispose()
        }
    }
}

private fun BufferedImage.png(maxEdge: Int): ByteArray = ByteArrayOutputStream().use { out ->
    ImageIO.write(scaled(maxEdge), "png", out)
    out.toByteArray()
}

/** The usable area (less the menu bar, the Dock or the taskbar) of the screen holding [point]; the main one for null. */
private fun usableBounds(point: Pair<Int, Int>?): Rectangle {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val device = point?.let { (px, py) -> env.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(px, py) } }
        ?: env.defaultScreenDevice
    val gc = device.defaultConfiguration
    val b = gc.bounds
    val inset = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return Rectangle(b.x + inset.left, b.y + inset.top, b.width - inset.left - inset.right, b.height - inset.top - inset.bottom)
}

private fun Rectangle.overlayScreen() = OverlayScreen(width.toFloat(), height.toFloat(), topInset = 0f, bottomInset = 0f, keyboardHeight = 0f)
