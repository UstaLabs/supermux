package dev.supermux.webview.engine.mac

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import dev.supermux.webview.drawCover
import dev.supermux.webview.runtime.LocalWebViewOverlays
import dev.supermux.webview.runtime.LocalWebViewRuntime
import dev.supermux.webview.runtime.windowOverlays
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.Component
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

    /**
     * The page's last snapshot over the slot, while it is redrawn at a new size, or once it has crashed: at its true
     * size, [scale] window px a pixel of [image], top-left, the rest [background], the page's own (`drawCover`).
     */
    class Cover(val image: ImageBitmap, val scale: Float, val background: Color) : SurfaceDraw
}

/** What a [MacSurfaceNode] asks of its engine: `MacWebEngine`; tests fake it. Main thread. */
internal interface SurfaceHost {
    /** The `NSWindow` handle of a composition's AWT window, 0 for none (M8). */
    fun windowHandleOf(window: java.awt.Window?): Long

    /** The window's backing scale, window px a point (I3); 0 when it has none to say. */
    fun scaleOf(window: java.awt.Window?): Float

    fun surfaceAttached(node: MacSurfaceNode)

    fun surfaceDetached(node: MacSurfaceNode)

    /**
     * The slot was laid out at [bounds], [visible] of it (both window px), in [window], whose backing scale is
     * [scale] window px a point. True when what the node draws changed: it redraws in this frame.
     */
    fun surfacePlaced(node: MacSurfaceNode, window: Long, bounds: Rect, visible: Rect, scale: Float): Boolean

    /** The slot is no longer placed. True when what the node draws changed. */
    fun surfaceUnplaced(node: MacSurfaceNode): Boolean

    fun drawFor(node: MacSurfaceNode): SurfaceDraw
}

/** Hands a press the page took to the Compose window at ([x], [y]), points from its content's top-left (D7). */
internal fun interface SyntheticPresses {
    fun press(window: java.awt.Window?, x: Int, y: Int)
}

/** A press the page took, handed to Compose: the surface node takes it, so nothing under the page does (I1). */
internal class SyntheticPress(source: Component, id: Int, time: Long, modifiers: Int, x: Int, y: Int) :
    MouseEvent(source, id, time, modifiers, x, y, 1, false, BUTTON1)

/**
 * The press and its release, to the Compose component at ([x], [y]) directly, never through the native queue, so AWT
 * doesn't take first responder from the page.
 */
internal object AwtSyntheticPresses : SyntheticPresses {
    override fun press(window: java.awt.Window?, x: Int, y: Int) {
        val pane = (window as? RootPaneContainer)?.contentPane ?: return
        val target = SwingUtilities.getDeepestComponentAt(pane, x, y) ?: return
        val at = SwingUtilities.convertPoint(pane, x, y, target)
        val now = System.currentTimeMillis()
        target.dispatchEvent(SyntheticPress(target, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, at.x, at.y))
        target.dispatchEvent(SyntheticPress(target, MouseEvent.MOUSE_RELEASED, now, 0, at.x, at.y))
    }
}

/** The engine's [MacSurfaceNode]; [inList]: an embed in a scrolling list (D7). [presses]: for the tests. */
internal class MacSurfaceElement(
    private val host: SurfaceHost,
    private val inList: Boolean,
    private val presses: SyntheticPresses = AwtSyntheticPresses,
) : ModifierNodeElement<MacSurfaceNode>() {
    override fun create(): MacSurfaceNode = MacSurfaceNode(host, inList, presses)

    override fun update(node: MacSurfaceNode) {
        node.presses = presses
        if (node.host === host && node.inList == inList) return
        if (node.isAttached) node.host.surfaceDetached(node)
        node.host = host
        node.inList = inList
        if (node.isAttached) host.surfaceAttached(node)
    }

    override fun equals(other: Any?): Boolean =
        other is MacSurfaceElement && other.host === host && other.inList == inList && other.presses === presses

    override fun hashCode(): Int = 31 * (31 * System.identityHashCode(host) + inList.hashCode()) + System.identityHashCode(presses)

    override fun InspectorInfo.inspectableProperties() {
        name = "webViewMacSurface"
        properties["inList"] = inList
    }
}

/**
 * One node for the page's slot under the canvas, like the runtime's `HostNode`:
 * - it reports the slot's place from `onGloballyPositioned`, which runs in the layout pass, so the engine can switch
 *   to the motion cover before this frame is drawn (D2), and redraws in this frame when the engine says so. The slot
 *   is where Compose draws it, through every layer's move and scale, at the window's backing scale (I3);
 * - it reports a slot no longer placed (a lazy list's retained item), and its leaving the composition;
 * - it draws its engine's [SurfaceDraw] under its content;
 * - [dismissPopupsAt] closes Compose's popups when the page took a press there (D7), and the node takes that press
 *   itself, so nothing under the page does (I1).
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class MacSurfaceNode(var host: SurfaceHost, var inList: Boolean, var presses: SyntheticPresses = AwtSyntheticPresses) :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    UnplacedAwareModifierNode,
    DrawModifierNode,
    PointerInputModifierNode,
    CompositionLocalConsumerModifierNode {
    private var waitingForWindow: Job? = null

    override fun onAttach() = host.surfaceAttached(this)

    override fun onDetach() {
        waitingForWindow = null
        host.surfaceDetached(this)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        // Both corners through every layer: a scaled ancestor draws the slot smaller, and the page goes where it's drawn.
        val topLeft = coordinates.positionInWindow()
        val bottomRight = coordinates.localToWindow(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        val bounds = Rect(topLeft, bottomRight)
        report(bounds, coordinates.boundsInWindow())
    }

    private fun report(bounds: Rect, visible: Rect) {
        val awtWindow = currentValueOf(LocalAwtWindow)
        val window = host.windowHandleOf(awtWindow)
        if (window == 0L) {
            waitForWindow(bounds, visible)
            return
        }
        waitingForWindow?.cancel()
        waitingForWindow = null
        // Compose's window pixels are the window's backing pixels, whatever density the app lays itself out at (I3).
        val scale = host.scaleOf(awtWindow).takeIf { it > 0f } ?: currentValueOf(LocalDensity).density
        if (host.surfacePlaced(this, window, bounds, visible, scale)) invalidateDraw()
    }

    /** skiko's layer joins the window about the first frame (M8): until it has, the slot is reported again each frame. */
    private fun waitForWindow(bounds: Rect, visible: Rect) {
        waitingForWindow?.cancel()
        waitingForWindow = coroutineScope.launch {
            while (isAttached) {
                withFrameNanos { }
                if (host.windowHandleOf(currentValueOf(LocalAwtWindow)) != 0L) {
                    report(bounds, visible)
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
            is SurfaceDraw.Cover -> drawCover(draw.image, draw.scale, draw.background)
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
     * go to the Compose component there, so the popup sees a press outside itself and closes (D7). This node takes
     * them in turn ([onPointerEvent]), so no clickable under the page does (I1).
     */
    fun dismissPopupsAt(xPoints: Double, yPoints: Double) {
        if (!isAttached) return
        val overlays = windowOverlays(currentValueOf(LocalWebViewOverlays), currentValueOf(LocalWebViewRuntime))
        if (overlays.shapes.isEmpty()) return
        presses.press(currentValueOf(LocalAwtWindow), xPoints.toInt(), yPoints.toInt())
    }

    /** The page's own press, handed over to close popups, ends here: consumed in every pass, before anything under it. */
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pointerEvent.awtEventOrNull !is SyntheticPress) return
        for (change in pointerEvent.changes) change.consume()
    }

    override fun onCancelPointerInput() = Unit
}

/** The backing scale of [window]'s screen, window px a point (AWT's transform there); 0 for no window. */
internal fun backingScaleOf(window: java.awt.Window?): Float =
    window?.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.takeIf { it > 0f } ?: 0f
