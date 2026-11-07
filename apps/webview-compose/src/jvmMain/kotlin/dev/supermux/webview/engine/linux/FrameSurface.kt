package dev.supermux.webview.engine.linux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
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
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.supermux.webview.LocalWebSurfaceHints
import java.awt.Cursor

/**
 * The Linux engine's [dev.supermux.webview.engine.WebEngine.Surface] (spec §7 "Linux: frames"), the lab's surface (Task
 * 11, measured by F1–F6 and L1–L5).
 * - It draws the page's frames, pulled at the start of the Compose frame after WebKit delivered one.
 * - It sizes the web view to itself in logical pixels, at the screen's density.
 * - It forwards the pointer, the wheel and keys to WebKit, and shows the page's cursor. A press focuses it.
 * - In a scrolling list ([LocalWebSurfaceHints]) the wheel scrolls the list, not the page.
 * - IME is deferred (F22): typed characters reach the page as keys; an input method's composition doesn't yet.
 */
@Composable
internal fun FrameSurface(engine: LinuxWebEngine, modifier: Modifier) {
    val density = LocalDensity.current.density
    val inList = LocalWebSurfaceHints.current.inScrollingList
    val focus = remember { FocusRequester() }
    DisposableEffect(engine, focus) {
        engine.focusRequester = focus
        onDispose { if (engine.focusRequester === focus) engine.focusRequester = null }
    }
    LaunchedEffect(engine) {
        engine.onSurfaceAttached(true)
        try {
            var full = true
            while (true) {
                withFrameNanos {
                    engine.onSurfaceFrame(full)
                    full = false
                }
                engine.awaitSurfaceWork()
            }
        } finally {
            engine.onSurfaceAttached(false)
        }
    }
    // The surface's place is the Box's: a <select>'s menu is placed against it.
    Box(modifier) {
    Canvas(
        Modifier.matchParentSize()
            .onSizeChanged { engine.onSurfaceSize(it.width, it.height, density) }
            .pointerHoverIcon(engine.cursor)
            .focusRequester(focus)
            .onFocusChanged { engine.onSurfaceFocus(it.isFocused) }
            .focusable()
            .onKeyEvent { engine.key(it) }
            .pointerInput(engine, density, inList) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // In a list, the wheel is the list's: left unconsumed, it scrolls the list.
                        if (event.type == PointerEventType.Scroll && inList) continue
                        if (event.type == PointerEventType.Press) focus.requestFocus()
                        engine.pointer(event, density)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val image = engine.frames.image ?: return@Canvas
        // As WebKit drew it, from the top left, cut to the surface: while WebKit catches up with a new size, its frame is
        // the old size's, which a stretch would make jump, and a 1 px difference at a fractional scale would show a seam.
        // Drawn from the store's own memory: no copy.
        clipRect { drawIntoCanvas { it.skiaCanvas.drawImage(image, 0f, 0f) } }
        engine.onSurfaceDrawn()
    }
    engine.optionMenu?.let { menu -> OptionMenuPopup(menu, density, engine::chooseOption) }
    }
}

/**
 * A `<select>`'s menu (WPE shows none): its items under the element, chosen with the mouse, or with Up, Down and Enter;
 * Escape, or a press outside, closes it as it was. Group labels and disabled items can't be chosen.
 */
@Composable
private fun OptionMenuPopup(menu: WpeEvent.OptionMenu, density: Float, choose: (Int?) -> Unit) {
    val choosable = menu.items.indices.filter { menu.items[it].choosable }
    var highlighted by remember(menu) {
        mutableStateOf(menu.items.indices.firstOrNull { menu.items[it].selected && menu.items[it].choosable } ?: choosable.firstOrNull() ?: -1)
    }
    val focus = remember(menu) { FocusRequester() }
    Popup(
        offset = IntOffset((menu.x * density).roundToInt(), ((menu.y + menu.height) * density).roundToInt()),
        onDismissRequest = { choose(null) },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(IntrinsicSize.Max).widthIn(min = menu.width.dp).heightIn(max = 320.dp)
                .background(Color.White).border(1.dp, Color(0xFF9AA0A6))
                .focusRequester(focus).focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> choosable.firstOrNull { it > highlighted }?.let { highlighted = it }
                        Key.DirectionUp -> choosable.lastOrNull { it < highlighted }?.let { highlighted = it }
                        Key.Enter, Key.NumPadEnter, Key.Spacebar -> choose(highlighted.takeIf { it >= 0 })
                        Key.Escape -> choose(null)
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .verticalScroll(rememberScrollState()),
        ) {
            menu.items.forEachIndexed { i, item ->
                val style = TextStyle(
                    color = if (item.choosable || item.groupLabel) Color(0xFF202124) else Color(0xFF9AA0A6),
                    fontSize = 14.sp,
                    fontWeight = if (item.groupLabel) FontWeight.Bold else FontWeight.Normal,
                )
                BasicText(
                    item.label,
                    Modifier.fillMaxWidth()
                        .background(if (i == highlighted) Color(0xFFD2E3FC) else Color.Transparent)
                        .clickable(enabled = item.choosable) { choose(i) }
                        .padding(start = if (item.groupChild) 20.dp else 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    style = style,
                )
            }
        }
        LaunchedEffect(menu) { focus.requestFocus() }
    }
}

/** A pointer event over the surface as WPE's, its position in logical pixels: [scale] surface pixels each. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun forwardPointer(view: WpeView, event: PointerEvent, scale: Double, scrollSign: Double) {
    val change = event.changes.firstOrNull() ?: return
    val x = change.position.x / scale
    val y = change.position.y / scale
    val k = event.keyboardModifiers
    val modifiers = KeyMapping.modifiers(k.isShiftPressed, k.isCtrlPressed, k.isAltPressed, k.isMetaPressed) or
        KeyMapping.buttons(event.buttons.isPrimaryPressed, event.buttons.isTertiaryPressed, event.buttons.isSecondaryPressed)
    when (event.type) {
        PointerEventType.Move -> view.pointer(WpeNative.POINTER_MOVE, x, y, 0, modifiers)
        PointerEventType.Enter -> view.pointer(WpeNative.POINTER_ENTER, x, y, 0, modifiers)
        PointerEventType.Exit -> view.pointer(WpeNative.POINTER_LEAVE, x, y, 0, modifiers)
        PointerEventType.Press -> view.pointer(WpeNative.POINTER_DOWN, x, y, buttonOf(event.button), modifiers)
        PointerEventType.Release -> view.pointer(WpeNative.POINTER_UP, x, y, buttonOf(event.button), modifiers)
        PointerEventType.Scroll -> {
            val delta = change.scrollDelta
            view.scroll(delta.x * scrollSign, delta.y * scrollSign, x, y, modifiers)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun buttonOf(button: PointerButton?): Int = when (button) {
    PointerButton.Secondary -> KeyMapping.BUTTON_SECONDARY
    PointerButton.Tertiary -> KeyMapping.BUTTON_MIDDLE
    else -> KeyMapping.BUTTON_PRIMARY
}

/** A key event as WPE's: true if WPE has the key, which then is the page's alone. */
internal fun forwardKey(view: WpeView, event: KeyEvent): Boolean {
    val down = when (event.type) {
        KeyEventType.KeyDown -> true
        KeyEventType.KeyUp -> false
        else -> return false
    }
    val key = KeyMapping.keyFor(event.key.nativeKeyCode, KeyMapping.codePointOf(event.utf16CodePoint), event.isShiftPressed) ?: return false
    val modifiers = KeyMapping.modifiers(event.isShiftPressed, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed)
    view.key(down, key.keyval, key.keycode, modifiers)
    return true
}

/** The AWT cursor for a CSS cursor name, as WebKit gives them (L5); the default for any other. */
internal fun cursorTypeFor(name: String?): Int = when (name) {
    "pointer" -> Cursor.HAND_CURSOR
    "text", "vertical-text" -> Cursor.TEXT_CURSOR
    "wait", "progress" -> Cursor.WAIT_CURSOR
    "crosshair", "cell" -> Cursor.CROSSHAIR_CURSOR
    "move", "all-scroll", "grab", "grabbing" -> Cursor.MOVE_CURSOR
    "n-resize", "ns-resize", "row-resize" -> Cursor.N_RESIZE_CURSOR
    "s-resize" -> Cursor.S_RESIZE_CURSOR
    "e-resize", "ew-resize", "col-resize" -> Cursor.E_RESIZE_CURSOR
    "w-resize" -> Cursor.W_RESIZE_CURSOR
    "ne-resize", "nesw-resize" -> Cursor.NE_RESIZE_CURSOR
    "nw-resize", "nwse-resize" -> Cursor.NW_RESIZE_CURSOR
    "se-resize" -> Cursor.SE_RESIZE_CURSOR
    "sw-resize" -> Cursor.SW_RESIZE_CURSOR
    else -> Cursor.DEFAULT_CURSOR
}
