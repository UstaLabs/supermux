package dev.supermux.webview

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.toSize
import dev.supermux.webview.runtime.LocalWebViewRuntime
import dev.supermux.webview.runtime.OverlayRegistry
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.ScrimShape
import kotlin.math.max
import kotlin.math.min

/**
 * Marks this composable as drawn over web views: a menu, popup, tooltip or dialog surface. Pages that sit above
 * Compose (web, macOS) cut its [shape] out of themselves, so it shows and takes the clicks (spec §6).
 * - Give it the shape the surface is drawn with. Each corner keeps its own radius; a shape that isn't a rounded
 *   rectangle is cut out as its bounds.
 * - It is cut out where it is drawn: moved and scaled by the layers above it (a menu that scales in), as the box
 *   around it if they turn it. So put it after the modifiers that move, size or transform the surface.
 * - A clip by an ancestor doesn't shrink it: the clipped edge would keep the outline's round corners, and cut too
 *   little there. Each page clips it to its own visible area, where that edge's corners turn square.
 */
fun Modifier.webViewOverlay(shape: Shape = RectangleShape): Modifier = this then OverlayElement(shape)

/**
 * Marks a modal scrim drawn over web views. A page that sits above Compose can't be covered, so it dims with [color]
 * where the scrim is drawn (spec §6): give it the color the scrim is drawn with. Scrims stack in the order they
 * appeared, so stacked ones compound as they do in Compose.
 */
fun Modifier.webViewScrim(color: Color = Color.Black.copy(alpha = 0.32f)): Modifier = this then ScrimElement(color)

private data class OverlayElement(val shape: Shape) : ModifierNodeElement<OverlayNode>() {
    override fun create() = OverlayNode(shape)

    override fun update(node: OverlayNode) {
        node.shape = shape
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "webViewOverlay"
        properties["shape"] = shape
    }
}

private data class ScrimElement(val color: Color) : ModifierNodeElement<ScrimNode>() {
    override fun create() = ScrimNode(color)

    override fun update(node: ScrimNode) {
        node.color = color
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "webViewScrim"
        properties["color"] = color
    }
}

/**
 * One entry in the overlay registry of the runtime provided where this node is, while it is attached.
 *
 * Compose calls [onGloballyPositioned] again whenever the entry may have changed: the layout moved or resized, a layer
 * above it changed its transform, the element was updated, or a composition local changed (another runtime, another
 * layout direction). So the entry is written there only, and moves when the runtime provided changes.
 */
private abstract class RegistryEntryNode :
    Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var registry: OverlayRegistry? = null

    /** Writes this node's entry, for its layout at [coordinates], into [registry]. */
    abstract fun write(registry: OverlayRegistry, coordinates: LayoutCoordinates)

    abstract fun erase(registry: OverlayRegistry)

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val current = currentValueOf(LocalWebViewRuntime).overlays
        val previous = registry
        if (previous !== current) {
            previous?.let(::erase)
            registry = current
        }
        write(current, coordinates)
    }

    override fun onDetach() {
        registry?.let(::erase)
        registry = null
    }
}

private class OverlayNode(var shape: Shape) : RegistryEntryNode() {
    override fun write(registry: OverlayRegistry, coordinates: LayoutCoordinates) {
        val outline = shape.createOutline(coordinates.size.toSize(), requireLayoutDirection(), requireDensity())
        val local = when (outline) {
            is Outline.Rounded -> outline.roundRect
            // A rectangle is its own rect; a path, its bounds.
            else -> RoundRect(outline.bounds)
        }
        registry.setOverlay(this, OverlayShape(coordinates.windowShapeOf(local)))
    }

    override fun erase(registry: OverlayRegistry) = registry.setOverlay(this, null)
}

private class ScrimNode(var color: Color) : RegistryEntryNode() {
    // Unlike an overlay's, a scrim's bounds are clipped: with no corners to round, that is exactly what is drawn, and
    // the page doesn't dim where the scrim is clipped away.
    override fun write(registry: OverlayRegistry, coordinates: LayoutCoordinates) {
        registry.setScrim(this, ScrimShape(coordinates.boundsInWindow(), color))
    }

    override fun erase(registry: OverlayRegistry) = registry.setScrim(this, null)
}

/**
 * [shape], given in this layout's coordinates, in window pixels as it is drawn: through the transforms of every layer
 * above it, and not clipped by any. Moved and scaled, it maps exactly, each radius scaled with its axis. Rotated,
 * skewed or mirrored, it is the box around its mapped corners, square, which covers it.
 */
private fun LayoutCoordinates.windowShapeOf(shape: RoundRect): RoundRect {
    val topLeft = localToWindow(Offset(shape.left, shape.top))
    val topRight = localToWindow(Offset(shape.right, shape.top))
    val bottomRight = localToWindow(Offset(shape.right, shape.bottom))
    val bottomLeft = localToWindow(Offset(shape.left, shape.bottom))
    val axisAligned = topLeft.y == topRight.y && bottomLeft.y == bottomRight.y &&
        topLeft.x == bottomLeft.x && topRight.x == bottomRight.x &&
        topRight.x >= topLeft.x && bottomLeft.y >= topLeft.y
    if (!axisAligned) {
        return RoundRect(
            min(min(topLeft.x, topRight.x), min(bottomRight.x, bottomLeft.x)),
            min(min(topLeft.y, topRight.y), min(bottomRight.y, bottomLeft.y)),
            max(max(topLeft.x, topRight.x), max(bottomRight.x, bottomLeft.x)),
            max(max(topLeft.y, topRight.y), max(bottomRight.y, bottomLeft.y)),
        )
    }
    val sx = if (shape.width > 0f) (topRight.x - topLeft.x) / shape.width else 1f
    val sy = if (shape.height > 0f) (bottomLeft.y - topLeft.y) / shape.height else 1f
    fun CornerRadius.scaled() = if (sx == 1f && sy == 1f) this else CornerRadius(x * sx, y * sy)
    return RoundRect(
        topLeft.x, topLeft.y, topRight.x, bottomLeft.y,
        shape.topLeftCornerRadius.scaled(),
        shape.topRightCornerRadius.scaled(),
        shape.bottomRightCornerRadius.scaled(),
        shape.bottomLeftCornerRadius.scaled(),
    )
}
