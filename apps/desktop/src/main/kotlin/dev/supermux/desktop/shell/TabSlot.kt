package dev.supermux.desktop.shell

import androidx.compose.runtime.Immutable

/**
 * What the pane layer knows about one tab while it draws it. The slot decides how that looks.
 *
 * [selected] is the group's active item.
 *
 * [dragged] is true for the source tab while a drag is past the threshold. The layer draws the slot
 * at alpha 0 in that state, so a slot cannot make the dragged tab *look* different — this is only
 * useful to skip work while hidden, such as pausing a spinner.
 */
@Immutable
data class TabSlotState(val selected: Boolean, val dragged: Boolean)
