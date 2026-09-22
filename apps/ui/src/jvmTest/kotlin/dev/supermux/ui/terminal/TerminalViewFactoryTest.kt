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
import dev.supermux.net.TerminalClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import dev.supermux.net.DrawDim
import dev.supermux.net.HideCaret
import dev.supermux.net.Mods
import dev.supermux.net.SpecialKey
import dev.supermux.net.specialKeySequence
import dev.supermux.ui.platform.FakePredictionSink
import dev.supermux.ui.platform.FakeTerminalViewFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The [TerminalViewFactory] seam's contract (cluster G1): a shared screen mounts a surface, hands it
 * the `active` flag, and disposes it by leaving the composition — with no engine named anywhere.
 * Cluster G3's shared `TerminalTabs` is built on exactly this.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalViewFactoryTest {
    /** A real client that is never `run()`, so no socket is ever opened. */
    private fun connect(): TerminalClient =
        TerminalClient("ws://test", "token", HttpClient(MockEngine { respond("{}") }), "s1")

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

    // ── the key sink (cluster G3's shared TerminalKeyBar drives exactly this) ───────────────────

    @Test
    fun a_surface_that_is_never_drawn_never_connects_and_a_key_press_connects_it() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        var keys: TerminalKeySink? = null
        setContent { keys = factory.rememberTerminalSurface { connect() }.keys }
        // Composing the surface (a key bar drawn outside the pane) must NOT open a pty: a surface
        // whose Content is never composed is never disposed either, so an eager client would leak.
        assertTrue(factory.connects.isEmpty(), "the client is built lazily")

        assertNotNull(keys).press(TerminalKey.Printable('a'))

        assertEquals(1, factory.connects.size, "the first press builds it")
        assertNotNull(keys).press(TerminalKey.Printable('b'))
        assertEquals(1, factory.connects.size, "and only once")
    }

    @Test
    fun disposing_a_surface_that_never_drew_never_connects_one_just_to_close_it() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        var shown by mutableStateOf(true)
        setContent { if (shown) factory.rememberTerminalSurface { connect() } }

        shown = false
        waitForIdle()

        // The surface's dispose stops a client it BUILT (nothing else owns a key-bar-only one) —
        // it must not build one to do it.
        assertTrue(factory.connects.isEmpty())
    }

    @Test
    fun mounting_the_grid_connects_the_surface() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        setContent { factory.TerminalView({ connect() }, Modifier, active = true, onExit = null) }
        assertEquals(1, factory.connects.size)
    }

    @Test
    fun a_surface_hands_out_a_sink_before_it_is_ever_drawn() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        var keys: TerminalKeySink? = null
        setContent {
            // No Content() call at all: a key bar can be composed before/without the grid.
            keys = factory.rememberTerminalSurface { connect() }.keys
        }
        val sink = assertNotNull(keys)
        sink.press(TerminalKey.Printable('a'))
        assertEquals(listOf("a"), factory.sent)
        assertTrue(factory.mounted.isEmpty())
    }

    @Test
    fun a_modifier_cycles_off_once_locked_and_arms_the_next_key() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        var keys: TerminalKeySink? = null
        setContent { keys = factory.rememberTerminalSurface { connect() }.keys }
        val sink = assertNotNull(keys)

        sink.press(TerminalKey.Mod(TerminalModKey.CTRL))
        assertEquals(TerminalModState.ONCE, sink.ctrl)
        assertTrue(sink.armed)
        assertTrue(factory.sent.isEmpty(), "a modifier press sends nothing on its own")

        sink.press(TerminalKey.Printable('c'))
        assertEquals(listOf("\u0003"), factory.sent)
        // `once` is consumed by the key it modified; `locked` would stay.
        assertEquals(TerminalModState.OFF, sink.ctrl)

        sink.press(TerminalKey.Mod(TerminalModKey.CTRL))
        sink.press(TerminalKey.Mod(TerminalModKey.CTRL))
        assertEquals(TerminalModState.LOCKED, sink.ctrl)
        sink.press(TerminalKey.Printable('c'))
        assertEquals(TerminalModState.LOCKED, sink.ctrl)
        sink.press(TerminalKey.Mod(TerminalModKey.CTRL))
        assertEquals(TerminalModState.OFF, sink.ctrl)
    }

    @Test
    fun a_special_key_is_encoded_with_the_armed_modifiers() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        var keys: TerminalKeySink? = null
        setContent { keys = factory.rememberTerminalSurface { connect() }.keys }
        val sink = assertNotNull(keys)
        sink.press(TerminalKey.Special(SpecialKey.ArrowUp))
        assertEquals(specialKeySequence(SpecialKey.ArrowUp, Mods(ctrl = false, alt = false), appCursor = false), factory.sent.last())
        sink.press(TerminalKey.Mod(TerminalModKey.ALT))
        sink.press(TerminalKey.Special(SpecialKey.ArrowUp))
        assertEquals(specialKeySequence(SpecialKey.ArrowUp, Mods(ctrl = false, alt = true), appCursor = false), factory.sent.last())
        assertEquals(TerminalModState.OFF, sink.alt)
    }

    @Test
    fun the_real_keyboard_consumes_an_armed_once_modifier() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        var keys: TerminalKeySink? = null
        setContent { keys = factory.rememberTerminalSurface { connect() }.keys }
        val sink = assertNotNull(keys)
        sink.press(TerminalKey.Mod(TerminalModKey.CTRL))
        // What a surface does when its OWN grid sent the modified keystroke.
        assertEquals(Mods(ctrl = true, alt = false), sink.mods)
        sink.consumeOnce()
        assertEquals(TerminalModState.OFF, sink.ctrl)
        assertFalse(sink.armed)
    }

    @Test
    fun each_pane_gets_its_own_sink() = runComposeUiTest {
        val factory = FakeTerminalViewFactory()
        setContent {
            factory.rememberTerminalSurface { connect() }
            factory.rememberTerminalSurface { connect() }
        }
        assertEquals(2, factory.surfaces.size)
        val (a, b) = factory.surfaces
        a.keys.press(TerminalKey.Mod(TerminalModKey.CTRL))
        // A background pane's armed Ctrl must not leak into the foreground one.
        assertEquals(TerminalModState.ONCE, a.keys.ctrl)
        assertEquals(TerminalModState.OFF, b.keys.ctrl)
    }

    @Test
    fun a_host_with_no_engine_has_a_sink_that_goes_nowhere() = runComposeUiTest {
        var keys: TerminalKeySink? = null
        setContent { keys = UnavailableTerminalViewFactory.rememberTerminalSurface { connect() }.keys }
        val sink = assertNotNull(keys)
        sink.press(TerminalKey.Printable('a'))
        assertEquals(TerminalModState.OFF, sink.ctrl)
    }
}
