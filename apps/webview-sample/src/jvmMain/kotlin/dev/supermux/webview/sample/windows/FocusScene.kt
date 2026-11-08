package dev.supermux.webview.sample.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.sample.linux.CheckScope
import dev.supermux.webview.sample.linux.LinuxChecks
import dev.supermux.webview.sample.linux.LinuxChecks.eventually
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An engine's surface in a check's own scene: the scene loads [content] itself (as the runtime does: configured, then
 * loaded), having subscribed to the engine's events first, since no first paint waits for a subscriber; and it records
 * where the surface is on screen. [origin] is where the window's content is on screen (`CheckScope.contentOrigin`).
 */
internal class SceneEngine(val engine: WebEngine, private val content: WebContent, private val origin: () -> Point) {
    private val painted = CompletableDeferred<Unit>()

    /** The surface's bounds in the window, in pixels; null until laid out. */
    @Volatile var bounds: Rect? = null
        private set

    /** Shows the surface with [modifier] (its size), and loads the page the first time. */
    @Composable
    fun Surface(modifier: Modifier) {
        LaunchedEffect(engine) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.collect { if (it == EngineEvent.FirstPaint) painted.complete(Unit) }
            }
            LinuxChecks.start(engine, content)
        }
        engine.Surface(modifier.onGloballyPositioned { bounds = it.boundsInWindow() })
    }

    /** The surface on screen. */
    val rect: Rectangle get() = onScreen(checkNotNull(bounds) { "the surface isn't laid out" }, origin())

    /** The screen point of the page's pixel ([x], [y]): at the VM's 100 %, a CSS pixel. */
    fun pagePoint(x: Int, y: Int): Point = rect.let { Point(it.x + x, it.y + y) }

    /** Waits for the page's first paint (drawn in place) and the surface's place. */
    suspend fun awaitPainted() {
        withTimeoutOrNull(20_000) { painted.await() } ?: throw AssertionError("the scene's page never painted")
        eventually("the surface laid out") { bounds != null }
    }
}

/** [r], a rectangle in the window's content, on screen. */
internal fun onScreen(r: Rect, origin: Point): Rectangle =
    Rectangle(origin.x + r.left.roundToInt(), origin.y + r.top.roundToInt(), r.width.roundToInt(), r.height.roundToInt())

/**
 * A focus check's scene (the test's focus matrix, W4): Compose field A above the engine's surface (320 × 240 dp), field B
 * below, inside an `onPreviewKeyEvent` that counts the app's shortcut Ctrl+Shift+K and consumes it. The page has three
 * inputs at fixed places (`in1` at (10, 10), 200 × 30) and runs [script]. Each part's place on screen is recorded for
 * clicks; [pressedAt] is when the scene first saw a press, in the Initial pass (it consumes nothing): the perf lab's.
 */
internal class FocusScene(engine: WebEngine, script: String = "", origin: () -> Point) {
    val scene = SceneEngine(engine, LinuxChecks.solidPage("focus", "#ffffff", body = INPUTS, script = script), origin)
    val engine: WebEngine get() = scene.engine
    private val origin = origin

    var a: String by mutableStateOf("")
    var b: String by mutableStateOf("")
    var shortcuts: Int by mutableIntStateOf(0)
    val pressedAt = AtomicLong(0)

    @Volatile private var aBounds: Rect? = null
    @Volatile private var bBounds: Rect? = null

    @Composable
    fun Content() {
        val field = Modifier.size(320.dp, 36.dp).background(Color.White).border(1.dp, Color.Gray)
        Column(
            Modifier.padding(start = 40.dp, top = 24.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.isShiftPressed && e.key == Key.K) {
                        shortcuts++
                        true
                    } else {
                        false
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) pressedAt.compareAndSet(0, System.nanoTime())
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(a, { a = it }, field.onGloballyPositioned { aBounds = it.boundsInWindow() }.padding(6.dp), singleLine = true)
            scene.Surface(Modifier.size(320.dp, 240.dp))
            BasicTextField(b, { b = it }, field.onGloballyPositioned { bBounds = it.boundsInWindow() }.padding(6.dp), singleLine = true)
        }
    }

    val fieldA: Rectangle get() = onScreen(checkNotNull(aBounds) { "field A isn't laid out" }, origin())
    val fieldB: Rectangle get() = onScreen(checkNotNull(bBounds) { "field B isn't laid out" }, origin())
    val surface: Rectangle get() = scene.rect

    /** `in1`, the page's first input, on screen. */
    val input: Rectangle get() = surface.let { Rectangle(it.x + 10, it.y + 10, 200, 30) }

    fun pagePoint(x: Int, y: Int): Point = scene.pagePoint(x, y)

    /** Waits for the page's first paint and every part's place. */
    suspend fun awaitPainted() {
        scene.awaitPainted()
        eventually("the fields laid out") { aBounds != null && bBounds != null }
    }

    /** `in1`'s value, as JSON. */
    suspend fun pageValue(): String = engine.evaluate("document.getElementById('in1').value") ?: "null"

    /** Clicks `in1`, waits until the page has the keyboard there, and types [text] into it, as a user does. */
    suspend fun typeIntoPage(scope: CheckScope, text: String) {
        awaitPainted()
        scope.clickOn(input)
        eventually("the page has the keyboard in in1") { engine.evaluate("document.activeElement.id + '|' + document.hasFocus()") == "\"in1|true\"" }
        scope.type(text)
        eventually("the page has what was typed") { pageValue().removeSurrounding("\"").endsWith(text) }
    }

    companion object {
        const val PAGE_ELEMENTS = 3
        private val INPUTS = (1..PAGE_ELEMENTS).joinToString("") { i ->
            "<input id=in$i style='position:fixed;left:10px;top:${10 + (i - 1) * 50}px;width:200px;height:30px;" +
                "box-sizing:border-box;font-size:16px'>"
        }
    }
}
