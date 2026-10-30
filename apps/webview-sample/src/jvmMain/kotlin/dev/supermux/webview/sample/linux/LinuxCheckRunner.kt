package dev.supermux.webview.sample.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.platformWebEngineFactory
import java.awt.KeyboardFocusManager
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** The size the checks show an engine's surface at, in pixels: at 1.0 density (Xvfb, `sun.java2d.uiScale=1`), dp. */
const val SURFACE_WIDTH: Int = 320
const val SURFACE_HEIGHT: Int = 480

/**
 * One conformance check of the Linux engine, through the public engine SPI only, or through the public `WebView` for
 * what only a view shows. It fails by throwing, or by taking longer than [timeoutMillis]. (iOS's `CheckRunner.kt`, on
 * the JVM, with the screen and the input devices of an X server.)
 */
class Check(val name: String, val timeoutMillis: Long = 60_000, val run: suspend CheckScope.() -> Unit)

/** What the window shows: an engine's surface, or a scene of a check's own; and where, on the screen. */
class CheckHost {
    var shown: WebEngine? by mutableStateOf(null)
    var scene: (@Composable () -> Unit)? by mutableStateOf(null)

    /** The shown surface's bounds in the window, in pixels; null until it is laid out. */
    var bounds: Rect? = null
    var window: ComposeWindow? = null
}

/** What a check works with. Everything it made is released when it ends, pass or fail. */
class CheckScope internal constructor(private val host: CheckHost, private val scope: CoroutineScope) {
    private val made = mutableListOf<WebEngine>()
    private val recorders = mutableMapOf<WebEngine, Recorder>()
    private val robot = Robot().apply { autoDelay = 0 }

    /** A new engine of this platform, as the pool would get it. */
    suspend fun engine(profile: WebProfile = WebProfile.Ephemeral): WebEngine {
        val result = platformWebEngineFactory().create(EngineConfig(profile))
        val engine = (result as? WebEngineResult.Created)?.engine ?: throw AssertionError("no engine: $result")
        made += engine
        return engine
    }

    /** Records [engine]'s events from now on, as the runtime does: one subscriber at a time. */
    fun record(engine: WebEngine): Recorder {
        stopRecording(engine)
        return Recorder(engine, scope).also { recorders[engine] = it }
    }

    /** Stops recording [engine]'s events: nothing subscribes to them until the next [record], as for a parked page. */
    fun stopRecording(engine: WebEngine) {
        recorders.remove(engine)?.stop()
    }

    /** Shows [engine]'s surface, [SURFACE_WIDTH] × [SURFACE_HEIGHT] at (40, 40) in the window, or nothing. */
    fun show(engine: WebEngine?) {
        if (engine == null) host.bounds = null
        host.shown = engine
    }

    /** Shows [scene] instead of an engine's surface, or nothing: for checks of what a `WebView` shows. */
    fun compose(scene: (@Composable () -> Unit)?) {
        host.scene = scene
    }

    /** The colour on screen, `0xRRGGBB`, at ([x], [y]) in the shown surface's pixels. */
    fun pixel(x: Int, y: Int): Int {
        val (sx, sy) = onScreen(x, y)
        return robot.getPixelColor(sx, sy).rgb and 0xFFFFFF
    }

    /** A left click at ([x], [y]) in the shown surface, through the X server, as a user's. */
    fun click(x: Int, y: Int) {
        val (sx, sy) = onScreen(x, y)
        robot.mouseMove(sx, sy)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    /** [notches] turns of the wheel (positive: down) over ([x], [y]) in the shown surface. */
    fun wheel(x: Int, y: Int, notches: Int) {
        val (sx, sy) = onScreen(x, y)
        robot.mouseMove(sx, sy)
        robot.mouseWheel(notches)
    }

    /**
     * Types [text] into the focused window, in order. Through the X server with xdotool, which maps each character to a
     * keysym, except a Latin-1 capital (Ç, Ö, Ü…): xdotool sends those as their lowercase keysyms (the WPE feasibility
     * test's L3, seen with xev), so they go to the window as AWT key events of their own instead. That is still the
     * engine's whole path, from AWT's event through Compose and the key mapping into WPE.
     */
    suspend fun type(text: String) {
        val run = StringBuilder()
        suspend fun xdotool() {
            if (run.isEmpty()) return
            val chunk = run.toString()
            run.clear()
            withContext(Dispatchers.IO) {
                val code = ProcessBuilder("xdotool", "type", "--delay", "40", "--", chunk).redirectErrorStream(true).start().waitFor()
                check(code == 0) { "xdotool type exited $code" }
            }
            settle()
        }
        for (c in text) {
            if (c in 'À'..'Þ' && c != '×') {
                xdotool()
                typeThroughAwt(c)
                settle()
            } else {
                run.append(c)
            }
        }
        xdotool()
    }

    /** One character as AWT delivers a typed key: pressed, typed and released, with Shift, to the focus owner. */
    private suspend fun typeThroughAwt(c: Char) = withContext(Dispatchers.Main) {
        val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            ?: checkNotNull(host.window?.mostRecentFocusOwner) { "nothing has the keyboard focus" }
        val code = KeyEvent.getExtendedKeyCodeForChar(c.code)
        val shift = InputEvent.SHIFT_DOWN_MASK
        val at = System.currentTimeMillis()
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        queue.postEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, at, shift, code, c))
        queue.postEvent(KeyEvent(target, KeyEvent.KEY_TYPED, at, shift, KeyEvent.VK_UNDEFINED, c))
        queue.postEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, at, shift, code, c))
    }

    /** Lets what was typed reach the window: the X server's events, then AWT's queue. */
    private suspend fun settle() {
        delay(250)
        withContext(Dispatchers.IO) { robot.waitForIdle() }
    }

    private fun onScreen(x: Int, y: Int): Pair<Int, Int> {
        val window = checkNotNull(host.window) { "no window" }
        val bounds = checkNotNull(host.bounds) { "no surface laid out" }
        val origin = window.contentPane.locationOnScreen
        return (origin.x + bounds.left.toInt() + x) to (origin.y + bounds.top.toInt() + y)
    }

    internal fun close() {
        recorders.values.forEach { it.stop() }
        host.shown = null
        host.scene = null
        host.bounds = null
        made.forEach { runCatching { it.release() } }
    }
}

/** An engine's events, as they arrive. A waiter wakes the moment its event comes. */
class Recorder(engine: WebEngine, scope: CoroutineScope) {
    private class Waiter(val from: Int, val matches: (EngineEvent) -> Boolean, val found: CompletableDeferred<EngineEvent>)

    val events = mutableListOf<EngineEvent>()
    private val waiters = mutableListOf<Waiter>()
    private val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events.collect { event ->
            events += event
            for (waiter in waiters.toList()) {
                if (events.size - 1 >= waiter.from && waiter.matches(event)) {
                    waiters -= waiter
                    waiter.found.complete(event)
                }
            }
        }
    }

    /** Waits up to [millis] for an event that [matches], from the [from]th on; returns it. */
    suspend fun await(what: String, from: Int = 0, millis: Long = 8_000, matches: (EngineEvent) -> Boolean): EngineEvent {
        events.drop(from).firstOrNull(matches)?.let { return it }
        val waiter = Waiter(from, matches, CompletableDeferred())
        waiters += waiter
        return withTimeoutOrNull(millis) { waiter.found.await() }
            ?: throw AssertionError("no $what within $millis ms; got ${events.drop(from)}").also { waiters -= waiter }
    }

    /** Waits [millis], then fails if an event that [matches] came from the [from]th on. */
    suspend fun expectNone(what: String, from: Int, millis: Long = 1_500, matches: (EngineEvent) -> Boolean) {
        delay(millis)
        val found = events.drop(from).filter(matches)
        if (found.isNotEmpty()) throw AssertionError("expected no $what, got $found")
    }

    fun stop() = job.cancel()
}

/**
 * Runs [checks] one after another on the main thread, each within its time, and prints `CHECK PASS <name>` or
 * `CHECK FAIL <name>: <why>`, then `CHECKS DONE passed=<n> failed=<n>`. Returns the failures.
 */
internal suspend fun runChecks(checks: List<Check>, host: CheckHost, scope: CoroutineScope): Int {
    var passed = 0
    var failed = 0
    println("CHECKS START ${checks.size}")
    for (check in checks) {
        val context = CheckScope(host, scope)
        val failure = try {
            withTimeout(check.timeoutMillis) { check.run(context) }
            null
        } catch (e: Throwable) {
            e
        }
        context.close()
        delay(100)
        if (failure == null) {
            passed++
            println("CHECK PASS ${check.name}")
        } else {
            failed++
            println("CHECK FAIL ${check.name}: ${failure.message ?: failure}")
        }
        System.out.flush()
    }
    println("CHECKS DONE passed=$passed failed=$failed")
    System.out.flush()
    return failed
}

/**
 * The checks' window (device-checks/linux-run.sh): undecorated at the screen's corner, the surface at (40, 40). Runs
 * [LinuxChecks.all], or those named in [only] (comma-separated), and exits: 0 if every check passed, 1 if one failed, 2
 * if none matched.
 */
fun linuxChecksMain(only: String?) {
    val names = only?.split(',')?.map { it.trim() }?.toSet()
    val checks = LinuxChecks.all.filter { names == null || it.name in names }
    if (checks.isEmpty()) {
        println("CHECKS DONE passed=0 failed=0 (no check named $only)")
        exitProcess(2)
    }
    application {
        val host = remember { CheckHost() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · Linux engine checks",
            undecorated = true,
            state = rememberWindowState(position = WindowPosition(0.dp, 0.dp), size = DpSize(720.dp, 720.dp)),
        ) {
            host.window = window
            LaunchedEffect(Unit) {
                val failed = runChecks(checks, host, this)
                exitProcess(if (failed == 0) 0 else 1)
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF202124))) {
                val scene = host.scene
                val engine = host.shown
                if (scene != null) {
                    scene()
                } else if (engine != null) {
                    key(engine) {
                        engine.Surface(
                            Modifier.offset(40.dp, 40.dp).size(SURFACE_WIDTH.dp, SURFACE_HEIGHT.dp)
                                .onGloballyPositioned { host.bounds = it.boundsInWindow() },
                        )
                    }
                }
            }
        }
    }
}
