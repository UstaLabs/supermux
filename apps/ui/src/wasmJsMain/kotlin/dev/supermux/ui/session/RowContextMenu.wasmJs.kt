// Right-click → a Material DropdownMenu anchored at the pointer. Compose foundation's
// `ContextMenuArea` (the desktop actual) has no web target, so the menu is built from the
// material3 primitives the rest of `:ui` already uses.
package dev.supermux.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset

@Composable
actual fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(DpOffset.Zero) }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                        val p = ev.changes.first().position
                        at = DpOffset(p.x.toDp(), p.y.toDp())
                        open = true
                        ev.changes.forEach { it.consume() }
                    }
                }
            }
        },
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = at) {
            items().forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

actual val platformContextMenuAvailable: Boolean = true
