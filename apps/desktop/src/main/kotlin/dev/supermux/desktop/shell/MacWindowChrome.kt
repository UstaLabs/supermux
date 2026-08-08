// macOS window-drag arbitration for the edge-to-edge chrome (see MacChrome.kt for the layout
// constants). With a transparent full-size-content title bar, AppKit still treats the top ~28pt
// band as the native drag zone: a drag there moves the WHOLE WINDOW in parallel with whatever
// Compose gesture is under the pointer (AppKit consults a private per-view opacity protocol AWT
// doesn't implement, so Java cannot opt content out). Symptom: dragging a workspace tab in a
// top-edge strip also dragged the window.
//
// Fix: JetBrains Runtime's custom-title-bar API (jbr-api `WindowDecorations.CustomTitleBar`).
// When installed, JBR makes the NSWindow immovable, overlays its own drag view on the title-bar
// band (below the traffic lights), keeps forwarding every event to the AWT content view, and
// performs the native drag / double-click zoom ONLY when the Java-side hit test allows it:
//   - Default heuristic: a component with mouse listeners under the pointer ⇒ "client" ⇒ NO
//     native actions. Compose's ComposePanel always has listeners, so merely installing the
//     title bar ends the collision everywhere.
//   - `CustomTitleBar.forceHitTest(client)` overrides that per event. We call it from an AWT
//     mouse listener so the EMPTY chrome areas (sidebar band, tab-strip tails) opt back INTO
//     native dragging — browser-tab-bar behavior, with native feel (Spaces, double-click zoom).
//
// Areas are declared from Compose via [macTitleBarDragRegion] (native drag allowed) and
// [macTitleBarNoDragRegion] (punches a hole in an overlapping drag region, e.g. a button inside
// the band). Everything unregistered stays "client".
//
// Runtime gate: `JBR.getWindowDecorations()` returns the real service only on a JetBrains
// Runtime (`:desktop:hotRun`). On other JVMs (the packaged app currently bundles Corretto) it
// returns null and [rememberMacWindowChrome] yields null — Main.kt then falls back to the plain
// fullWindowContent client properties (pre-existing behavior, collision included). Packaging
// with a JBR makes the fix apply to the shipped app too.
package dev.supermux.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.toSize
import com.jetbrains.JBR
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the title-bar band may act as a native window-drag handle, in Compose window-root px.
 *
 * Native drag is allowed at a point iff it is inside at least one drag region AND outside every
 * hole. Regions are registered by [macTitleBarDragRegion] / [macTitleBarNoDragRegion] and read by
 * the per-event AWT listener in [rememberMacWindowChrome]. Rects anywhere below the title-bar
 * band are harmless: JBR consults the hit test only inside the band.
 *
 * Plain concurrent maps, not snapshot state — nothing composes against this; it is only read on
 * AWT mouse events.
 */
class MacChromeRegions {
    private val drag = ConcurrentHashMap<String, Rect>()
    private val holes = ConcurrentHashMap<String, Rect>()

    internal fun set(key: String, rect: Rect, hole: Boolean) {
        (if (hole) holes else drag)[key] = rect
    }

    internal fun remove(key: String, hole: Boolean) {
        (if (hole) holes else drag).remove(key)
    }

    /** True when a pointer at [p] (window-root px) should get NATIVE drag / double-click zoom. */
    fun allowsNativeDrag(p: Offset): Boolean =
        drag.values.any { it.contains(p) } && holes.values.none { it.contains(p) }
}

/**
 * Null everywhere except under an installed [rememberMacWindowChrome] (macOS + JetBrains
 * Runtime). The region modifiers below no-op when this is null, so call sites don't gate.
 */
val LocalMacWindowChrome = staticCompositionLocalOf<MacChromeRegions?> { null }

/**
 * Installs the JBR custom title bar on [window] (height = [MacTitleBarHeight], native traffic
 * lights kept) and starts feeding its hit test from window mouse events. Returns the region
 * registry to provide via [LocalMacWindowChrome], or null when the runtime has no
 * WindowDecorations service — caller falls back to the plain client properties.
 */
@Composable
fun rememberMacWindowChrome(window: ComposeWindow): MacChromeRegions? {
    val titleBar = remember(window) {
        runCatching {
            val decorations = JBR.getWindowDecorations() ?: return@runCatching null
            decorations.createCustomTitleBar().also { tb ->
                // "Pixels from the top of the client area" = AWT user-space points = dp here.
                tb.height = MacTitleBarHeight.value
                decorations.setCustomTitleBar(window, tb)
            }
        }.getOrNull()
    } ?: return null
    val regions = remember(window) { MacChromeRegions() }
    DisposableEffect(window, titleBar) {
        val listener = object : MouseAdapter() {
            // JBR contract: update the hit test on every mouse event except EXITED/WHEEL;
            // unset events revert to the default heuristic (= client over Compose content).
            // ComposeWindow delegates listener registration to its content panel, so event
            // coords are content-relative AWT points; scale to Compose px per event (per-monitor
            // transforms change when the window moves between displays).
            private fun update(e: MouseEvent) {
                val t = window.graphicsConfiguration?.defaultTransform
                val p = Offset(
                    (e.x * (t?.scaleX ?: 1.0)).toFloat(),
                    (e.y * (t?.scaleY ?: 1.0)).toFloat(),
                )
                titleBar.forceHitTest(!regions.allowsNativeDrag(p))
            }

            override fun mousePressed(e: MouseEvent) = update(e)
            override fun mouseReleased(e: MouseEvent) = update(e)
            override fun mouseEntered(e: MouseEvent) = update(e)
            override fun mouseDragged(e: MouseEvent) = update(e)
            override fun mouseMoved(e: MouseEvent) = update(e)
        }
        window.addMouseListener(listener)
        window.addMouseMotionListener(listener)
        onDispose {
            window.removeMouseListener(listener)
            window.removeMouseMotionListener(listener)
        }
    }
    return regions
}

/**
 * Marks this element's bounds as a native window-drag handle while it sits in the macOS
 * title-bar band (empty chrome: the sidebar band, tab-strip tails). No-op when
 * [LocalMacWindowChrome] is null (non-mac / non-JBR) and everywhere below the band.
 * [key] must be unique per registered element.
 */
fun Modifier.macTitleBarDragRegion(key: String): Modifier =
    this then MacChromeRegionElement(key, hole = false)

/**
 * Punches a hole in any overlapping [macTitleBarDragRegion]: interactive content layered inside
 * a drag region (e.g. the sidebar toggle inside the sidebar band) keeps normal client behavior.
 */
fun Modifier.macTitleBarNoDragRegion(key: String): Modifier =
    this then MacChromeRegionElement(key, hole = true)

private data class MacChromeRegionElement(
    val key: String,
    val hole: Boolean,
) : ModifierNodeElement<MacChromeRegionNode>() {
    override fun create() = MacChromeRegionNode(key, hole)

    override fun update(node: MacChromeRegionNode) = node.update(key, hole)

    override fun InspectorInfo.inspectableProperties() {
        name = if (hole) "macTitleBarNoDragRegion" else "macTitleBarDragRegion"
        properties["key"] = key
    }
}

private class MacChromeRegionNode(
    private var key: String,
    private var hole: Boolean,
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var regions: MacChromeRegions? = null

    override fun onAttach() {
        regions = currentValueOf(LocalMacWindowChrome)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        regions?.set(key, Rect(coordinates.positionInWindow(), coordinates.size.toSize()), hole)
    }

    override fun onDetach() {
        regions?.remove(key, hole)
        regions = null
    }

    fun update(newKey: String, newHole: Boolean) {
        if (newKey == key && newHole == hole) return
        regions?.remove(key, hole)
        key = newKey
        hole = newHole
        // Fresh bounds land on the next onGloballyPositioned pass.
    }
}
