package dev.supermux.webview.layouttest

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import dev.supermux.webview.runtime.LocalWebViewOverlays
import dev.supermux.webview.runtime.computeCutouts

/**
 * Where the page goes.
 * - At every layout the glue gets the page's bounds; whenever they or the window's overlays change, the cut-outs the
 *   library computes for an engine (`computeCutouts` over the overlay registry).
 * - Layout A clears the slot's pixels (BlendMode.Clear), so the page under the Metal layer shows. It must stay out of
 *   offscreen layers (alpha < 1, CompositingStrategy.Offscreen): a clear inside one clears only that layer.
 * - Until the page has painted, a placeholder (the glue keeps the page transparent until then).
 */
@Composable
fun PageSlot(model: TestModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val overlays = LocalWebViewOverlays.current ?: error("PageSlot needs ProvideWebViewOverlays around it")
    var pageBounds by remember { mutableStateOf(Rect.Zero) }
    var visibleBounds by remember { mutableStateOf(Rect.Zero) }
    val page = model.page
    LaunchedEffect(page, density) {
        if (page == 0L) return@LaunchedEffect
        snapshotFlow {
            packCutouts(computeCutouts(pageBounds, visibleBounds, overlays.shapes, overlays.scrims), includeHoles = model.cutoutsEnabled)
        }.collect { packed ->
            // Main.kt zeroes model.page before it detaches (frees) the page: never call into a freed one.
            if (model.page == page) Native.setCutouts(page, density, packed.geometry, packed.holeCount, packed.scrimColors)
        }
    }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val width = coordinates.size.width.toFloat()
                val height = coordinates.size.height.toFloat()
                pageBounds = Rect(position.x, position.y, position.x + width, position.y + height)
                visibleBounds = coordinates.boundsInWindow()
                model.bounds["page"] = pageBounds
                // The page as of now, not as of the last composition: the window's dispose lays out once more after
                // Main.kt has detached (freed) the page and zeroed model.page.
                val current = model.page
                if (current != 0L) Native.setFrame(current, density, position.x, position.y, width, height)
            }
            .drawBehind {
                when {
                    !model.painted -> drawRect(Palette.placeholder)
                    model.layout == Layout.Under -> drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                }
            },
    )
}
