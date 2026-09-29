package dev.supermux.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp

/**
 * Zoom and pan for a diagram, written to live INSIDE a scrolling chat as well as in the fullscreen
 * viewer, so a diagram can be read where it sits:
 *  - pinch (touch), trackpad pinch and Ctrl/⌘ + wheel zoom about the fingers / cursor — the browser
 *    reports a trackpad pinch as a Ctrl + wheel stream, so its delta is applied proportionally rather
 *    than as a fixed step per event (which is what made trackpad pinching lurch)
 *  - a plain wheel / two-finger trackpad scroll pans a zoomed diagram and, at fit, is left alone so
 *    the chat scrolls past it
 *  - one-finger / mouse drag pans a zoomed diagram; at fit it is left alone, so the chat scrolls
 *  - double-tap / double-click toggles fit ↔ 2× on the tapped point
 *  - a macOS desktop trackpad pinch, which AWT never turns into pointer events, arrives through
 *    [TrackpadMagnify] and zooms whichever diagram the cursor is over
 */
internal fun Modifier.diagramGestures(transform: LightboxTransform): Modifier = this
    .onSizeChanged { transform.viewport = Size(it.width.toFloat(), it.height.toFloat()) }
    // Separate pointerInput blocks: one detector cannot both own the wheel and arbitrate taps
    // against drags (as in the image lightbox).
    .pointerInput(transform) {
        val panPerUnit = WheelPan.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val delta = event.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
                if (delta == Offset.Zero) continue
                val mods = event.keyboardModifiers
                val focus = event.changes.first().position
                if (mods.isCtrlPressed || mods.isMetaPressed) {
                    transform.zoomBy(wheelZoomFactor(delta.y), focus)
                } else if (transform.scale > LightboxTransform.MIN) {
                    transform.panBy(-delta * panPerUnit)
                } else {
                    continue // at fit: let the chat have the scroll
                }
                event.changes.forEach { it.consume() }
            }
        }
    }
    .pointerInput(transform) {
        // Hovering makes this diagram the target of a host-delivered trackpad pinch; the cursor
        // position doubles as the zoom focus, so no window→local coordinate mapping is needed.
        var hover = Offset.Zero
        val target: (Float) -> Unit = { magnification -> transform.zoomBy(1f + magnification, hover) }
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move -> {
                            hover = event.changes.first().position
                            TrackpadMagnify.target = target
                        }
                        PointerEventType.Exit -> if (TrackpadMagnify.target === target) TrackpadMagnify.target = null
                    }
                }
            }
        } finally {
            if (TrackpadMagnify.target === target) TrackpadMagnify.target = null
        }
    }
    .pointerInput(transform) { detectTapGestures(onDoubleTap = { transform.toggleZoom(it) }) }
    .pointerInput(transform) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var claimed = false
            var travel = Offset.Zero
            var spread = 1f
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                // Someone else (the chat's scroll) took this gesture first — step aside.
                if (!claimed && event.changes.any { it.isConsumed }) break
                val fingers = event.changes.count { it.pressed }
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (!claimed) {
                    travel += pan
                    spread *= zoom
                    // Two fingers always mean the diagram; one only once there is something to pan.
                    val wants = fingers >= 2 || transform.scale > LightboxTransform.MIN
                    val pastSlop = abs(1f - spread) * size.width / 2f > viewConfiguration.touchSlop ||
                        travel.getDistance() > viewConfiguration.touchSlop
                    if (wants && pastSlop) claimed = true else continue
                }
                if (zoom != 1f) transform.zoomBy(zoom, event.calculateCentroid(useCurrent = true))
                transform.panBy(pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }

/**
 * One wheel notch (a delta of 1) zooms by about [LightboxTransform.STEP]; a trackpad pinch's many
 * small fractional deltas compose to the same curve, so pinching speed tracks the fingers.
 * Negative delta (wheel up / fingers apart) zooms in.
 */
internal fun wheelZoomFactor(deltaY: Float): Float = exp(-deltaY.coerceIn(-4f, 4f) * WHEEL_ZOOM_RATE)

private const val WHEEL_ZOOM_RATE = 0.22f // ≈ ln(LightboxTransform.STEP)

/** How far one wheel unit pans (Compose reports wheel notches / trackpad steps, not pixels). */
private val WheelPan = 40.dp

/**
 * The seam for a trackpad pinch the host sees but Compose does not: on macOS desktop, AWT delivers
 * it only through `com.apple.eawt.event` (the desktop app forwards it here), never as a pointer
 * event. Browsers report the same gesture as Ctrl + wheel, which [diagramGestures] already handles.
 */
object TrackpadMagnify {
    internal var target: ((Float) -> Unit)? = null

    /** A pinch step: `+0.05` is 5 % bigger. Returns whether a diagram under the cursor took it. */
    fun dispatch(magnification: Float): Boolean {
        val t = target ?: return false
        t(magnification)
        return true
    }
}
