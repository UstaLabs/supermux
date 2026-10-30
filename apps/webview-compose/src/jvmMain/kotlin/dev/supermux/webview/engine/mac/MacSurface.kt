package dev.supermux.webview.engine.mac

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize
import dev.supermux.webview.drawCover
import dev.supermux.webview.runtime.LocalWebViewOverlays
import dev.supermux.webview.runtime.LocalWebViewRuntime
import dev.supermux.webview.runtime.windowOverlays
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/** What a page's [MacSurfaceNode] draws over its own bounds (D1, D2). */
internal sealed interface SurfaceDraw {
    /** Nothing: the slot isn't placed, or the node isn't its engine's surface. */
    data object Nothing : SurfaceDraw

    /** A hole in the canvas: the page under it shows. */
    data object Hole : SurfaceDraw

    /** The page's last snapshot over the slot, while it moves. */
    class Cover(val image: ImageBitmap) : SurfaceDraw
}

/** What a [MacSurfaceNode] asks of its engine: `MacWebEngine`; tests fake it. Main thread. */
internal interface SurfaceHost {
    /** The `NSWindow` handle of a composition's AWT window, 0 for none (M8). */
    fun windowHandleOf(window: java.awt.Window?): Long

    fun surfaceAttached(node: MacSurfaceNode)

    fun surfaceDetached(node: MacSurfaceNode)

    /**
     * The slot was laid out at [bounds], [visible] of it (both window px), at [density], in [window]. True when what the
     * node draws changed: it redraws in this frame.
     */
    fun surfacePlaced(node: MacSurfaceNode, window: Long, bounds: Rect, visible: Rect, density: Float): Boolean

    /** The slot is no longer placed. True when what the node draws changed. */
    fun surfaceUnplaced(node: MacSurfaceNode): Boolean

    fun drawFor(node: MacSurfaceNode): SurfaceDraw
}

/** The engine's [MacSurfaceNode]; [inList]: an embed in a scrolling list (D7). */
internal class MacSurfaceElement(private val host: SurfaceHost, private val inList: Boolean) : ModifierNodeElement<MacSurfaceNode>() {
    override fun create(): MacSurfaceNode = MacSurfaceNode(host, inList)

    override fun update(node: MacSurfaceNode) {
        if (node.host === host && node.inList == inList) return
        if (node.isAttached) node.host.surfaceDetached(node)
        node.host = host
        node.inList = inList
        if (node.isAttached) host.surfaceAttached(node)
    }

    override fun equals(other: Any?): Boolean = other is MacSurfaceElement && other.host === host && other.inList == inList

    override fun hashCode(): Int = 31 * System.identityHashCode(host) + inList.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "webViewMacSurface"
        properties["inList"] = inList
    }
}

/**
 * One node for the page's slot under the canvas, like the runtime's `HostNode`:
 * - it reports the slot's place from `onGloballyPositioned`, which runs in the layout pass, so the engine can switch
 *   to the motion cover before this frame is drawn (D2), and redraws in this frame when the engine says so;
 * - it reports a slot no longer placed (a lazy list's retained item), and its leaving the composition;
 * - it draws its engine's [SurfaceDraw] under its content;
 * - [dismissPopupsAt] closes Compose's popups when the page took a press there (D7).
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class MacSurfaceNode(var host: SurfaceHost, var inList: Boolean) :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    UnplacedAwareModifierNode,
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var waitingForWindow: Job? = null

    override fun onAttach() = host.surfaceAttached(this)

    override fun onDetach() {
        waitingForWindow = null
        host.surfaceDetached(this)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val density = currentValueOf(LocalDensity).density
        val bounds = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
        report(bounds, coordinates.boundsInWindow(), density)
    }

    private fun report(bounds: Rect, visible: Rect, density: Float) {
        val window = host.windowHandleOf(currentValueOf(LocalAwtWindow))
        if (window == 0L) {
            waitForWindow(bounds, visible, density)
            return
        }
        waitingForWindow?.cancel()
        waitingForWindow = null
        if (host.surfacePlaced(this, window, bounds, visible, density)) invalidateDraw()
    }

    /** skiko's layer joins the window about the first frame (M8): until it has, the slot is reported again each frame. */
    private fun waitForWindow(bounds: Rect, visible: Rect, density: Float) {
        waitingForWindow?.cancel()
        waitingForWindow = coroutineScope.launch {
            while (isAttached) {
                withFrameNanos { }
                if (host.windowHandleOf(currentValueOf(LocalAwtWindow)) != 0L) {
                    report(bounds, visible, density)
                    return@launch
                }
            }
        }
    }

    override fun onUnplaced() {
        if (host.surfaceUnplaced(this)) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        when (val draw = host.drawFor(this@MacSurfaceNode)) {
            SurfaceDraw.Hole -> drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            is SurfaceDraw.Cover -> drawCover(draw.image)
            SurfaceDraw.Nothing -> Unit
        }
        drawContent()
    }

    /** What the engine says to draw changed outside a layout pass (a settle, a cover's refresh, a paint). */
    fun redraw() {
        if (isAttached) invalidateDraw()
    }

    /**
     * The page took a press at ([xPoints], [yPoints]), points from its window content's top-left, which Compose never
     * saw. While the window's overlay registry has a marked overlay (an open menu, say), the same press and its release
     * go to the Compose component there, so the popup sees a press outside itself and closes (D7). The events go to the
     * component directly, never through the native queue, so AWT doesn't take first responder from the page.
     */
    fun dismissPopupsAt(xPoints: Double, yPoints: Double) {
        if (!isAttached) return
        val overlays = windowOverlays(currentValueOf(LocalWebViewOverlays), currentValueOf(LocalWebViewRuntime))
        if (overlays.shapes.isEmpty()) return
        val window = currentValueOf(LocalAwtWindow) as? RootPaneContainer ?: return
        val pane = window.contentPane
        val x = xPoints.toInt()
        val y = yPoints.toInt()
        val target = SwingUtilities.getDeepestComponentAt(pane, x, y) ?: return
        val at = SwingUtilities.convertPoint(pane, x, y, target)
        val now = System.currentTimeMillis()
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, at.x, at.y, 1, false, MouseEvent.BUTTON1))
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_RELEASED, now, 0, at.x, at.y, 1, false, MouseEvent.BUTTON1))
    }
}
