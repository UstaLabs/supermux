package dev.supermux.webview.engine.web

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import org.w3c.dom.HTMLElement

/** Where an engine's frame goes: this node's layout, in the viewport whose layer is [layer]. */
internal class WebSurfaceElement(private val frame: WebFrame, private val layer: HTMLElement?) :
    ModifierNodeElement<WebSurfaceNode>() {
    override fun create() = WebSurfaceNode(frame, layer)

    override fun update(node: WebSurfaceNode) = node.update(frame, layer)

    override fun equals(other: Any?): Boolean =
        other is WebSurfaceElement && other.frame === frame && sameLayer(other.layer, layer)

    override fun hashCode(): Int = frame.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "webEngineSurface"
    }
}

private fun sameLayer(a: HTMLElement?, b: HTMLElement?): Boolean =
    if (a == null || b == null) a == null && b == null else same(a, b)

/**
 * Tells the frame where its surface is, in the layout pass that put it there. That is the same pass that moves a list,
 * so the page moves with the list. It also says when the surface is no longer placed, and when it leaves.
 */
internal class WebSurfaceNode(private var frame: WebFrame, private var layer: HTMLElement?) :
    Modifier.Node(), GlobalPositionAwareModifierNode, UnplacedAwareModifierNode {
    private var last: LayoutCoordinates? = null

    fun update(frame: WebFrame, layer: HTMLElement?) {
        if (frame !== this.frame) {
            this.frame.surfaceDetached(this)
            this.frame = frame
            frame.surfaceAttached(this)
        }
        this.layer = layer
        // The layer may be found after the last layout pass, and no other may come: place now.
        last?.takeIf { it.isAttached }?.let(::placeAt)
    }

    override fun onAttach() = frame.surfaceAttached(this)

    override fun onDetach() {
        last = null
        frame.surfaceDetached(this)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        last = coordinates
        placeAt(coordinates)
    }

    override fun onUnplaced() = frame.surfaceUnplaced(this)

    private fun placeAt(coordinates: LayoutCoordinates) {
        frame.surfacePlaced(
            this,
            layer,
            coordinates.positionInWindow(),
            coordinates.size,
            coordinates.boundsInWindow(),
            requireDensity().density,
        )
    }
}
