package dev.supermux.ui.session

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable

@Composable
actual fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
) {
    ContextMenuArea(
        items = { items().map { entry -> ContextMenuItem(entry.label) { entry.onClick() } } },
        content = content,
    )
}
