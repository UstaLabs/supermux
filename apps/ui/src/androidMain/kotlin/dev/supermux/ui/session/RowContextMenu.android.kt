package dev.supermux.ui.session

import androidx.compose.runtime.Composable

/** No right-click on a phone: the row's overflow menu and swipe actions carry these entries. */
@Composable
actual fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
) {
    content()
}
