package dev.supermux.webview.wintest

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect as SkRect

/**
 * The frame-mode surface, the prototype of the Windows engine's `WebEngine.Surface` (EmbeddingMode.Frames). It draws
 * the captured frames, forwards pointer input with SendMouseInput, shows WebView2's cursor, hands keyboard focus over
 * through [AppState], and tells the engine its bounds, debounced (spec §7).
 */
@Composable
fun CaptureSurface(app: AppState, modifier: Modifier = Modifier) {
    val web = app.web
    val debouncer = remember { BoundsDebouncer(settleNanos = 150_000_000L) }
    // Pull a frame at the start of the Compose frame after the native side says one arrived.
    LaunchedEffect(web) {
        while (true) {
            web.frameSignal.receive()
            withFrameNanos { web.buffer.pull() }
            // Chain 3: the GPU copy wasn't done yet; pull again at the next frame.
            if (web.buffer.retryPending) web.frameSignal.trySend(Unit)
        }
    }
    // Resize the engine once the size has settled; until then the last frame is drawn scaled.
    LaunchedEffect(debouncer) {
        while (true) {
            debouncer.poll(System.nanoTime())?.let(app::applyBounds)
            delay(if (debouncer.hasPending) 16L else 50L)
        }
    }
    // WebView2 places popups, the IME candidate window and tooltips from the parent window's screen position.
    DisposableEffect(app.window) {
        val listener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = WinNative.notifyParentMoved()
        }
        app.window.addComponentListener(listener)
        onDispose { app.window.removeComponentListener(listener) }
    }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                // positionInWindow + size, not boundsInWindow: the engine needs the unclipped size (the 1080p runs).
                val origin = coordinates.positionInWindow()
                val bounds = BoundsDebouncer.Rect(
                    origin.x.roundToInt(), origin.y.roundToInt(), coordinates.size.width, coordinates.size.height,
                )
                app.surfaceInWindow = bounds
                debouncer.update(bounds, System.nanoTime())?.let(app::applyBounds)
            }
            .focusRequester(app.surfaceFocus)
            .onFocusChanged { app.onSurfaceFocusChanged(it.isFocused) }
            .focusable()
            .pointerHoverIcon(PointerIcon(web.cursor))
            .pointerInput(web) { forwardPointer(app) }
            .drawBehind {
                val buffer = web.buffer
                val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
                val frame = if (buffer.chain3) buffer.skiaImage?.let { IntSize(it.width, it.height) }
                else buffer.image?.let { IntSize(it.width, it.height) }
                if (frame == null) {
                    drawRect(Color(0xFFE6E9F0))
                    return@drawBehind
                }
                val start = System.nanoTime()
                when {
                    !app.options.drawImage -> drawRect(Color(0xFFDDE3EE))  // what the frame costs skiko, by its absence
                    buffer.chain3 -> drawIntoCanvas {
                        // An image over the buffer's own pixels: no copy here, unlike drawImage(ImageBitmap) below.
                        it.skiaCanvas.drawImageRect(
                            checkNotNull(buffer.skiaImage), SkRect.makeWH(frame.width.toFloat(), frame.height.toFloat()),
                            SkRect.makeWH(target.width.toFloat(), target.height.toFloat()),
                            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), null, true,
                        )
                    }
                    else -> drawImage(
                        checkNotNull(buffer.image), IntOffset.Zero, frame, IntOffset.Zero, target,
                        filterQuality = FilterQuality.Low,
                    )
                }
                buffer.onDrawn(System.nanoTime() - start, scaled = frame != target)
            },
    )
}

/** Compose pointer events → SendMouseInput, in the surface's local pixels (the WebView's own client coordinates). */
private suspend fun PointerInputScope.forwardPointer(app: AppState) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            val x = change.position.x.roundToInt()
            val y = change.position.y.roundToInt()
            val mouse = event.nativeEvent as? MouseEvent
            val keys = virtualKeys(event)
            when (event.type) {
                PointerEventType.Enter, PointerEventType.Move -> WinNative.mouse(WinNative.MOUSE_MOVE, keys, 0, x, y)
                PointerEventType.Exit -> WinNative.mouse(WinNative.MOUSE_LEAVE, 0, 0, 0, 0)
                PointerEventType.Press -> {
                    app.onSurfacePressed()
                    val double = mouse?.clickCount == 2
                    val kind = when (mouse?.button) {
                        MouseEvent.BUTTON3 -> if (double) WinNative.RIGHT_DOUBLE else WinNative.RIGHT_DOWN
                        MouseEvent.BUTTON2 -> if (double) WinNative.MIDDLE_DOUBLE else WinNative.MIDDLE_DOWN
                        else -> if (double) WinNative.LEFT_DOUBLE else WinNative.LEFT_DOWN
                    }
                    WinNative.mouse(kind, keys, 0, x, y)
                }
                PointerEventType.Release -> {
                    val kind = when (mouse?.button) {
                        MouseEvent.BUTTON3 -> WinNative.RIGHT_UP
                        MouseEvent.BUTTON2 -> WinNative.MIDDLE_UP
                        else -> WinNative.LEFT_UP
                    }
                    WinNative.mouse(kind, keys, 0, x, y)
                }
                PointerEventType.Scroll -> {
                    // AWT: positive rotation = toward the user; Win32 WHEEL_DELTA: positive = away from the user.
                    val notches = (mouse as? MouseWheelEvent)?.preciseWheelRotation ?: change.scrollDelta.y.toDouble()
                    WinNative.mouse(WinNative.WHEEL, keys, (-notches * 120).roundToInt(), x, y)
                }
            }
            change.consume()
        }
    }
}

private fun virtualKeys(event: PointerEvent): Int {
    var keys = 0
    if (event.buttons.isPrimaryPressed) keys = keys or WinNative.MK_LBUTTON
    if (event.buttons.isSecondaryPressed) keys = keys or WinNative.MK_RBUTTON
    if (event.buttons.isTertiaryPressed) keys = keys or WinNative.MK_MBUTTON
    if (event.keyboardModifiers.isShiftPressed) keys = keys or WinNative.MK_SHIFT
    if (event.keyboardModifiers.isCtrlPressed) keys = keys or WinNative.MK_CONTROL
    return keys
}
