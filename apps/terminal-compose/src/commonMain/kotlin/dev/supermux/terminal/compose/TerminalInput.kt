package dev.supermux.terminal.compose

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import dev.supermux.terminal.KeyAction
import dev.supermux.terminal.Modifiers
import dev.supermux.terminal.MouseAction
import dev.supermux.terminal.MouseButton
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalModes
import dev.supermux.terminal.TerminalMouse
import dev.supermux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything the user does to one [Terminal], routed to whoever the negotiated modes say owns it.
 *
 * It holds the input layer's small amount of state — which keys are held, which pointer is down and
 * where it went down, what the accessory bar has armed — and nothing about drawing. Every decision
 * it makes about a pointer goes through [TerminalInputPolicy], which is pure; every byte it
 * produces is produced by the ENGINE's encoder from a [TerminalKey] / [TerminalMouse] / paste, never
 * written here.
 *
 * **Returning to the bottom.** Any input the user sends returns the surface to live-follow: a
 * program being typed at has to show what it prints. History gestures do not, obviously, and neither
 * does a mouse event the program asked for — a click in `htop` is not a reason to jump.
 */
@Stable
internal class TerminalInputController(
    private val session: TerminalSession,
    private val model: ViewportModel,
    private val scroll: ScrollController,
    private val accessories: TerminalAccessoryState,
    private val scope: CoroutineScope,
    val focusRequester: FocusRequester,
) : TerminalAccessoryState.Sink {

    /** The cell box every hit test is measured against; the surface keeps it current. */
    var metrics: CellMetrics = CellMetrics(1f, 1f, 1f, 1f)

    /** False for an inactive surface: no keys, no pointer, no focus reports. */
    var enabled: Boolean = true

    /** An OSC 8 hyperlink the user activated with a plain click. */
    var onLink: (String) -> Unit = {}

    val router = TerminalKeyRouter(
        send = ::sendKey,
        armedModifiers = { accessories.armedModifiers },
        onSubmitted = ::afterLocalInput,
    )

    private var press: PressTracker? = null

    // ----------------------------------------------------------------- keyboard ----

    fun onKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean =
        enabled && router.handle(event)

    fun onFocusChanged(focused: Boolean) {
        session.focus(focused)
        // A modifier armed against a terminal the user has left must not fire into the next one,
        // and a key held while focus moved will never produce its key-up here.
        accessories.clear()
        router.reset()
    }

    // ----------------------------------------------------------------- accessory bar ----

    override fun key(key: TerminalKey) {
        sendKey(key)
        if (key.action != KeyAction.RELEASE) afterLocalInput()
    }

    override fun paste(text: String, allowUnsafe: Boolean, onResult: (Boolean) -> Unit) {
        if (text.isEmpty()) {
            onResult(true)
            return
        }
        scope.launch {
            // The engine decides everything about a paste: bracketed-paste wrapping (once, from
            // mode 2004), newline conversion and whether the text is safe to send at all.
            val sent = runCatching { session.paste(text, allowUnsafe) }.getOrDefault(false)
            if (sent) afterLocalInput()
            onResult(sent)
        }
    }

    private fun sendKey(key: TerminalKey) {
        session.key(key)
    }

    /** Local input: disarm the accessory modifiers and come back to the newest output. */
    private fun afterLocalInput() {
        accessories.clear()
        if (!scroll.following) scroll.followBottom()
    }

    // ----------------------------------------------------------------- pointer ----

    /**
     * The pointer loop.
     *
     * It runs in the MAIN pass of a node placed INSIDE `Modifier.scrollable`, so it sees every
     * gesture first and decides who gets it: what it consumes never reaches the scrollable (that is
     * how a wheel notch the program asked for stops scrolling this surface's history), and what it
     * leaves alone is handled by the scrollable exactly as before — including the platform's own
     * wheel-to-pixel normalization and the fling, which this layer has no business re-deriving.
     */
    suspend fun handlePointer(pointerScope: PointerInputScope) = with(pointerScope) {
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                val waiting = press?.takeIf { !it.longPressFired }
                val event = if (waiting == null) {
                    awaitPointerEvent(PointerEventPass.Main)
                } else {
                    val remaining = longPressTimeout - (waiting.lastUptimeMillis - waiting.downUptimeMillis)
                    if (remaining <= 0L) {
                        fireLongPress(waiting)
                        continue
                    }
                    withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Main) }
                        ?: run { fireLongPress(waiting); continue }
                }
                if (!enabled) continue
                when (event.type) {
                    PointerEventType.Scroll -> onScroll(event)
                    PointerEventType.Press -> onPress(event)
                    PointerEventType.Release -> onRelease(event)
                    PointerEventType.Move -> onMove(event, slop)
                    else -> Unit
                }
            }
        }
    }

    private fun modes(): TerminalModes = model.frame?.modes ?: NO_MODES

    private fun modifiersOf(event: PointerEvent): Int {
        var modifiers = accessories.armedModifiers
        val keyboard = event.keyboardModifiers
        if (keyboard.isShiftPressed) modifiers = modifiers or Modifiers.SHIFT
        if (keyboard.isCtrlPressed) modifiers = modifiers or Modifiers.CTRL
        if (keyboard.isAltPressed) modifiers = modifiers or Modifiers.ALT
        if (keyboard.isMetaPressed) modifiers = modifiers or Modifiers.SUPER
        return modifiers
    }

    private fun deviceOf(change: PointerInputChange): PointerDevice =
        if (change.type == PointerType.Mouse) PointerDevice.MOUSE else PointerDevice.TOUCH

    /** The viewport cell under [position], or null before the first frame. */
    private fun cellOf(position: Offset): TerminalCellPosition? {
        val frame = model.frame ?: return null
        return cellAt(
            position = position,
            metrics = metrics,
            scrollOffsetPx = scroll.paintOffset(frame),
            columns = frame.size.columns,
            rows = frame.size.rows,
        )
    }

    private fun send(cell: TerminalCellPosition, button: Int, action: Int, modifiers: Int) {
        session.mouse(
            TerminalMouse(
                column = cell.column,
                row = cell.row,
                button = button,
                modifiers = modifiers,
                action = action,
            ),
        )
    }

    private fun AwaitPointerEventScope.onScroll(event: PointerEvent) {
        val change = event.changes.lastOrNull() ?: return
        val modifiers = modifiersOf(event)
        val route = TerminalInputPolicy.route(
            modes = modes(),
            intent = PointerIntent.WHEEL,
            device = deviceOf(change),
            modifiers = modifiers,
        )
        // Anything but a remote wheel is the scrollable's: leave the event unconsumed and let it
        // normalize the notch, track the velocity and run the fling.
        if (route != PointerRoute.REMOTE_MOUSE) return
        val button = TerminalInputPolicy.wheelButton(change.scrollDelta)
        val cell = cellOf(change.position)
        if (button != MouseButton.NONE && cell != null) {
            // xterm's wheel "buttons" are press events; there is no release for a notch.
            repeat(TerminalInputPolicy.wheelNotches(change.scrollDelta)) {
                send(cell, button, MouseAction.PRESS, modifiers)
            }
        }
        for (candidate in event.changes) candidate.consume()
    }

    private fun AwaitPointerEventScope.onPress(event: PointerEvent) {
        val change = event.changes.firstOrNull { it.pressed } ?: return
        val cell = cellOf(change.position) ?: return
        val modifiers = modifiersOf(event)
        val device = deviceOf(change)
        val route = TerminalInputPolicy.route(modes(), PointerIntent.PRESS, device, modifiers)
        val button = buttonOf(event)
        press = PressTracker(
            pointerId = change.id.value,
            downPosition = change.position,
            downUptimeMillis = change.uptimeMillis,
            lastUptimeMillis = change.uptimeMillis,
            cell = cell,
            device = device,
            button = button,
            modifiers = modifiers,
            route = route,
        )
        // Touching a terminal is how a user says "type here"; the host never has to ask for focus.
        runCatching { focusRequester.requestFocus() }
        if (route != PointerRoute.REMOTE_MOUSE) return
        send(cell, button, MouseAction.PRESS, modifiers)
        change.consume()
    }

    private fun AwaitPointerEventScope.onMove(event: PointerEvent, slop: Float) {
        val tracker = press
        val change = event.changes.firstOrNull { it.id.value == tracker?.pointerId }
            ?: event.changes.lastOrNull() ?: return
        val cell = cellOf(change.position) ?: return
        val modifiers = modifiersOf(event)
        val device = deviceOf(change)
        val down = change.pressed
        if (tracker != null && down) {
            tracker.lastUptimeMillis = change.uptimeMillis
            if ((change.position - tracker.downPosition).getDistance() > slop) tracker.moved = true
        }
        val intent = if (down) PointerIntent.DRAG else PointerIntent.HOVER
        // A gesture belongs to whoever its PRESS was routed to, for its whole life: a program that
        // turns mouse reporting off mid-drag must still get the motion and the release of the
        // button it saw go down, or it is left believing that button is still held.
        val route = if (down && tracker?.route == PointerRoute.REMOTE_MOUSE) {
            PointerRoute.REMOTE_MOUSE
        } else {
            TerminalInputPolicy.route(modes(), intent, device, modifiers)
        }
        if (route != PointerRoute.REMOTE_MOUSE) return
        // The engine de-duplicates motion per cell too; not sending it saves a mailbox slot per
        // pixel of a drag, which is the difference between a smooth drag and a saturated queue.
        val last = tracker?.lastRemoteCell
        if (cell == last) {
            change.consume()
            return
        }
        tracker?.lastRemoteCell = cell
        send(cell, if (down) tracker?.button ?: MouseButton.LEFT else MouseButton.NONE, MouseAction.MOTION, modifiers)
        if (change.positionChanged()) change.consume()
    }

    private fun AwaitPointerEventScope.onRelease(event: PointerEvent) {
        val tracker = press
        press = null
        val change = event.changes.firstOrNull { it.id.value == tracker?.pointerId }
            ?: event.changes.lastOrNull() ?: return
        val cell = cellOf(change.position) ?: return
        val modifiers = modifiersOf(event)
        val device = deviceOf(change)
        // Same rule as a drag: the release belongs to whoever the press did.
        val route = if (tracker?.route == PointerRoute.REMOTE_MOUSE) {
            PointerRoute.REMOTE_MOUSE
        } else {
            TerminalInputPolicy.route(modes(), PointerIntent.RELEASE, device, modifiers)
        }
        if (route == PointerRoute.REMOTE_MOUSE) {
            send(cell, tracker?.button ?: MouseButton.LEFT, MouseAction.RELEASE, modifiers)
            change.consume()
            return
        }
        // A plain click that never moved, on a cell the frame says carries an OSC 8 hyperlink.
        if (tracker != null && !tracker.moved && !tracker.longPressClaimed && tracker.cell == cell) {
            linkAt(cell)?.let(onLink)
        }
    }

    private fun fireLongPress(tracker: PressTracker) {
        tracker.longPressFired = true
        // The policy always keeps a long press local — it is the one gesture a touch user keeps
        // when a program has taken the mouse. Claiming the press here is what stops its release
        // from counting as a click, so holding a hyperlink does not open it. The selection a long
        // press starts is Plan 2 Task 5.
        tracker.longPressClaimed =
            TerminalInputPolicy.route(modes(), PointerIntent.LONG_PRESS, tracker.device, tracker.modifiers) !=
            PointerRoute.REMOTE_MOUSE
    }

    private fun linkAt(cell: TerminalCellPosition): String? =
        model.frame?.links?.firstOrNull {
            it.row == cell.row && cell.column >= it.firstColumn && cell.column <= it.lastColumn
        }?.uri

    private fun buttonOf(event: PointerEvent): Int = when {
        event.buttons.isSecondaryPressed -> MouseButton.RIGHT
        event.buttons.isTertiaryPressed -> MouseButton.MIDDLE
        // A finger has no buttons: `buttons` is empty on touch, and a touch IS the primary button.
        else -> MouseButton.LEFT
    }

    private class PressTracker(
        val pointerId: Long,
        val downPosition: Offset,
        val downUptimeMillis: Long,
        var lastUptimeMillis: Long,
        val cell: TerminalCellPosition,
        val device: PointerDevice,
        val button: Int,
        val modifiers: Int,
        val route: PointerRoute,
        var moved: Boolean = false,
        var longPressFired: Boolean = false,
        var longPressClaimed: Boolean = false,
        var lastRemoteCell: TerminalCellPosition? = null,
    )

    private companion object {
        /** What the policy sees before the first frame: a plain shell, nothing negotiated. */
        val NO_MODES = TerminalModes(alternateScreen = false, mouseTracking = false, bracketedPaste = false)
    }
}

/**
 * The input half of the surface's modifier chain: focus, hardware keys and the pointer.
 *
 * ORDER MATTERS. This goes AFTER `Modifier.scrollable` so that the pointer node is the scrollable's
 * descendant and sees the Main pass first — see [TerminalInputController.handlePointer].
 * `onPreviewKeyEvent` is a preview, not a plain handler, so that Tab, the arrows and Escape reach
 * the program instead of moving focus or closing a dialog.
 */
internal fun Modifier.terminalInput(controller: TerminalInputController, enabled: Boolean): Modifier =
    this
        .onPreviewKeyEvent(controller::onKeyEvent)
        .onFocusChanged { controller.onFocusChanged(it.isFocused) }
        .focusRequester(controller.focusRequester)
        .focusable(enabled = enabled)
        .pointerInput(controller) { controller.handlePointer(this) }
