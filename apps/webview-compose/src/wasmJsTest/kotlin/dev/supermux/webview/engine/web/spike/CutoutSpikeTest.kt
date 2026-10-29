package dev.supermux.webview.engine.web.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.web.TestViewport
import dev.supermux.webview.engine.web.clickCompose
import dev.supermux.webview.engine.web.frames
import dev.supermux.webview.engine.web.hitAt
import dev.supermux.webview.engine.web.pause
import dev.supermux.webview.runtime.LocalWebViewOverlays
import dev.supermux.webview.runtime.OverlayRegistry
import dev.supermux.webview.runtime.ProvideWebViewOverlays
import dev.supermux.webview.runtime.computeCutouts
import dev.supermux.webview.runtime.pageLocal
import dev.supermux.webview.webViewOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Feasibility G2 (in Compose) and the lag half of G3. A strategy-B page at (40, 40) 600×400, and over it a Compose
 * overlay at (200, 150) 240×160 with 24 px corners, marked with `webViewOverlay`. Density is 1.
 */
class CutoutSpikeTest {
    private val shape = RoundedCornerShape(24.dp)

    @Test fun holesTakeTheClicksAndKeepPaceWithAMovingOverlay() = runTest(timeout = 3.minutes) {
        val counts = SpikeCounts()
        var clicks by mutableIntStateOf(0)
        var overlayX by mutableIntStateOf(200)
        var drawnLeft = Float.NaN
        var appliedLeft = Float.NaN
        var page by mutableStateOf(Rect.Zero)
        var visible by mutableStateOf(Rect.Zero)
        var frame: SpikeFrame? = null
        var registry: OverlayRegistry? = null
        TestViewport.show {
            ProvideWebViewOverlays {
                registry = LocalWebViewOverlays.current
                Box(Modifier.fillMaxSize()) {
                    frame = FrameB(30_000, counts, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) { p, v ->
                        page = p
                        visible = v
                    }
                    Box(
                        Modifier
                            .offset { IntOffset(overlayX, 150) }
                            .size(240.dp, 160.dp)
                            .onGloballyPositioned { drawnLeft = it.positionInWindow().x }
                            .clip(shape)
                            .background(Color(0xFF3366FF))
                            .webViewOverlay(shape)
                            .clickable { clicks++ },
                    )
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            val overlays = checkNotNull(registry)
            snapshotFlow { computeCutouts(page, visible, overlays.shapes, overlays.scrims) }
                .map { it to it.pageLocal() }
                .distinctUntilChangedBy { it.second }
                .collect { (cutouts, _) ->
                    checkNotNull(frame).cutOut(cutouts)
                    appliedLeft = cutouts.holes.firstOrNull()?.bounds?.left ?: Float.NaN
                }
        }
        pause(1_500)

        // G2: hit testing at rest.
        val points = listOf(
            Triple(320.0, 230.0, "compose"), // the hole's middle
            Triple(203.0, 153.0, "iframe:b30000"), // inside the overlay's box, outside its rounded corner
            Triple(202.0, 230.0, "compose"), // just inside the hole's straight left edge
            Triple(100.0, 100.0, "iframe:b30000"), // the page, away from the hole
            Triple(20.0, 20.0, "compose"), // outside the page
            Triple(700.0, 500.0, "compose"), // outside the page
        )
        val got = points.map { (x, y, _) -> hitAt(x, y) }
        points.zip(got).forEach { (point, hit) ->
            println("SPIKE G2 karma x=${point.first} y=${point.second} expected=${point.third} got=$hit")
        }
        clickCompose(320.0, 230.0)
        frames(3)
        println("SPIKE G2 karma click-through-hole clicks=$clicks")

        // G3 lag: the overlay moves 4 px a frame for 60 frames. Just before each paint, is the hole where Compose put
        // the overlay in that frame?
        var moved = 0
        var late = 0
        var sampled = Float.NaN
        val probe = installPrePaintProbe {
            val drawn = drawnLeft
            if (drawn != sampled) {
                sampled = drawn
                moved++
                if (appliedLeft != drawn) late++
            }
        }
        repeat(60) {
            overlayX += 4
            frames(1)
        }
        frames(3)
        stopPrePaintProbe(probe)
        println("SPIKE G3 cutout-lag frames-moved=$moved frames-late=$late")

        scope.cancel()
        TestViewport.clear()
        points.zip(got).forEach { (point, hit) -> assertEquals(point.third, hit, "hit at (${point.first}, ${point.second})") }
        assertEquals(1, clicks, "a click in the hole reaches the Compose overlay")
    }
}
