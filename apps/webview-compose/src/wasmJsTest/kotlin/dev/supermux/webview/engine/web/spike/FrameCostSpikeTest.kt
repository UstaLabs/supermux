package dev.supermux.webview.engine.web.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.web.TestViewport
import dev.supermux.webview.engine.web.frames
import dev.supermux.webview.engine.web.now
import dev.supermux.webview.engine.web.pause
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Feasibility G3, cost: a 3-column grid of 140 dp tiles (about 12–15 on screen), scrolled 6 px a frame for 300 frames,
 * once with strategy-B frames and once with plain tiles. Prints frame intervals for both, and for the frames the JS
 * time spent placing them.
 */
class FrameCostSpikeTest {
    @Composable
    private fun Grid(state: LazyGridState, live: Boolean, counts: SpikeCounts) {
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), state = state) {
            items(300, key = { it }) { index ->
                val tile = Modifier.fillMaxWidth().height(140.dp).padding(4.dp)
                if (live) FrameB(40_000 + index, counts, tile) else Box(tile.background(Color(0xFFE8EEFC)))
            }
        }
    }

    private suspend fun scroll(live: Boolean): List<Double> {
        val state = LazyGridState()
        val counts = SpikeCounts()
        TestViewport.show { Grid(state, live, counts) }
        pause(2_000)
        SpikeCost.take()
        val intervals = ArrayList<Double>()
        val costs = ArrayList<Double>()
        var peak = 0
        frames(1)
        var last = now()
        repeat(300) {
            state.scrollBy(6f)
            frames(1)
            val t = now()
            intervals += t - last
            last = t
            costs += SpikeCost.take()
            peak = maxOf(peak, SpikeCost.liveFrames)
        }
        TestViewport.clear()
        println(
            "SPIKE G3 frame-cost live=$live peak-frames=$peak " +
                "interval-p50=${percentile(intervals, 0.5).ms()} p95=${percentile(intervals, 0.95).ms()} " +
                "max=${(intervals.maxOrNull() ?: Double.NaN).ms()} over-25ms=${intervals.count { it > 25.0 }} " +
                "placement-p50=${percentile(costs, 0.5).ms()} placement-p95=${percentile(costs, 0.95).ms()}",
        )
        return costs
    }

    @Test fun placingAScreenOfLiveFramesEachFrame() = runTest(timeout = 5.minutes) {
        scroll(live = false)
        val costs = scroll(live = true)
        assertTrue(costs.size == 300)
    }
}
