package dev.supermux.ui.session

import androidx.compose.runtime.Composable

/** One entry of a row's right-click menu. */
data class RowContextMenuEntry(val label: String, val onClick: () -> Unit)

/**
 * A right-click context menu around a list row.
 *
 * Desktop's `ContextMenuArea` (the sidebar's rename / mute / archive menu); a passthrough on
 * Android, where the same actions live in the row's overflow menu and its swipe actions — there is
 * no right-click on a phone. [items] is a lambda so it is only evaluated when the menu opens,
 * exactly as `ContextMenuArea` expects.
 */
@Composable
expect fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
)
