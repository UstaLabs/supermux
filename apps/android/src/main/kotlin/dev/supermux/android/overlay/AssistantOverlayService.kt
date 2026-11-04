package dev.supermux.android.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.supermux.android.AppFleet
import dev.supermux.android.MainActivity
import dev.supermux.android.platform.AndroidHaptics
import dev.supermux.android.platform.AndroidPlatform
import dev.supermux.android.platform.PickerHost
import dev.supermux.android.platform.QrScanHost
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.widthClassFor
import dev.supermux.ui.overlay.CompanionGesture
import dev.supermux.ui.overlay.CompanionPlacement
import dev.supermux.ui.overlay.Edge
import dev.supermux.ui.overlay.OverlayAgent
import dev.supermux.ui.overlay.OverlayBubble
import dev.supermux.ui.overlay.OverlayChat
import dev.supermux.ui.overlay.OverlayDropTarget
import dev.supermux.ui.overlay.OverlayHead
import dev.supermux.ui.overlay.OverlayReplies
import dev.supermux.ui.overlay.OverlayScreen
import dev.supermux.ui.overlay.OverlayScrim
import dev.supermux.ui.overlay.OverlayUiState
import dev.supermux.ui.overlay.OverlayVoice
import dev.supermux.ui.overlay.Spot
import dev.supermux.ui.overlay.rememberOverlayAgent
import dev.supermux.ui.overlay.rememberOverlayRig
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.HostTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The assistant overlay on Android (#62): the default Agent as a chat head over other apps.
 *
 * A foreground service, because only a running service can keep windows of `TYPE_APPLICATION_OVERLAY`
 * ("Display over other apps") on screen while supermux itself is gone; it holds the process's
 * [AppFleet], so it shares the main window's connection instead of opening its own. It owns small
 * windows sized to their content, never one full-screen pass-through window (Android 12+ blocks
 * touches through other apps' overlays):
 * - the **head**: the living avatar; touch goes through [CompanionGesture] (tap, hold to talk, drag);
 * - the **bubble**: the reply typing out, the words while you talk, the lock's Send / Cancel;
 * - the **✕**: shown while dragging; dropping the head on it hides it until the next reply;
 * - the **chat**: a sheet with a scrim on a phone, a card beside the head on a wide screen.
 *
 * Started and stopped by [MainActivity] from the Appearance switch ([sync]); it never starts itself.
 */
class AssistantOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        private const val CHANNEL_ID = "assistant_overlay"
        private const val NOTIFICATION_ID = 0x0062
        private const val EXTRA_FOREGROUND = "dev.supermux.overlay.foreground"
        private const val ACTION_SHOW = "dev.supermux.overlay.SHOW"
        private const val ACTION_HIDE = "dev.supermux.overlay.HIDE"
        private const val HOLD_MS = 320L
        private const val HEAD_DP = 56
        private const val KEYBOARD_DP = 290
        private const val MARGIN_DP = 12

        /** The Agent whose head is out right now, or null. The push service skips its notifications. */
        @Volatile
        var showingAgentId: String? = null
            private set

        /**
         * Starts or stops the overlay to match the Appearance switch. Called while supermux is on
         * screen: only a service created in the foreground may use the mic from the background
         * (Android 14+), which hold-to-talk needs.
         */
        fun sync(context: Context, wanted: Boolean) {
            val intent = Intent(context, AssistantOverlayService::class.java)
            if (!wanted || !Settings.canDrawOverlays(context)) {
                context.stopService(intent)
                return
            }
            runCatching { ContextCompat.startForegroundService(context, intent.putExtra(EXTRA_FOREGROUND, true)) }
        }
    }

    // ── a lifecycle of its own, so Compose can live in windows outside an activity ──
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private lateinit var shared: AppFleet.Shared
    private lateinit var windowContext: Context
    private lateinit var wm: WindowManager
    private val ui = OverlayUiState()
    private val main = Handler(Looper.getMainLooper())
    /** The default Agent, as the head's composition last saw it (snapshot state: the watchers read it). */
    private var agent by mutableStateOf<OverlayAgent?>(null)

    private lateinit var head: OverlayWindow
    private lateinit var bubble: OverlayWindow
    private lateinit var drop: OverlayWindow
    private var chat: OverlayWindow? = null

    private var spot: Spot? = null
    private val gesture by lazy { CompanionGesture(HOLD_MS, slop = px(8), cancelDistance = px(70), lockDistance = px(70)) }
    private var dragFrom = 0 to 0
    private val hold = Runnable { handle(gesture.hold(SystemClock.uptimeMillis())) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        wm = windowContext.getSystemService(WindowManager::class.java)
        shared = AppFleet.acquire(this, this)
        startInForeground(fromForeground = false)

        head = OverlayWindow(headParams(), ownTouch = true) { Head() }
        bubble = OverlayWindow(bubbleParams()) { Bubble() }
        drop = OverlayWindow(dropParams()) { OverlayDropTarget(hot = ui.dropHot) }
        head.view.setOnTouchListener(::onHeadTouch)
        head.show()
        windowContext.registerComponentCallbacks(screenChanges)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        watch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> ui.hidden = true
            ACTION_SHOW -> ui.hidden = false
            else -> if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) startInForeground(fromForeground = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        showingAgentId = null
        main.removeCallbacks(hold)
        runCatching { windowContext.unregisterComponentCallbacks(screenChanges) }
        closeChat()
        listOf(head, bubble, drop).forEach { it.remove() }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        AppFleet.release(this)
        super.onDestroy()
    }

    // ── the foreground notification: quiet, with Show/Hide ──

    private fun startInForeground(fromForeground: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Assistant overlay", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps your assistant floating over other apps."
                    setShowBadge(false)
                },
            )
        }
        // The mic type only when this start came from the app on screen: Android 14+ refuses it from
        // the background, and without it hold-to-talk cannot record once supermux is gone.
        val micAllowed = fromForeground &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val mic = if (micAllowed && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val notification = notification()
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special or mic) }
            .recoverCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special) }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, AssistantOverlayService::class.java).setAction(if (ui.hidden) ACTION_SHOW else ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = currentAgent?.name ?: "Your assistant"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (ui.hidden) "$name is resting" else "$name is in your corner")
            .setContentText(if (ui.hidden) "It comes back with its next reply." else "Tap it to chat, hold it to talk.")
            .setContentIntent(open)
            .addAction(0, if (ui.hidden) "Show" else "Hide", toggle)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ── windows ──

    private inner class OverlayWindow(
        val params: WindowManager.LayoutParams,
        private val ownTouch: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val view: FrameLayout = object : FrameLayout(windowContext) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = ownTouch || super.onInterceptTouchEvent(ev)

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && chat?.view === this) {
                    if (event.action == KeyEvent.ACTION_UP) closeChat()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        var attached = false
            private set

        init {
            view.setViewTreeLifecycleOwner(this@AssistantOverlayService)
            view.setViewTreeSavedStateRegistryOwner(this@AssistantOverlayService)
            view.setViewTreeViewModelStoreOwner(this@AssistantOverlayService)
            view.addView(
                ComposeView(windowContext).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@AssistantOverlayService))
                    setContent { OverlayTheme { content() } }
                },
            )
        }

        fun show() { if (!attached) runCatching { wm.addView(view, params); attached = true } }
        fun update() { if (attached) runCatching { wm.updateViewLayout(view, params) } }
        fun remove() { if (attached) runCatching { wm.removeViewImmediate(view) }; attached = false }
    }

    private fun baseParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun headParams() = baseParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val p = CompanionPlacement.rest(screen(), headPx(), px(MARGIN_DP), spot)
        x = p.x.toInt() - px(4).toInt()
        y = p.y.toInt() - px(4).toInt()
    }

    private fun bubbleParams() = baseParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    )

    private fun dropParams() = baseParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val (cx, cy) = CompanionPlacement.dismissCenter(screen(), px(64))
        x = (cx - px(40)).toInt()
        y = (cy - px(40)).toInt()
    }

    /** The bubble sits just above the head on its edge, or below it when the head is near the top. */
    private fun placeBubble() {
        val s = screen()
        val p = bubble.params
        val hp = head.params
        val headTop = hp.y + px(4).toInt()
        val below = headTop < s.height * 0.3f
        val edge = if (ui.edge == Edge.Left) Gravity.START else Gravity.END
        p.gravity = (if (below) Gravity.TOP else Gravity.BOTTOM) or edge
        p.x = px(MARGIN_DP).toInt()
        p.y = if (below) headTop + headPx().toInt() + px(6).toInt() else s.height.toInt() - headTop + px(4).toInt()
        val touchable = ui.locked || (!ui.listening && !ui.finishing && ui.reply != null)
        p.flags = if (touchable) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        bubble.update()
    }

    // ── what the windows draw ──

    private val currentAgent: OverlayAgent? get() = agent

    @Composable
    private fun OverlayTheme(content: @Composable () -> Unit) {
        val view = LocalView.current
        val platform = remember(view) { AndroidPlatform(windowContext, PickerHost(), QrScanHost(), AndroidHaptics(view)) }
        val appearance by shared.uiPrefs.appearance(AppearanceMode.SYSTEM).collectAsState(AppearanceMode.SYSTEM)
        val widthDp = windowContext.resources.configuration.screenWidthDp
        HostTheme(
            platform = platform,
            appearance = appearance,
            uiPrefs = shared.uiPrefs,
            inputMode = InputMode.Touch,
            pointerAvailable = false,
            hardwareKeyboard = false,
            widthClass = widthClassFor(widthDp),
            content = content,
        )
    }

    @Composable
    private fun Head() {
        val fleet = shared.fleet
        val seen = rememberOverlayAgent(fleet)
        SideEffect { agent = seen }
        val rig = rememberOverlayRig(fleet, seen?.id)
        OverlayReplies(fleet, seen, ui)
        seen?.let { OverlayVoice(fleet, it.id, ui) }
        OverlayHead(seen, rig, ui, size = HEAD_DP.dp)
    }

    @Composable
    private fun Bubble() {
        val maxWidth = (windowContext.resources.configuration.screenWidthDp - MARGIN_DP * 2 - 8).coerceAtMost(340).dp
        OverlayBubble(
            ui,
            onOpen = { openChat() },
            onSendLocked = { endTalk(send = true) },
            onCancelLocked = { endTalk(send = false) },
            maxWidth = maxWidth,
        )
    }

    // ── keep the windows in step with what is happening ──

    private fun watch() {
        lifecycleScope.launch {
            snapshotFlow { Triple(ui.bubbleVisible, ui.locked, ui.reply != null && !ui.listening && !ui.finishing) }
                .distinctUntilChanged()
                .collect { (visible, _, _) ->
                    if (visible) { placeBubble(); bubble.show() } else bubble.remove()
                }
        }
        lifecycleScope.launch {
            snapshotFlow { ui.hidden }.distinctUntilChanged().collect { hidden ->
                head.view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
                head.params.flags = if (hidden) head.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else head.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                head.update()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        }
        lifecycleScope.launch {
            // Its notifications are skipped only while the head is out AND its host is connected, so
            // the reply surely reaches the bubble; otherwise the push still comes.
            snapshotFlow { agent?.takeIf { it.connected && !ui.hidden }?.id }
                .distinctUntilChanged()
                .collect { showingAgentId = it }
        }
        lifecycleScope.launch {
            // A reply that arrives while it is hidden brings it back, bubble first.
            snapshotFlow { ui.reply?.id }.distinctUntilChanged().collect { if (it != null && ui.hidden) ui.hidden = false }
        }
    }

    // ── touch on the head ──

    @SuppressLint("ClickableViewAccessibility")
    private fun onHeadTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ui.pressed = true
                gesture.towardMiddle = if (ui.edge == Edge.Left) 1 else -1
                handle(gesture.down(e.rawX, e.rawY, e.eventTime))
                main.postDelayed(hold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> handle(gesture.move(e.rawX, e.rawY, e.eventTime))
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(hold)
                ui.pressed = false
                handle(gesture.up(e.eventTime))
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(hold)
                ui.pressed = false
                handle(gesture.cancel())
            }
        }
        return true
    }

    private fun handle(intents: List<CompanionGesture.Intent>) {
        for (i in intents) when (i) {
            CompanionGesture.Intent.Tap -> if (ui.chatOpen) closeChat() else openChat()
            CompanionGesture.Intent.ListenStart -> {
                haptic(HapticFeedbackConstants.LONG_PRESS)
                closeChat()
                ui.reply = null
                ui.listening = true
                ui.locked = false
                ui.cancelArmed = false
                ui.lockArmed = false
                ui.voice?.start()
            }
            is CompanionGesture.Intent.ListenSlide -> {
                if (i.cancelArmed && !ui.cancelArmed) haptic(HapticFeedbackConstants.CLOCK_TICK)
                ui.cancelArmed = i.cancelArmed
                ui.lockArmed = i.lockArmed
            }
            CompanionGesture.Intent.ListenLock -> {
                haptic(HapticFeedbackConstants.CLOCK_TICK)
                ui.locked = true
                ui.lockArmed = false
                placeBubble()
            }
            CompanionGesture.Intent.ListenSend -> endTalk(send = true)
            CompanionGesture.Intent.ListenCancel -> endTalk(send = false)
            CompanionGesture.Intent.DragStart -> {
                closeChat()
                ui.dragging = true
                dragFrom = head.params.x to head.params.y
                drop.params.apply {
                    val (cx, cy) = CompanionPlacement.dismissCenter(screen(), px(64))
                    x = (cx - px(40)).toInt(); y = (cy - px(40)).toInt()
                }
                drop.show()
            }
            is CompanionGesture.Intent.DragTo -> {
                head.params.x = dragFrom.first + i.dx.toInt()
                head.params.y = dragFrom.second + i.dy.toInt()
                head.update()
                val half = px(4) + headPx() / 2
                val hot = CompanionPlacement.overDismiss(screen(), head.params.x + half, head.params.y + half, px(64), px(56))
                if (hot && !ui.dropHot) haptic(HapticFeedbackConstants.CLOCK_TICK)
                ui.dropHot = hot
            }
            CompanionGesture.Intent.DragEnd -> {
                ui.dragging = false
                drop.remove()
                if (ui.dropHot) {
                    ui.dropHot = false
                    ui.hidden = true
                    snapTo(spot)
                } else {
                    val s = CompanionPlacement.snap(screen(), headPx(), px(MARGIN_DP), head.params.x + px(4), head.params.y + px(4))
                    spot = s
                    ui.edge = s.edge
                    snapTo(s)
                }
            }
        }
    }

    private fun endTalk(send: Boolean) {
        if (!ui.listening) return
        haptic(
            when {
                Build.VERSION.SDK_INT < 30 -> HapticFeedbackConstants.VIRTUAL_KEY
                send -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.REJECT
            },
        )
        ui.listening = false
        ui.locked = false
        ui.cancelArmed = false
        ui.lockArmed = false
        if (send) ui.voice?.send() else ui.voice?.cancel()
    }

    private fun snapTo(s: Spot?) {
        val p = CompanionPlacement.rest(screen(), headPx(), px(MARGIN_DP), s)
        animateHead(p.x.toInt() - px(4).toInt(), p.y.toInt() - px(4).toInt())
    }

    private fun animateHead(toX: Int, toY: Int) {
        val fromX = head.params.x
        val fromY = head.params.y
        val start = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - start) / 260f).coerceAtMost(1f)
                val k = 1f - (1f - t) * (1f - t) * (1f - t)
                head.params.x = (fromX + (toX - fromX) * k).toInt()
                head.params.y = (fromY + (toY - fromY) * k).toInt()
                head.update()
                if (t < 1f) main.postDelayed(this, 16)
            }
        }
        main.post(step)
    }

    // ── the chat ──

    private fun compact() = windowContext.resources.configuration.screenWidthDp < 600

    private fun openChat() {
        val agent = currentAgent ?: return
        if (chat != null) return
        ui.chatOpen = true
        ui.reply = null
        val compact = compact()
        val params = if (compact) {
            baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0).apply {
                gravity = Gravity.TOP or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        } else {
            baseParams(
                px(380).toInt(), px(560).toInt(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                val s = screen()
                val hx = head.params.x
                val hy = head.params.y
                x = if (ui.edge == Edge.Left) hx + headPx().toInt() + px(16).toInt() else hx - px(380).toInt() - px(8).toInt()
                y = (hy + headPx().toInt() - px(560).toInt()).coerceIn((s.topInset + px(8)).toInt(), (s.height - s.bottomInset - px(560) - px(8)).toInt().coerceAtLeast(0))
            }
        }
        val window = OverlayWindow(params) { Chat(compact) }
        if (!compact) {
            window.view.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closeChat()
                false
            }
        }
        chat = window
        if (compact) head.view.visibility = View.INVISIBLE
        window.show()
    }

    private fun closeChat() {
        val window = chat ?: return
        chat = null
        ui.chatOpen = false
        window.remove()
        if (!ui.hidden) head.view.visibility = View.VISIBLE
    }

    @Composable
    private fun Chat(compact: Boolean) {
        val fleet = shared.fleet
        val agent = currentAgent ?: return
        val rig = rememberOverlayRig(fleet, agent.id)
        if (compact) {
            val screenH = windowContext.resources.configuration.screenHeightDp
            Box(Modifier.fillMaxSize()) {
                OverlayScrim(onTap = { closeChat() })
                OverlayChat(
                    fleet, agent, rig, ui, compact = true,
                    onClose = { closeChat() },
                    onOpenInApp = { openInApp(agent.id) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = (screenH * 0.66f).dp).fillMaxHeight(),
                )
            }
        } else {
            OverlayChat(
                fleet, agent, rig, ui, compact = false,
                onClose = { closeChat() },
                onOpenInApp = { openInApp(agent.id) },
                modifier = Modifier.size(380.dp, 560.dp),
            )
        }
    }

    private fun openInApp(sessionId: String) {
        closeChat()
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(SupermuxMessagingService.EXTRA_SESSION_ID, sessionId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    // ── the screen it lives on ──

    private val screenChanges = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // Folded ↔ open, or rotated: re-seat everything for the new screen.
            closeChat()
            snapTo(spot)
            drop.remove()
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    private fun screen(): OverlayScreen {
        val keyboard = px(KEYBOARD_DP)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = wm.currentWindowMetrics
            val bars = m.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.displayCutout(),
            )
            OverlayScreen(m.bounds.width().toFloat(), m.bounds.height().toFloat(), bars.top.toFloat(), bars.bottom.toFloat(), keyboard)
        } else {
            val dm = windowContext.resources.displayMetrics
            OverlayScreen(dm.widthPixels.toFloat(), dm.heightPixels.toFloat(), px(24), px(48), keyboard)
        }
    }

    private fun px(dp: Int): Float = dp * windowContext.resources.displayMetrics.density
    private fun headPx(): Float = px(HEAD_DP)

    private fun haptic(kind: Int) {
        head.view.performHapticFeedback(kind)
    }
}
