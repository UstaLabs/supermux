package dev.supermux.ui.session

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.supermux.ui.widgets.PointerAnchoredMenu

@Composable
actual fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
) {
    PointerAnchoredMenu(content = content) { dismiss ->
        // `items` is a lambda so it is only evaluated once the menu is open, exactly as the
        // desktop `ContextMenuArea` actual treats it.
        items().forEach { entry ->
            DropdownMenuItem(
                text = { Text(entry.label) },
                onClick = {
                    dismiss()
                    entry.onClick()
                },
            )
        }
    }
}

actual val platformContextMenuAvailable: Boolean = true
