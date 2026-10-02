// Keeps pane seams still on screen when the whole pane area is resized.
package dev.supermux.ui.panes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntRect
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.layoutNodeAt
import dev.supermux.workspace.resizeLayoutEdges

/**
 * The window's bounds on screen, in the same pixels Compose lays out in, read at
 * call time; null where windows are not resized by dragging an edge (phones,
 * tablets, the browser), which keeps the plain proportional behaviour there.
 */
@Composable
expect fun rememberWindowScreenBounds(): () -> IntRect?

/**
 * Re-fits the layout tree when the pane area changes size, so every seam keeps
 * its place on screen and only the panes on the edge that moved grow or shrink
 * (see [resizeLayoutEdges]).
 *
 * Runs in the LAYOUT pass, not after it: [measure] is called with the area's new
 * constraints before any split is measured, and [PaneSplit] reads [sizesAt]
 * while it measures. An adjustment applied from onSizeChanged / a state write
 * would land a frame late and the seams would wobble for the whole resize.
 *
 * Which edge moved: the pane area's right and bottom edges follow the window's
 * (nothing resizable sits to their right or below), so their movement is the
 * window's; whatever size change is left over happened at the left/top edge —
 * the window's own left/top edge, the sidebar, or both.
 *
 * The adjusted tree is only a rendering until [PaneHost] commits it (debounced
 * on [tick]); once the committed tree comes back, [measure] rebases on it.
 */
internal class PaneAreaFit {
    private var base: LayoutNode? = null
    private var refW = 0
    private var refH = 0
    private var refWin: IntRect? = null

    /** The tree fitted to the current size, or null when it equals the tree. */
    var adjusted: LayoutNode? = null
        private set

    /** Bumped whenever a placement carries a pending [adjusted]; drives the commit. */
    var tick by mutableIntStateOf(0)

    fun measure(tree: LayoutNode, w: Int, h: Int, win: IntRect?, minPx: Double) {
        // A hidden keep-alive layer measures at 0×0; that is not a resize.
        if (w <= 0 || h <= 0) return
        if (win == null || base == null || base != tree || refWin == null) {
            base = tree
            refW = w
            refH = h
            refWin = win
            adjusted = null
            return
        }
        val rw = refWin!!
        // A window that only MOVED shifts everything alike: not an edge move.
        val winDx = if (win.width != rw.width) win.left - rw.left else 0
        val winDy = if (win.height != rw.height) win.top - rw.top else 0
        val endShiftX = winDx + (win.width - rw.width)
        val endShiftY = winDy + (win.height - rw.height)
        val startShiftX = endShiftX - (w - refW)
        val startShiftY = endShiftY - (h - refH)
        val fitted = resizeLayoutEdges(
            tree,
            refW.toDouble(), w.toDouble(), startShiftX.toDouble(),
            refH.toDouble(), h.toDouble(), startShiftY.toDouble(),
            minPx,
        )
        adjusted = fitted.takeIf { it != tree }
    }

    /** Sizes to draw for the split at [path], when a fit is pending. */
    fun sizesAt(path: List<Int>): List<Double>? =
        adjusted?.let { layoutNodeAt(it, path) as? LayoutNode.Split }?.sizes
}

internal val LocalPaneAreaFit = staticCompositionLocalOf<PaneAreaFit?> { null }
