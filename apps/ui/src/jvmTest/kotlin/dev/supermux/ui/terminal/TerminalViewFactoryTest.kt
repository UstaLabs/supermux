package dev.supermux.ui.terminal

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.CursorPos
import dev.supermux.net.DrawDim
import dev.supermux.net.HideCaret
import dev.supermux.ui.platform.FakePredictionSink
import dev.supermux.ui.platform.FakeTerminalViewFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The [TerminalViewFactory] seam's contract (cluster G1): a shared screen mounts a surface, hands it
 * the `active` flag, and disposes it by leaving the composition — with no engine named anywhere.
 * Cluster G3's shared `TerminalTabs` is built on exactly this.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalViewFactoryTest {
    private fun connect(): Nothing = error("the fake never connects")

    @Test
    fun mounting_a_surface_draws_it_and_records_one_live_mount() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        setContent {
            factory.TerminalView({ connect() }, Modifier, active = true, onExit = null)
        }
        onNodeWithTag("fake_terminal").assertIsDisplayed()
        assertEquals(listOf("terminal"), factory.mounted)
        assertEquals(true, factory.lastActive)
    }

    @Test
    fun leaving_the_composition_disposes_the_surface() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        var shown by mutableStateOf(true)
        setContent {
            if (shown) factory.TerminalView({ connect() }, Modifier, active = true, onExit = null)
            else Text("gone")
        }
        assertEquals(1, factory.mounted.size)
        shown = false
        waitForIdle()
        assertTrue(factory.mounted.isEmpty(), "surface should be disposed with its composition")
        assertEquals(1, factory.mounts.size, "and not re-mounted")
        onNodeWithText("gone").assertIsDisplayed()
    }

    @Test
    fun a_background_pane_is_mounted_inactive() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        setContent {
            factory.TerminalView({ connect() }, Modifier, active = false, onExit = null)
        }
        assertEquals(false, factory.lastActive)
    }

    @Test
    fun a_host_with_no_engine_draws_the_hint_instead_of_a_grid() = runComposeUiTest {
        assertFalse(UnavailableTerminalViewFactory.available)
        setContent {
            UnavailableTerminalViewFactory.TerminalView({ connect() }, Modifier, true, null)
        }
        onNodeWithTag("terminal_unavailable").assertIsDisplayed()
    }

    @Test
    fun the_prediction_sink_records_the_ops_it_was_handed() {
        val sink = FakePredictionSink(cursor = CursorPos(3, 7))
        assertTrue(sink.available)
        assertEquals(CursorPos(3, 7), sink.cursor())
        assertEquals(1, sink.cursorReads)
        sink.render(listOf(HideCaret, DrawDim(1, 2, 3, "x")))
        assertEquals(2, sink.rendered.size)
        assertEquals(HideCaret, sink.rendered.first())
    }

    @Test
    fun an_unavailable_sink_is_what_a_pipeline_skips_prediction_on() {
        assertFalse(FakePredictionSink(available = false).available)
    }
}
