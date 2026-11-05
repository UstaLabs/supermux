package dev.supermux.ui.panes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Live tear-off, the pane layer's side of it: a tab dragged out of a window opens in a window of
 * its own that follows the pointer, and a window's only tab drags the window itself — the way a
 * browser does it. The host (desktop) owns windows and the pointer outside them; this layer only
 * hands a drag over ([takeOverOnStart] / [takeOverOutside]) and registers itself as a place a tab
 * dragged in ANOTHER window can land ([register]).
 *
 * One per window: it knows which window and which workspace its [PaneHost] shows.
 */
interface TabTearOff {
    /** [endpoint] is the pane layer this window shows now; it replaces any earlier one. */
    fun register(endpoint: TabDropEndpoint)

    /** [endpoint] left composition. Ignored unless it is the one registered. */
    fun unregister(endpoint: TabDropEndpoint)

    /**
     * A mouse drag of a tab just passed the click threshold. True when the host takes it from
     * here: dragging a window's only tab moves that window.
     */
    fun takeOverOnStart(handoff: TabHandoff): Boolean

    /**
     * A mouse drag of a tab is past the window's edge. True when the host takes it from here: it
     * opened the tab in a window of its own, which now follows the pointer.
     */
    fun takeOverOutside(handoff: TabHandoff): Boolean
}

/** A tab drag as the pane layer hands it to a [TabTearOff] host. Positions in this window's root px. */
data class TabHandoff(
    val viewId: String,
    /** What the tab is labelled; a window it is dragged over shows it on the ghost. */
    val label: String,
    /** The group the drag started in, the tab's index there, and that group's active tab — so Esc can put it all back. */
    val groupId: String,
    val index: Int,
    val activeViewId: String?,
    /** Where the pointer holds the tab, from the tab's top-left. */
    val grab: Offset,
    /** The tab's own size. */
    val tabSize: Size,
    /** The group's strip and body together: a window opened for the tab gets this content size. */
    val groupSize: Size,
    /** The pointer now. */
    val pointer: Offset,
)

/**
 * One window's pane layer as a place a dragged tab can land: [PaneHost] builds it over its own
 * [PaneDragController], drop and edit paths, and registers it with its window's [TabTearOff].
 */
class TabDropEndpoint internal constructor(
    internal val drag: PaneDragController,
    private val onDrop: (PaneDropTarget) -> Unit,
    private val onDocked: (viewId: String) -> Unit,
    private val onRestore: (viewId: String, groupId: String, index: Int, activeViewId: String?) -> Unit,
) {
    /** Apply a drop this layer's [drag] resolved: the tree edit, then this window takes the view. */
    internal fun drop(target: PaneDropTarget) = onDrop(target)

    /** This window takes [viewId] where the tree already has it (no edit) — a drop on an empty canvas. */
    internal fun docked(viewId: String) = onDocked(viewId)

    /** Put [viewId] back at [index] of [groupId] with [activeViewId] active, and take it into this window. */
    internal fun restore(viewId: String, groupId: String, index: Int, activeViewId: String?) =
        onRestore(viewId, groupId, index, activeViewId)
}
