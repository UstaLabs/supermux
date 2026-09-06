// Cluster G2 (review fix): the ONE pointer→remote route for a VNC surface, shared by both hosts'
// DisplayPanel (and by G4's shared one). Android used to get this from a TextureView's
// OnTouchListener and desktop from its own awaitPointerEvent loop; both are this modifier now.
package dev.supermux.ui.display

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import dev.supermux.display.VncInput

/**
 * Forward pointer events over this node to a VNC server as RFB `PointerEvent`s.
 *
 * [viewSize] is the node's own on-screen pixel size and [remoteSize] the framebuffer's — the two
 * `VncInput.mapToRemote` needs, read through lambdas so the gesture loop (installed once per [key])
 * always sees the current values. [send] gets remote pixels and the RFB button mask (1 = left
 * button down, 0 = up); it is called from the gesture coroutine, so a host that must suspend
 * launches its own job. [onPress] fires before the mapping on every press (desktop takes keyboard
 * focus there).
 *
 * Three rules, all of them bugs the pre-G2 code had on one host or the other:
 *  - a CONSUMED change is ignored: the surface's own overlay buttons (Ctrl+Alt+Del, the keyboard
 *    toggle) sit inside this node, and Compose still delivers what they consumed to their ancestor
 *    — without this, tapping them also clicks the remote desktop.
 *  - the mask follows the PRIMARY button: a mouse/stylus reports its buttons (so hover moves the
 *    remote cursor with mask 0 instead of dragging, and a right-press is not a left-press); a
 *    finger reports none, so a pressed touch IS the left button.
 *  - a CANCELLED gesture releases: if the loop is torn down (a parent takes the gesture over, the
 *    pane leaves the composition) while the button is down, the remote would otherwise keep it
 *    held forever. This is the old `ACTION_CANCEL -> mask 0` branch.
 *
 * LIMITATION (recorded in cluster G3, from the G2 review): both hosts pass a [send] that launches
 * on `rememberCoroutineScope()`, so on the LEAVE-COMPOSITION path that release is dropped — the
 * scope is cancelled in the same dispose that tears this loop down. It is harmless as wired,
 * because the only way to leave the composition here also disposes the pane, and the pane's own
 * dispose stops the VNC client (the server drops the whole connection's button state with it). A
 * caller that keeps a client alive ACROSS a pane's disposal must send the release on a scope that
 * outlives the composition, or the remote keeps the button held.
 */
fun Modifier.vncPointerInput(
    key: Any?,
    viewSize: () -> IntSize,
    remoteSize: () -> Pair<Int, Int>?,
    onPress: () -> Unit = {},
    send: (x: Int, y: Int, mask: Int) -> Unit,
): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        // Where the button went down, so a cancelled gesture can release it at that same point.
        var heldAt: Pair<Int, Int>? = null
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                if (event.type == PointerEventType.Press) onPress()
                val primaryDown = event.buttons.isPrimaryPressed ||
                    (!event.buttons.areAnyPressed && event.changes.any { it.pressed })
                val mask = when (event.type) {
                    PointerEventType.Press, PointerEventType.Move -> if (primaryDown) 1 else 0
                    PointerEventType.Release -> 0
                    else -> continue
                }
                val remote = remoteSize() ?: continue
                val vs = viewSize()
                val (rx, ry) = VncInput.mapToRemote(
                    change.position.x, change.position.y, vs.width, vs.height, remote.first, remote.second,
                )
                heldAt = if (mask == 1) rx to ry else null
                send(rx, ry, mask)
            }
        } finally {
            heldAt?.let { (x, y) -> send(x, y, 0) }
        }
    }
}
