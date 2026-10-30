package dev.supermux.webview.engine.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import dev.supermux.webview.engine.LocalWebSurfaceHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.skiko.SkiaLayer

/**
 * The Windows engine's [dev.supermux.webview.engine.WebEngine.Surface] (spec §7 "Windows: frames"), the test's
 * `CaptureSurface` grown up:
 * - it draws the captured frames, pulled at the start of the Compose frame after WGC delivered one, 1:1 from the top-left
 *   and cut to the surface, from the store's own memory (D3, L9);
 * - it places WebView2 where it is: moves at once, sizes once settled, its window's scale, its window's frame as parent
 *   (W3, L26), and tells WebView2 when the window moves (popups, the IME window, W6; the capture's adapter, D24);
 * - it forwards the mouse with `SendMouseInput` and consumes it (D16, L27); in a list the wheel is the list's;
 * - it shows WebView2's cursor (W8), and hands keyboard focus over through the engine's FocusBroker (Task 25).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Wv2Surface(engine: WindowsWebEngine, modifier: Modifier) {
    val window = LocalAwtWindow.current
    val inList = LocalWebSurfaceHints.current.inScrollingList
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    DisposableEffect(engine, focus, focusManager, window) {
        engine.bindSurface(focus, focusManager, window)
        onDispose { engine.unbindSurface(focus) }
    }
    LaunchedEffect(engine) {
        engine.onSurfaceAttached(true)
        try {
            var full = true
            var logged = false
            while (true) {
                withFrameNanos {
                    engine.onSurfaceFrame(full)
                    full = false
                }
                if (!logged) window?.let { logged = true; SkikoAdapterLog.once(it) }   // D24, after skiko's first frame
                engine.awaitSurfaceWork()
            }
        } finally {
            engine.onSurfaceAttached(false)
        }
    }
    LaunchedEffect(engine) {
        while (true) {
            delay(if (engine.placementPending) 16L else 100L)
            engine.settlePlacement(System.nanoTime())
        }
    }
    DisposableEffect(engine, window) {
        val listener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = engine.onWindowMoved()
        }
        window?.addComponentListener(listener)
        onDispose { window?.removeComponentListener(listener) }
    }
    Canvas(
        modifier
            .onGloballyPositioned {
                val origin = it.positionInWindow()
                engine.onSurfacePlaced(window, origin.x.roundToInt(), origin.y.roundToInt(), it.size.width, it.size.height)
            }
            .pointerHoverIcon(engine.cursor)
            .focusRequester(focus)
            .onFocusChanged { engine.onSurfaceFocus(it.isFocused) }
            .focusable()
            .pointerInput(engine, inList) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // In a list, the wheel is the list's: left unconsumed, it scrolls the list.
                        if (event.type == PointerEventType.Scroll && inList) continue
                        if (event.type == PointerEventType.Press) engine.onSurfacePress(focus)
                        engine.pointer(event)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val image = engine.frames.image ?: return@Canvas
        // As WebView2 drew it, from the top left, cut to the surface: while it catches up with a new size, its frame is
        // the old size's, which a stretch would make jump. Drawn from the store's own memory: no copy (W17).
        clipRect { drawIntoCanvas { it.skiaCanvas.drawImage(image, 0f, 0f) } }
        engine.onSurfaceDrawn()
    }
}

/**
 * D24: skiko's renderer (API, adapter, VRAM) once per window, next to the glue's "capture on <adapter>" lines. On a
 * machine whose display runs on another GPU than skiko's, they differ; the frames still arrive (they cross through the
 * CPU), but zero-copy (Task 34) can't be used there.
 */
internal object SkikoAdapterLog {
    private val seen = Collections.newSetFromMap(WeakHashMap<java.awt.Window, Boolean>())

    fun once(window: java.awt.Window) {
        if (!seen.add(window)) return
        val layer = layerOf(window) ?: return
        System.err.println("webview-windows: skiko " + layer.renderInfo.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | "))
    }

    private fun layerOf(container: java.awt.Container): SkiaLayer? {
        for (child in container.components) {
            if (child is SkiaLayer) return child
            if (child is java.awt.Container) layerOf(child)?.let { return it }
        }
        return null
    }
}
