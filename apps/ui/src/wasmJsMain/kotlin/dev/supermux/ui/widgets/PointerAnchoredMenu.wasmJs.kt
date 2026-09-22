package dev.supermux.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import dev.supermux.ui.adaptive.isSecondaryButtonPress

/**
 * The web stand-in for foundation's `ContextMenuArea` (desktop's actual), which has no web target:
 * a right-click anywhere over [content] opens [menu] as a material3 `DropdownMenu` anchored at the
 * pointer.
 *
 * Matches the repo's own right-click idiom (`HostBadge`, `TerminalTabs`): the press is read on the
 * **Initial** pass through the `isSecondaryButtonPress` seam, and the REST of the gesture is drained
 * and consumed so opening the menu does not also click whatever is underneath.
 *
 * `propagateMinConstraints = true` mirrors `ContextMenuArea`, so wrapping a row does not shrink it.
 */
@Composable
internal fun PointerAnchoredMenu(
    content: @Composable () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(DpOffset.Zero) }
    var width by remember { mutableStateOf(0) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = Modifier.onSizeChanged { width = it.width }.pointerInput(rtl) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Initial)
                    if (!down.isSecondaryButtonPress()) continue
                    val position = down.changes.firstOrNull()?.position ?: continue
                    // material3's MenuPosition measures the offset from the anchor's START edge,
                    // which in RTL is its RIGHT edge (x = anchorLeft + anchorWidth - menuWidth -
                    // offset.x), so the pointer sits at `anchorWidth - x` there, not at `-x`.
                    val x = if (rtl) width - position.x else position.x
                    at = DpOffset(x.toDp(), position.y.toDp())
                    open = true
                    down.changes.forEach { it.consume() }
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        },
        propagateMinConstraints = true,
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = at) {
            menu { open = false }
        }
    }
}
