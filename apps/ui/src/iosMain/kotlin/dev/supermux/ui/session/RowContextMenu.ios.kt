package dev.supermux.ui.session

import androidx.compose.runtime.Composable

/** No right-click menu on iOS: the row's overflow menu and its swipe actions carry these entries,
 *  exactly as on Android. (UIKit's long-press `UIContextMenuInteraction` is a different gesture and
 *  would fight the row's own long-press; a Compose-native version is a later cluster's call.) */
@Composable
actual fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
) {
    content()
}

/** Passthrough above → rows must keep carrying their actions visibly. */
actual val platformContextMenuAvailable: Boolean = false
