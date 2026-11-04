package dev.supermux.android.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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
import androidx.compose.runtime.key
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
import dev.supermux.android.platform.ANDROID_CAPS
import dev.supermux.android.platform.AndroidHaptics
import dev.supermux.android.platform.AndroidNotices
import dev.supermux.android.platform.AndroidPlatform
import dev.supermux.android.platform.PickerHost
import dev.supermux.android.platform.QrScanHost
import dev.supermux.android.platform.pickedFileFromUri
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.widthClassFor
import dev.supermux.ui.chat.ComposerExternalAttach
import dev.supermux.ui.overlay.CompanionGesture
import dev.supermux.ui.overlay.CompanionPlacement
import dev.supermux.ui.overlay.Edge
import dev.supermux.ui.overlay.LookBlock
import dev.supermux.ui.overlay.LookRect
import dev.supermux.ui.overlay.LumaGrid
import dev.supermux.ui.overlay.OVERLAY_DROP_BOX
import dev.supermux.ui.overlay.OverlayAgent
import dev.supermux.ui.overlay.OverlayBubble
import dev.supermux.ui.overlay.OverlayChat
import dev.supermux.ui.overlay.OverlayDropTarget
import dev.supermux.ui.overlay.OverlayHead
import dev.supermux.ui.overlay.OverlayLookChips
import dev.supermux.ui.overlay.OverlayLookFrame
import dev.supermux.ui.overlay.OverlayReplies
import dev.supermux.ui.overlay.OverlayScreen
import dev.supermux.ui.overlay.OverlayScrim
import dev.supermux.ui.overlay.OverlayUndo
import dev.supermux.ui.overlay.OverlayUiState
import dev.supermux.ui.overlay.OverlayVoice
import dev.supermux.ui.overlay.Spot
import dev.supermux.ui.overlay.rememberOverlayAgent
import dev.supermux.ui.overlay.overlayChoiceToStore
import dev.supermux.ui.overlay.rememberOverlayRig
import dev.supermux.ui.overlay.shareImageSize
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.HostTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
 * - the **undo**: "hidden · Undo" for a few seconds after that;
 * - the **chat**: a sheet with a scrim on a phone, a card beside the head on a wide screen.
 *
 * Looks: drag the head onto something and hold still, and a frame shows what letting go takes; that part
 * of the screen lands in the chat's composer for the user to send ([armLook], [Look]).
 *
 * What only an activity may ask for (the screen-capture consent behind looks, the pickers behind the
 * chat's attach button) goes through the invisible [OverlayBridgeActivity]. The
 * chat steps out of its way meanwhile ([goAway]); the head stays, and tapping it drops a request the
 * user walked away from ([cancelBridge]).
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
        private const val ACTION_STOP_LOOK = "dev.supermux.overlay.STOP_LOOK"
        /** How long a drag holds still before letting go looks rather than moves. */
        private const val LOOK_MS = 450L
        /** Logcat tag for the screen-sharing session (`adb logcat -s SupermuxLook`): what it costs and why it ended. */
        private const val LOOK_LOG = "SupermuxLook"
        /** On screen off, how long Android gets to end the session itself (15 QPR1+) before we do. */
        private const val SCREEN_OFF_GRACE_MS = 2_000L
        private const val HOLD_MS = 320L
        private const val HEAD_DP = 56
        private const val KEYBOARD_DP = 290
        private const val MARGIN_DP = 12
        private const val UNDO_MS = 4_000L
        private const val SHARE_MAX_EDGE = 1600
        /** How long the bridge may take to come up before the overlay stops waiting for it. */
        private const val BRIDGE_WAIT_MS = 2_500L
        /** How long a picked file's kept read grant lives: ample for its upload. */
        private const val GRANT_KEEP_MS = 60 * 60_000L

        /** The overlay's windows have no camera to capture with and no save dialog. */
        private val OVERLAY_CAPS = ANDROID_CAPS.copy(camera = false, saveAs = false)

        /** The running overlay, for [OverlayBridgeActivity]'s answers (same process, main thread). */
        @SuppressLint("StaticFieldLeak")
        private var running: AssistantOverlayService? = null

        // The bridge's answers carry the request they answer: one the overlay has given up on is ignored.
        /** The bridge is up. False: its request was given up on, so it should just go. */
        internal fun bridgeOpened(request: Int, activity: Activity): Boolean = running?.onBridgeOpened(request, activity) ?: false
        internal fun shareAllowed(request: Int, resultCode: Int, data: Intent) { running?.ifAsked(request)?.onShareAllowed(resultCode, data) }
        internal fun shareRefused(request: Int) { running?.ifAsked(request)?.lookRefused() }
        internal fun picked(request: Int, uris: List<Uri>) { running?.ifAsked(request)?.onPicked(uris) }

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
    private var chatCompact = false
    private var undo: OverlayWindow? = null
    private val undoGone = Runnable { dropUndo() }

    /** The chat's attach button picks through the bridge ([OverlayBridgeActivity]). */
    private val picker = PickerHost<List<Uri>>()
    /**
     * A request is out with the bridge (the capture consent, a picker): the chat steps out of its way.
     * The head stays, so a picker the user walked away from is one tap from being dropped ([cancelBridge]).
     */
    private var away = false
    private var sharing = false
    /** The bridge's current request, and the bridge once it is up. */
    private var bridgeId = 0
    private var bridge: WeakReference<Activity>? = null
    private val bridgeLost = Runnable { cancelBridge(failed = true) }
    /** Read grants kept for files picked through the bridge, given back after [GRANT_KEEP_MS]. */
    private val keptGrants = mutableSetOf<Uri>()

    private var spot: Spot? = null
    private val gesture by lazy {
        CompanionGesture(HOLD_MS, slop = px(8), cancelDistance = px(70), lockDistance = px(70), lookMs = LOOK_MS, lookSlop = px(16))
    }
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
        running = this
        picker.onLaunch = { kind -> if (!runBridge(OverlayBridgeActivity.pick(this, kind))) picker.deliver(null) }
        shared = AppFleet.acquire(this, this)
        startInForeground(fromForeground = false)

        head = OverlayWindow(headParams(), ownTouch = true) { Head() }
        bubble = OverlayWindow(bubbleParams()) { Bubble() }
        drop = OverlayWindow(dropParams()) { OverlayDropTarget(hot = ui.dropHot) }
        head.view.setOnTouchListener(::onHeadTouch)
        head.show()
        windowContext.registerComponentCallbacks(screenChanges)
        // Screen sharing for looks ends when the screen goes off, on every Android (15+ does it too).
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        watch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> ui.hidden = true
            ACTION_SHOW -> {
                cancelBridge()
                ui.hidden = false
            }
            ACTION_STOP_LOOK -> look?.let { endLook(it, "Stop sharing in the notification") }
            else -> if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) startInForeground(fromForeground = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        showingAgentId = null
        if (running === this) running = null
        picker.onLaunch = null
        main.removeCallbacks(hold)
        main.removeCallbacks(bridgeLost)
        main.removeCallbacks(dwell)
        runCatching { unregisterReceiver(screenOff) }
        hideFrame()
        look?.let { endLook(it, "the overlay was turned off") }
        bridgeId++
        bridge?.get()?.let { runCatching { it.finishAndRemoveTask() } }
        releaseGrants(keptGrants)
        dropUndo()
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (ui.hidden) "$name is resting" else "$name is in your corner")
            .setContentText(
                when {
                    ui.lookSharing -> "Screen sharing is on for looks."
                    ui.hidden -> "It comes back with its next reply."
                    else -> "Tap it to chat, hold it to talk."
                },
            )
            .setContentIntent(open)
        if (ui.lookSharing) {
            val stop = PendingIntent.getService(
                this, 2,
                Intent(this, AssistantOverlayService::class.java).setAction(ACTION_STOP_LOOK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop sharing", stop)
        }
        return builder
            .addAction(0, if (ui.hidden) "Show" else "Hide", toggle)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ── windows ──

    /**
     * One overlay window with its own composition. The lasting ones (head, bubble, ✕) keep theirs while
     * the service runs, across hide and show; a [oneOff] (a chat, an undo) lets go of it when removed.
     */
    private inner class OverlayWindow(
        val params: WindowManager.LayoutParams,
        private val ownTouch: Boolean = false,
        private val oneOff: Boolean = false,
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
                    setViewCompositionStrategy(
                        if (oneOff) ViewCompositionStrategy.DisposeOnDetachedFromWindow
                        else ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@AssistantOverlayService),
                    )
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
        x = (cx - dropHalf()).toInt()
        y = (cy - dropHalf()).toInt()
    }

    private fun undoParams() = baseParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (screen().bottomInset + px(8)).toInt()
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
        val platform = remember(view) { AndroidPlatform(windowContext, picker, QrScanHost(), AndroidHaptics(view), caps = OVERLAY_CAPS) }
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

    /** This device's pick of Agent (picked in the chat's header), wrapped so "not read yet" differs from "none". */
    private class Choice(val id: String?)
    private val choices by lazy { shared.uiPrefs.assistantOverlayAgent.map { Choice(it) } }

    @Composable
    private fun Head() {
        val fleet = shared.fleet
        val choice by choices.collectAsState(null)
        val seen = rememberOverlayAgent(fleet, chosenId = choice?.id, ready = choice != null)
        SideEffect { agent = seen }
        val rig = rememberOverlayRig(fleet, seen?.id)
        OverlayReplies(fleet, seen, ui)
        // Keyed: another Agent gets its own dictation, from scratch.
        seen?.let { key(it.id) { OverlayVoice(fleet, it.id, ui) } }
        OverlayHead(seen, rig, ui, size = HEAD_DP.dp)
    }

    /** Shows [id] from now on, on this device; picking the default Agent goes back to following the default. */
    private fun pickAgent(id: String) {
        val defaultId = shared.fleet.sessions.value.firstOrNull { it.role == "personal_assistant" && it.isDefault == true }?.id
        ui.reply = null
        ui.thinking = false
        lifecycleScope.launch { shared.uiPrefs.putAssistantOverlayAgent(overlayChoiceToStore(id, defaultId)) }
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
                syncHead()
                if (!hidden) dropUndo()
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
            // Sharing on or off: the notification offers Stop, or doesn't.
            snapshotFlow { ui.lookSharing }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        }
        lifecycleScope.launch {
            // Another Agent picked: the quiet notification names it.
            snapshotFlow { agent?.name }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
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
        scheduleDwell()
        return true
    }

    private fun handle(intents: List<CompanionGesture.Intent>) {
        for (i in intents) when (i) {
            CompanionGesture.Intent.Tap -> when {
                // Back from a picker or the consent left open: drop it, the chat returns.
                away -> cancelBridge()
                ui.chatOpen -> closeChat()
                else -> openChat()
            }
            CompanionGesture.Intent.ListenStart -> {
                cancelBridge()
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
                cancelBridge()
                closeChat()
                ui.dragging = true
                dragFrom = head.params.x to head.params.y
                drop.params.apply {
                    val (cx, cy) = CompanionPlacement.dismissCenter(screen(), px(64))
                    x = (cx - dropHalf()).toInt(); y = (cy - dropHalf()).toInt()
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
            CompanionGesture.Intent.LookArm -> armLook()
            CompanionGesture.Intent.LookDisarm -> disarmLook()
            CompanionGesture.Intent.LookDrop -> {
                ui.dragging = false
                ui.dropHot = false
                drop.remove()
                dropLook()
            }
            CompanionGesture.Intent.DragEnd -> {
                ui.dragging = false
                drop.remove()
                if (ui.dropHot) {
                    ui.dropHot = false
                    ui.hidden = true
                    snapTo(spot)
                    offerUndo()
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
        val window = OverlayWindow(params, oneOff = true) { Chat(compact) }
        if (!compact) {
            window.view.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closeChat()
                false
            }
        }
        chat = window
        chatCompact = compact
        window.show()
        syncHead()
    }

    private fun closeChat() {
        val window = chat ?: return
        chat = null
        ui.chatOpen = false
        window.remove()
        syncHead()
    }

    /**
     * The head is out unless it is hidden, in a screenshot's way, or riding on the phone sheet (which
     * draws its own; while the sheet steps aside for the bridge, the head is back).
     */
    private fun syncHead() {
        val shown = !ui.hidden && !ui.capturing && !(chat != null && chatCompact && !away)
        head.view.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        val flags = head.params.flags
        head.params.flags = if (shown) flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        head.update()
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
                    composerChips = { LookChips(agent.name) },
                    onPickAgent = { pickAgent(it) },
                )
            }
        } else {
            OverlayChat(
                fleet, agent, rig, ui, compact = false,
                onClose = { closeChat() },
                onOpenInApp = { openInApp(agent.id) },
                modifier = Modifier.size(380.dp, 560.dp),
                composerChips = { LookChips(agent.name) },
                onPickAgent = { pickAgent(it) },
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

    // ── undo a hide ──

    private fun offerUndo() {
        dropUndo()
        val name = currentAgent?.name ?: "Your assistant"
        val window = OverlayWindow(undoParams(), oneOff = true) {
            OverlayUndo(name, onUndo = {
                dropUndo()
                ui.hidden = false
            })
        }
        undo = window
        window.show()
        main.postDelayed(undoGone, UNDO_MS)
    }

    private fun dropUndo() {
        main.removeCallbacks(undoGone)
        undo?.remove()
        undo = null
    }

    // ── the bridge: what only an activity may ask for ──

    /**
     * Opens [OverlayBridgeActivity], then steps out of its way. Started while the chat is still on
     * screen: an app's visible window is what lets it start an activity from the background.
     */
    private fun runBridge(intent: Intent): Boolean {
        if (away) return false
        val request = ++bridgeId
        if (runCatching { startActivity(intent.putExtra(OverlayBridgeActivity.EXTRA_REQUEST, request)) }.isFailure) return false
        goAway(true)
        // A start the system quietly refused never answers; don't wait for it.
        main.postDelayed(bridgeLost, BRIDGE_WAIT_MS)
        return true
    }

    /** This service, if [request] is the one still out with the bridge. */
    private fun ifAsked(request: Int): AssistantOverlayService? = takeIf { away && request == bridgeId }

    private fun onBridgeOpened(request: Int, activity: Activity): Boolean {
        if (ifAsked(request) == null) return false
        main.removeCallbacks(bridgeLost)
        bridge = WeakReference(activity)
        return true
    }

    /**
     * Gives up on the request out with the bridge (it never came up, or the user came back without
     * answering): its screen goes, a pick comes back empty, the chat returns. [failed] says so.
     */
    private fun cancelBridge(failed: Boolean = false) {
        if (!away) return
        bridgeId++
        bridge?.get()?.let { runCatching { it.finishAndRemoveTask() } }
        when {
            sharing -> {
                sharing = false
                pendingLook = null
                goAway(false)
                if (failed) AndroidNotices(applicationContext).show("Couldn't ask to share the screen.")
            }
            picker.inFlightId != null -> onPicked(emptyList())
            else -> goAway(false)
        }
    }

    /** Steps the chat out of the way of the bridge's screen (it would cover it), or brings it back. */
    private fun goAway(away: Boolean) {
        if (!away) {
            main.removeCallbacks(bridgeLost)
            bridge = null
        }
        if (this.away == away) return
        this.away = away
        chat?.view?.visibility = if (away) View.INVISIBLE else View.VISIBLE
        syncHead()
    }

    private fun onPicked(uris: List<Uri>) {
        goAway(false)
        if (uris.isNotEmpty()) {
            keptGrants += uris
            main.postDelayed({ releaseGrants(uris) }, GRANT_KEEP_MS)
        }
        picker.deliver(uris.ifEmpty { null })
    }

    private fun releaseGrants(uris: Collection<Uri>) {
        for (uri in uris.toList()) {
            keptGrants -= uri
            runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    // ── looks: drop it on something and hold still, and that part goes to its chat ──

    /**
     * Android's screen-sharing session for looks: asked for once, kept until it's stopped (Android's
     * sharing chip, Stop in the chat or the notification, or the screen going off). Its mirror is paused
     * between looks, so nothing of the screen is read unless a look is taken.
     */
    private inner class Look(private val projection: MediaProjection) {
        private val size = displaySize()
        private val reader = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 2)
        private val display: VirtualDisplay
        /** We are ending it ourselves, so Android's onStop that follows isn't Android's doing. */
        var ending = false
        /** Frames that reach the reader while the mirror is paused: 0 means detaching the surface works. */
        private var pausedFrames = 0
        private var pausedAt = 0L
        private val startedAt = SystemClock.uptimeMillis()
        private var grabs = 0

        init {
            // Android 14+ wants the callback before the virtual display.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { if (!ending) endLook(this@Look, "Android stopped it (its sharing chip, or the lock)") }
                }
            }, main)
            display = projection.createVirtualDisplay(
                "supermux-assistant-look", size.first, size.second, windowContext.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main,
            ) ?: error("no virtual display for the look session")
            countWhilePaused()
            Log.i(LOOK_LOG, "session started on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${size.first}x${size.second}, mirror paused")
        }

        /** One frame of the screen as it is now: the mirror runs only for as long as that takes. */
        suspend fun grab(why: String): Bitmap? {
            val asked = SystemClock.uptimeMillis()
            if (pausedFrames > 0) Log.w(LOOK_LOG, "$pausedFrames frame(s) arrived while paused: detaching the surface did not stop the mirror")
            pausedFrames = 0
            val shot = withTimeoutOrNull(1_500) {
                suspendCancellableCoroutine { cont ->
                    runCatching { reader.acquireLatestImage()?.close() }
                    reader.setOnImageAvailableListener({ r ->
                        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        val bmp = runCatching { image.frameBitmap(size.first, size.second) }.getOrNull()
                        image.close()
                        pause()
                        if (cont.isActive) cont.resume(bmp)
                    }, main)
                    display.surface = reader.surface
                    cont.invokeOnCancellation { main.post { pause() } }
                }
            }
            grabs++
            val took = SystemClock.uptimeMillis() - asked
            if (shot != null) Log.i(LOOK_LOG, "$why: one frame in $took ms (grab $grabs)") else Log.w(LOOK_LOG, "$why: no frame within $took ms")
            return shot
        }

        private fun pause() {
            runCatching { display.surface = null }
            countWhilePaused()
        }

        /**
         * While paused, any frame that still arrives is counted (and dropped), to prove the mirror stopped;
         * one already on its way when it paused isn't counted.
         */
        private fun countWhilePaused() {
            pausedAt = SystemClock.uptimeMillis()
            reader.setOnImageAvailableListener({ r ->
                runCatching { r.acquireLatestImage()?.close() }
                if (SystemClock.uptimeMillis() - pausedAt > 200) pausedFrames++
            }, main)
        }

        fun stop(reason: String) {
            ending = true
            reader.setOnImageAvailableListener(null, null)
            runCatching { display.surface = null }
            runCatching { display.release() }
            runCatching { projection.stop() }
            runCatching { reader.close() }
            val minutes = (SystemClock.uptimeMillis() - startedAt) / 60_000.0
            Log.i(LOOK_LOG, "session ended after ${"%.1f".format(minutes)} min, $grabs grab(s), $pausedFrames frame(s) while paused: $reason")
        }
    }

    private var look: Look? = null
    /** Where a look was dropped while Android was still asking to share the screen. */
    private var pendingLook: LookRect? = null
    private var frame: OverlayWindow? = null
    private var lookRect: LookRect? = null
    private var lookJob: Job? = null
    private var dwellAt: Long? = null
    private val dwell = Runnable { onDwell() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Android 15 QPR1+ ends a share on lock by itself; give it the chance, so the log shows who did.
            val session = look ?: return
            main.postDelayed({ if (look === session) endLook(session, "screen off (ended by the overlay; Android hadn't)") }, SCREEN_OFF_GRACE_MS)
        }
    }

    /** Keeps the hold-still timer on the gesture's due time, without pushing it back on every wobble. */
    private fun scheduleDwell() {
        val due = gesture.lookDueAt
        if (due == dwellAt) return
        main.removeCallbacks(dwell)
        dwellAt = due
        if (due != null) main.postAtTime(dwell, due)
    }

    private fun headCenter(): Pair<Float, Float> {
        val half = px(4) + headPx() / 2
        return head.params.x + half to head.params.y + half
    }

    /** The drag held still: arm the look, if it's over the screen (not along an edge, not on the ✕). */
    private fun onDwell() {
        val (cx, cy) = headCenter()
        val over = CompanionPlacement.inLookZone(screen(), cx, cy, edgeBand = headPx() + px(MARGIN_DP + 12), dismissOffset = px(64), dismissRadius = px(90))
        if (!over) return
        dwellAt = null
        handle(gesture.dwell(SystemClock.uptimeMillis()))
    }

    private fun plainFrame(cx: Float, cy: Float): LookRect {
        val s = screen()
        return CompanionPlacement.lookFrame(s, cx, cy, s.width * 0.8f, s.height * 0.22f)
    }

    /** Armed: frame what letting go will take, the block of content under it when the screen can be read. */
    private fun armLook() {
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        ui.lookArmed = true
        val (cx, cy) = headCenter()
        val session = look
        if (session == null) {
            showFrame(plainFrame(cx, cy))
            return
        }
        lookJob = lifecycleScope.launch {
            val found = withTimeoutOrNull(500) {
                val shot = session.grab("frame the look") ?: return@withTimeoutOrNull null
                withContext(Dispatchers.Default) { blockAround(shot, cx, cy) }
            }
            if (ui.lookArmed) showFrame(found ?: plainFrame(cx, cy))
        }
    }

    private fun disarmLook() {
        ui.lookArmed = false
        lookJob?.cancel()
        lookJob = null
        lookRect = null
        hideFrame()
    }

    /** Let go while armed: what's in the frame goes to the chat; Android is asked first if it hasn't been. */
    private fun dropLook() {
        val (cx, cy) = headCenter()
        val target = lookRect ?: plainFrame(cx, cy)
        disarmLook()
        snapTo(spot)
        val session = look
        if (session != null) {
            takeLook(session, target, settleMs = 150)
            return
        }
        pendingLook = target
        sharing = true
        if (!runBridge(OverlayBridgeActivity.share(this))) {
            sharing = false
            pendingLook = null
            AndroidNotices(applicationContext).show("Couldn't ask to share the screen.")
        }
    }

    /** The overlay steps out of the picture, one frame is taken, and its framed part goes to the composer. */
    private fun takeLook(session: Look, rect: LookRect, settleMs: Long) {
        ui.capturing = true
        syncHead()
        dropUndo()
        lifecycleScope.launch {
            delay(settleMs)
            val shot = session.grab("take the look")
            ui.capturing = false
            syncHead()
            val png = shot?.let { withContext(Dispatchers.Default) { runCatching { encodeLook(it, rect) }.getOrNull() } }
            if (png == null) {
                AndroidNotices(applicationContext).show("Couldn't take that look.")
                return@launch
            }
            val name = "look-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png"
            ui.shareAttach = ComposerExternalAttach(PickedFile(name, "image/png", ByteArrayChunkSource(png)), text = "", send = false)
            openChat()
        }
    }

    /** The block of content around the head in [shot] (its own head painted out), in screen px; null = none. */
    private fun blockAround(shot: Bitmap, cx: Float, cy: Float): LookRect? {
        val step = 4
        val argb = IntArray(shot.width * shot.height).also { shot.getPixels(it, 0, shot.width, 0, 0, shot.width, shot.height) }
        val g = LumaGrid.fromArgb(argb, shot.width, shot.height, step)
        val half = px(4) + headPx() / 2
        g.blankOut(((cx - half) / step).toInt(), ((cy - half) / step).toInt(), ((cx + half) / step).toInt(), ((cy + half) / step).toInt())
        val block = LookBlock.around(
            g, (cx / step).toInt(), (cy / step).toInt(),
            minGap = (px(14) / step).toInt().coerceAtLeast(3),
            seed = (half / step).toInt() + 4,
        ) ?: return null
        val s = screen()
        val pad = px(8)
        val r = LookRect(
            (block.left * step - pad).coerceAtLeast(0f),
            (block.top * step - pad).coerceAtLeast(s.topInset),
            (block.right * step + pad).coerceAtMost(s.width),
            (block.bottom * step + pad).coerceAtMost(s.height - s.bottomInset),
        )
        // Too small to be a thing, or most of the screen: a plain frame says more.
        if (r.width < px(72) || r.height < px(40) || r.width * r.height > s.width * s.height * 0.6f) return null
        return r
    }

    private fun encodeLook(shot: Bitmap, rect: LookRect): ByteArray {
        val l = rect.left.toInt().coerceIn(0, shot.width - 1)
        val t = rect.top.toInt().coerceIn(0, shot.height - 1)
        val r = rect.right.toInt().coerceIn(l + 1, shot.width)
        val b = rect.bottom.toInt().coerceIn(t + 1, shot.height)
        val crop = Bitmap.createBitmap(shot, l, t, r - l, b - t)
        val (w, h) = shareImageSize(crop.width, crop.height, SHARE_MAX_EDGE)
        val scaled = if (w == crop.width && h == crop.height) crop else Bitmap.createScaledBitmap(crop, w, h, true)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    /** The frame around what letting go takes, with its label above; it never takes a touch. */
    private fun showFrame(r: LookRect) {
        hideFrame()
        lookRect = r
        val label = px(32)
        val top = (r.top - label).coerceAtLeast(0f)
        val params = baseParams(
            r.width.toInt(), (r.bottom - top).toInt(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = r.left.toInt()
            y = top.toInt()
        }
        val name = currentAgent?.name ?: "your assistant"
        val frameTop = ((r.top - top) / windowContext.resources.displayMetrics.density).dp
        frame = OverlayWindow(params, oneOff = true) { OverlayLookFrame(name, frameTop) }.also { it.show() }
    }

    private fun hideFrame() {
        frame?.remove()
        frame = null
    }

    /**
     * Android said yes. Called while the bridge is still on screen: Android 14+ lets a service take the
     * screen-capture type only then, and wants it before the projection starts. The session stays for
     * the looks to come; a look dropped while it was asking is taken now.
     */
    private fun onShareAllowed(resultCode: Int, data: Intent) {
        if (!sharing) return
        sharing = false
        val session = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val capture = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                val now = foregroundServiceType
                val notification = notification()
                runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, now or capture) }
                    // Should the mic be refused this time, looks still work; opening supermux restores it.
                    .recoverCatching {
                        val noMic = now and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
                        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, noMic or capture)
                    }
                    .getOrThrow()
            }
            Look(getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)!!)
        }.getOrNull()
        val rect = pendingLook
        pendingLook = null
        goAway(false)
        if (session == null) {
            AndroidNotices(applicationContext).show("Couldn't share the screen.")
            return
        }
        look = session
        ui.lookSharing = true
        ui.lookDeclined = false
        // The consent dialog leaves the screen first.
        if (rect != null) takeLook(session, rect, settleMs = 500)
    }

    /** Android said no: nothing is taken; the chat offers a screenshot of the user's own instead. */
    private fun lookRefused() {
        sharing = false
        pendingLook = null
        ui.lookDeclined = true
        goAway(false)
        openChat()
    }

    private fun endLook(session: Look, reason: String) {
        if (look === session) {
            look = null
            ui.lookSharing = false
        }
        if (!session.ending) session.stop(reason)
    }

    @Composable
    private fun LookChips(name: String) {
        OverlayLookChips(
            ui, name,
            onStopSharing = { look?.let { endLook(it, "Stop in the chat") } },
            onAttachScreenshot = { pickScreenshot() },
        )
    }

    /** The way round a "no" to sharing: a screenshot of their own, from the photo picker, into the composer. */
    private fun pickScreenshot() {
        lifecycleScope.launch {
            val uris = picker.pick(PickKind.Images, "overlay-look") ?: return@launch
            val file = uris.firstOrNull()?.let { pickedFileFromUri(windowContext, it) } ?: return@launch
            ui.lookDeclined = false
            ui.shareAttach = ComposerExternalAttach(file, text = "", send = false)
        }
    }

    // ── the screen it lives on ──

    private val screenChanges = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // Folded ↔ open, or rotated: re-seat everything for the new screen.
            disarmLook()
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

    /** The whole display, bars included: what a screenshot covers. */
    private fun displaySize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun px(dp: Int): Float = dp * windowContext.resources.displayMetrics.density
    private fun headPx(): Float = px(HEAD_DP)
    private fun dropHalf(): Float = OVERLAY_DROP_BOX.value / 2 * windowContext.resources.displayMetrics.density

    private fun haptic(kind: Int) {
        head.view.performHapticFeedback(kind)
    }
}

/** An RGBA_8888 frame as a bitmap of exactly [width]×[height] (its rows can be padded past the width). */
private fun Image.frameBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val rowPixels = plane.rowStride / plane.pixelStride
    val full = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
    full.copyPixelsFromBuffer(plane.buffer)
    if (rowPixels == width) return full
    return Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() }
}
