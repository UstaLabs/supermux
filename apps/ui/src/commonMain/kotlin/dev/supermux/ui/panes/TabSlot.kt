package dev.supermux.ui.panes

import androidx.compose.runtime.Immutable

/**
 * What the pane layer knows about one tab while it draws it. The slot decides how that looks.
 *
 * [selected] is the group's active item.
 *
 * [dragged] is true for a tab's own slot while the tab is held past the threshold, or easing into
 * place after a drop. The layer draws that slot at alpha 0 and draws the tab lifted above the
 * strips with [dragged] false, so a slot cannot make the dragged tab *look* different — this is
 * only useful to skip work while hidden, such as pausing a spinner.
 */
@Immutable
data class TabSlotState(val selected: Boolean, val dragged: Boolean)
