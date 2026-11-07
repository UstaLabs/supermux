package dev.supermux.webview.engine.web.spike

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.web.TestViewport
import dev.supermux.webview.engine.web.frames
import dev.supermux.webview.engine.web.pause
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Feasibility G1: does a live iframe start a document nobody asked for while its list scrolls, gets items inserted
 * above it, reorders, or stops placing it for a few frames? Strategy A is the iframe inside Compose's `HtmlElementView`;
 * strategy B is the engine's design. Each line printed is cumulative over the scenarios so far.
 */
class EmbeddingSpikeTest {
    private enum class Strategy { A, B }

    /** Measured at its size but never placed: still composed, unplaced. */
    private fun Modifier.unplaced(): Modifier = layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {}
    }

    @Composable
    private fun SpikeList(strategy: Strategy, ids: List<Int>, state: LazyListState, unplaced: Int?, counts: SpikeCounts) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(ids, key = { it }) { id ->
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    BasicText("message $id")
                    val frame = Modifier.fillMaxWidth().height(160.dp).then(if (id == unplaced) Modifier.unplaced() else Modifier)
                    when (strategy) {
                        Strategy.A -> FrameA(id, counts, frame)
                        Strategy.B -> FrameB(id, counts, frame)
                    }
                }
            }
        }
    }

    private suspend fun measure(strategy: Strategy): List<String> {
        installStartCounter()
        resetStarts()
        val counts = SpikeCounts()
        val base = if (strategy == Strategy.A) 10_000 else 20_000
        val ids = mutableStateListOf<Int>().apply { addAll((0 until 60).map { base + it }) }
        val state = LazyListState()
        var unplaced by mutableStateOf<Int?>(null)
        TestViewport.show { SpikeList(strategy, ids, state, unplaced, counts) }
        pause(1_500)
        val lines = mutableListOf<String>()
        fun record(scenario: String) {
            lines += "SPIKE G1 strategy=$strategy scenario=$scenario created=${counts.created} reloads=${counts.reloads}"
        }
        // 1. Scroll 200 frames down and 200 back up, 37 px a frame.
        repeat(200) {
            state.scrollBy(37f)
            frames(1)
        }
        repeat(200) {
            state.scrollBy(-37f)
            frames(1)
        }
        pause(1_500)
        record("scroll")
        // 2. Three items inserted above live ones, ten frames apart.
        state.scrollToItem(20)
        pause(1_500)
        repeat(3) {
            ids.add(0, base + 1_000 + it)
            frames(10)
        }
        pause(1_500)
        record("insert-above")
        // 3. Two visible items swap places. Both writes land before the next composition, so no key repeats.
        val first = state.firstVisibleItemIndex
        val upper = ids[first]
        ids[first] = ids[first + 1]
        ids[first + 1] = upper
        pause(1_500)
        record("reorder")
        // 4. A visible item stays composed but isn't placed for five frames.
        unplaced = ids[state.firstVisibleItemIndex]
        frames(5)
        unplaced = null
        pause(1_500)
        record("unplace")
        TestViewport.clear()
        return lines
    }

    @Test fun ownLayerFramesNeverReload() = runTest(timeout = 6.minutes) {
        val a = measure(Strategy.A)
        val b = measure(Strategy.B)
        (a + b).forEach(::println)
        val reloads = b.last().substringAfter("reloads=").toInt()
        assertEquals(0, reloads, "strategy B reloaded: ${b.joinToString(" | ")}")
    }
}
