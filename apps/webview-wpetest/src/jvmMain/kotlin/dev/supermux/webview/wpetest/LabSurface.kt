package dev.supermux.webview.wpetest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The frame-mode surface, the prototype of the engine's `WebEngine.Surface`. It draws [frames], pulled at the start of
 * the Compose frame after WebKit delivered one, sizes the view to itself, and forwards pointer, wheel and keys to WPE.
 * WPE's view coordinates are logical pixels: the surface's pixels over WPE's scale (the density, times
 * [LabOptions.scale] in the damage-scale runs).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LabSurface(view: LabView, frames: FrameBuffer, options: LabOptions, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val scale = density * options.scale.toFloat()
    val focus = remember { FocusRequester() }
    LaunchedEffect(view, frames) {
        withFrameNanos { frames.pull(view.id, full = true) }
        for (signal in view.frameSignal) withFrameNanos { frames.pull(view.id) }
    }
    Canvas(
        modifier
            .onSizeChanged { size ->
                WpeNative.setSize(view.id, (size.width / scale).roundToInt(), (size.height / scale).roundToInt(), scale.toDouble())
            }
            .focusRequester(focus)
            .onFocusChanged { WpeNative.setFocus(view.id, it.isFocused) }
            .focusable()
            .onKeyEvent { forwardKey(view, it) }
            .pointerInput(view) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) focus.requestFocus()
                        forwardPointer(view, event, scale, options.scrollSign)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val image = frames.image ?: return@Canvas
        drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.None)
        frames.onDrawn(System.nanoTime())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun forwardPointer(view: LabView, event: PointerEvent, scale: Float, scrollSign: Double) {
    val change = event.changes.firstOrNull() ?: return
    val x = change.position.x / scale.toDouble()
    val y = change.position.y / scale.toDouble()
    val k = event.keyboardModifiers
    val modifiers = KeyMapping.modifiers(k.isShiftPressed, k.isCtrlPressed, k.isAltPressed, k.isMetaPressed) or
        KeyMapping.buttons(event.buttons.isPrimaryPressed, event.buttons.isTertiaryPressed, event.buttons.isSecondaryPressed)
    when (event.type) {
        PointerEventType.Move -> WpeNative.pointer(view.id, WpeNative.POINTER_MOVE, x, y, 0, modifiers, 0, 0)
        PointerEventType.Enter -> WpeNative.pointer(view.id, WpeNative.POINTER_ENTER, x, y, 0, modifiers, 0, 0)
        PointerEventType.Exit -> WpeNative.pointer(view.id, WpeNative.POINTER_LEAVE, x, y, 0, modifiers, 0, 0)
        PointerEventType.Press -> WpeNative.pointer(view.id, WpeNative.POINTER_DOWN, x, y, buttonOf(event.button), modifiers, 0, 0)
        PointerEventType.Release -> WpeNative.pointer(view.id, WpeNative.POINTER_UP, x, y, buttonOf(event.button), modifiers, 0, 0)
        PointerEventType.Scroll -> {
            val d = change.scrollDelta
            WpeNative.scroll(view.id, d.x * scrollSign, d.y * scrollSign, x, y, false, modifiers, 0)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun buttonOf(button: PointerButton?): Int = when (button) {
    PointerButton.Secondary -> KeyMapping.BUTTON_SECONDARY
    PointerButton.Tertiary -> KeyMapping.BUTTON_MIDDLE
    else -> KeyMapping.BUTTON_PRIMARY
}

internal fun forwardKey(view: LabView, event: KeyEvent): Boolean {
    val down = when (event.type) {
        KeyEventType.KeyDown -> true
        KeyEventType.KeyUp -> false
        else -> return false
    }
    val key = KeyMapping.keyFor(event.key.nativeKeyCode, KeyMapping.codePointOf(event.utf16CodePoint)) ?: return false
    val modifiers = KeyMapping.modifiers(event.isShiftPressed, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed)
    WpeNative.key(view.id, down, key.keyval, key.keycode, modifiers, 0)
    return true
}
