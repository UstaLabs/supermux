package dev.supermux.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.PredictionConfig
import dev.supermux.net.PredictionEngine
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalSendResult
import dev.supermux.terminal.OutputOrigin
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSize
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared renderer behind the `TerminalViewFactory` seam, driven end to end: a real
 * [TerminalClient] over a fake socket, a real [TerminalSession] over a fake engine, and the real
 * adapter between them.
 *
 * What is worth pinning here is the WIRING — which backend event becomes which engine call, who
 * encodes a keystroke, what a hidden pane keeps and what it gives up, and who stops the client.
 * None of it is reachable by reading either half alone, which is precisely why the four host
 * renderers could each get a different answer.
 */
@OptIn(ExperimentalTestApi::class)
class GhosttyTerminalViewFactoryTest {

    private class Clock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private class Harness {
        val clock = Clock()
        val engine = FakeTerminalEngine(TerminalSize(80, 24, 8, 16))
        val transport = FakeTerminalTransport()
        val http = HttpClient(MockEngine { respond("{}") })

        /** The surface's own prediction state, held here so a test can read what it would draw. */
        val predictions = GhosttyPredictionState(PredictionEngine(PredictionConfig(40, 600, 50), clock), clock)

        val factory = GhosttyTerminalViewFactory(
            nowMs = clock,
            openSession = { size, effects ->
                engine.size = size
                TerminalSession.open(
                    size = size,
                    limits = TerminalLimits(),
                    effects = effects,
                    engineFactory = { _, _ -> engine },
                )
            },
            predictionsOf = { predictions },
        )

        /** How many clients the surface asked for, and the last one it got. */
        var connects = 0
            private set
        var client: TerminalClient? = null
            private set

        fun connect(): TerminalClient {
            connects++
            return TerminalClient(
                baseUrl = "ws://h:1",
                token = "t",
                http = http,
                sessionId = "s1",
                workspaceId = "w1",
                transport = transport,
            ).also { client = it }
        }

        val socket: FakeTerminalSocket get() = transport.sockets.first()
    }

    /** Pump until [condition]; the engine and the client run beside the composition, not in it. */
    private fun ComposeUiTest.until(what: String, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = 10_000) { condition() }
        } catch (timeout: Throwable) {
            throw AssertionError("timed out waiting until $what", timeout)
        }
    }

    /**
     * Open the connection AND wait until the replay boundary has closed in the ADAPTER.
     *
     * The client's own `restoring` flag flips a step before the adapter's collector sees
     * `replay-end`, so "the socket accepted my keystroke" does not yet mean "the next server byte
     * will be fed as LIVE". A test that starts typing in that gap gets its echo classified as
     * replay — correctly, and invisibly — and then wonders why nothing is ever predicted. The
     * sentinel byte is below 0x20, so it moves the fake screen not at all and the boundary
     * everything.
     */
    private fun ComposeUiTest.openLive(h: Harness) {
        h.socket.openEpoch()
        h.socket.pushBytes(byteArrayOf(0))
        until("the replay boundary closed", h) {
            h.engine.feeds.any { it.second == OutputOrigin.LIVE }
        }
        waitForIdle()
    }

    /** As [until], but says what the world looked like when it gave up. */
    private fun ComposeUiTest.until(what: String, h: Harness, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = 10_000) { condition() }
        } catch (timeout: Throwable) {
            throw AssertionError(
                "timed out waiting until $what; " +
                    "screen='${h.engine.text}' sent='${h.socket.sentText}' " +
                    "feeds=${h.engine.feeds} sockets=${h.transport.sockets.size} " +
                    "predicted=${h.predictions.cells.toList()} clock=${h.clock.now}",
                timeout,
            )
        }
    }

    @Test
    fun a_typed_character_is_predicted_then_confirmed_without_doubling() = runComposeUiTest {
        val h = Harness()
        lateinit var surface: TerminalSurface
        setContent {
            surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = true, onExit = null) }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        openLive(h)

        // Open both of the engine's gates the way a real session does: one round trip raises the
        // latency estimate past the threshold, and one CONFIRMED prediction opens the epoch.
        // Nothing is drawn speculatively before that, deliberately.
        //
        // 200 ms, not 45: the engine re-samples the estimate on every confirm, and a round trip
        // only just over the threshold can be dragged back under it by one sample. A fixture that
        // sits on the boundary is a fixture that fails on a slow machine and passes on a fast one.
        fun roundTrip(ch: Char, gapMs: Long) {
            surface.keys.press(TerminalKey.Printable(ch))
            until("'$ch' reached the socket") { h.socket.sentText.endsWith(ch.toString()) }
            h.clock.now += gapMs
            h.socket.pushBytes(ch.toString().encodeToByteArray())
            until("'$ch' echoed back onto the screen") { h.engine.text.endsWith(ch.toString()) }
        }
        roundTrip('x', 200)
        roundTrip('y', 200)
        waitForIdle()

        // Now the one under test.
        surface.keys.press(TerminalKey.Printable('a'))
        until("'a' was predicted") { h.predictions.cells.isNotEmpty() }
        waitForIdle()

        // Drawn speculatively — and NOT in Ghostty. The authoritative screen still says "xy",
        // because a prediction is painted over the grid and never written into it.
        assertEquals(listOf(PredictedCell(0, 2, "a")), h.predictions.cells.toList())
        assertEquals("xy", h.engine.text)

        h.clock.now += 200
        h.socket.pushBytes("a".encodeToByteArray())
        until("the echo landed") { h.engine.text == "xya" }
        until("the prediction was confirmed") { h.predictions.cells.isEmpty() }
        waitForIdle()

        // One 'a' on the authoritative screen and none over it: the overlay stopped drawing its
        // own the moment the real one arrived. Fed exactly once, so nothing doubled.
        assertEquals("xya", h.engine.text)
        assertEquals(1, h.engine.feeds.count { it.first == "a" })
    }

    @Test
    fun a_mispredicted_character_gives_way_to_the_authoritative_text() = runComposeUiTest {
        val h = Harness()
        lateinit var surface: TerminalSurface
        setContent {
            surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = true, onExit = null) }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        openLive(h)

        fun roundTrip(typed: Char, echoed: Char, gapMs: Long) {
            surface.keys.press(TerminalKey.Printable(typed))
            until("'$typed' reached the socket") { h.socket.sentText.endsWith(typed.toString()) }
            h.clock.now += gapMs
            h.socket.pushBytes(echoed.toString().encodeToByteArray())
            until("'$echoed' landed") { h.engine.text.endsWith(echoed.toString()) }
        }
        roundTrip('x', 'x', 200)
        roundTrip('y', 'y', 200)
        waitForIdle()

        surface.keys.press(TerminalKey.Printable('a'))
        until("'a' was predicted", h) { h.predictions.cells.isNotEmpty() }

        // The program echoed something else entirely — a password prompt's star.
        h.clock.now += 200
        h.socket.pushBytes("*".encodeToByteArray())
        until("the real glyph landed") { h.engine.text == "xy*" }
        until("the mispredict was withdrawn") { h.predictions.cells.isEmpty() }
        waitForIdle()

        // The authoritative text stands, alone. A prediction written INTO the emulator would have
        // had to be erased by writing over it; this one only had to stop being drawn.
        assertEquals("xy*", h.engine.text)
        assertEquals(emptyList(), h.predictions.cells.toList())
    }

    @Test
    fun server_bytes_reach_the_engine_exactly_once_with_the_replay_boundarys_origin() =
        runComposeUiTest {
            val h = Harness()
            setContent {
                val surface = h.factory.rememberTerminalSurface { h.connect() }
                Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = true, onExit = null) }
            }
            until("the socket opens") { h.transport.sockets.isNotEmpty() }

            val socket = h.socket
            socket.push("""{"type":"ready","version":2,"epoch":"c-1","replyOwner":false,"ownerGeneration":0}""")
            socket.push("""{"type":"reset","epoch":"e-1"}""")
            socket.push("""{"type":"replay-start","epoch":"e-1"}""")
            socket.pushBytes("old".encodeToByteArray())
            socket.push("""{"type":"replay-end","epoch":"e-1"}""")
            socket.pushBytes("new".encodeToByteArray())

            until("both chunks landed") { h.engine.feeds.size >= 2 }
            waitForIdle()

            assertEquals(
                listOf("old" to OutputOrigin.REPLAY, "new" to OutputOrigin.LIVE),
                h.engine.feeds,
            )
            assertEquals("oldnew", h.engine.text)
            // The reset before the replay is the backend's, applied once.
            assertEquals(1, h.engine.resets)
        }

    @Test
    fun an_armed_modifier_is_encoded_by_the_engine_exactly_once() = runComposeUiTest {
        val h = Harness()
        lateinit var surface: TerminalSurface
        setContent {
            surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = true, onExit = null) }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        h.socket.openEpoch()
        waitForIdle()

        surface.keys.press(TerminalKey.Mod(TerminalModKey.CTRL))
        until("the bar's Ctrl reached the renderer") { surface.keys.ctrl == TerminalModState.ONCE }
        surface.keys.press(TerminalKey.Printable('c'))
        until("something reached the socket") { h.socket.sent.isNotEmpty() }
        waitForIdle()

        // ONE control byte. The sink built no bytes at all; the engine applied the modifier once,
        // to a key. The old renderers encoded Ctrl-C here AND handed the emulator a 0x03 it
        // encoded again.
        assertEquals("\u0003", h.socket.sentText)
        // And the one-shot is spent.
        assertEquals(TerminalModState.OFF, surface.keys.ctrl)
    }

    @Test
    fun a_locked_modifier_survives_a_key_and_a_one_shot_does_not() = runComposeUiTest {
        val h = Harness()
        lateinit var surface: TerminalSurface
        setContent {
            surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = true, onExit = null) }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        h.socket.openEpoch()
        waitForIdle()

        surface.keys.press(TerminalKey.Mod(TerminalModKey.CTRL))
        surface.keys.press(TerminalKey.Mod(TerminalModKey.CTRL))
        assertEquals(TerminalModState.LOCKED, surface.keys.ctrl)

        surface.keys.press(TerminalKey.Printable('a'))
        until("the first key went out") { h.socket.sent.isNotEmpty() }
        surface.keys.press(TerminalKey.Printable('b'))
        until("the second key went out") { h.socket.sent.size >= 2 }
        waitForIdle()

        assertEquals("\u0001\u0002", h.socket.sentText)
        assertEquals(TerminalModState.LOCKED, surface.keys.ctrl)
    }

    @Test
    fun a_background_terminal_keeps_no_armed_modifier() = runComposeUiTest {
        val h = Harness()
        lateinit var surface: TerminalSurface
        var active by mutableStateOf(true)
        setContent {
            surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = active, onExit = null) }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        h.socket.openEpoch()
        waitForIdle()

        surface.keys.press(TerminalKey.Mod(TerminalModKey.CTRL))
        surface.keys.press(TerminalKey.Mod(TerminalModKey.CTRL))
        assertEquals(TerminalModState.LOCKED, surface.keys.ctrl)

        active = false
        until("the pane went to the background") { surface.keys.ctrl == TerminalModState.OFF }

        // A lock the user can no longer SEE — the bar is only drawn for the active pane — is a
        // modifier they have no way to turn off, and it would fire into whatever they open next.
        assertEquals(TerminalModState.OFF, surface.keys.ctrl)
        assertEquals(TerminalModState.OFF, surface.keys.alt)
    }

    @Test
    fun hiding_and_showing_a_pane_keeps_its_history_and_never_stops_its_client() = runComposeUiTest {
        val h = Harness()
        var active by mutableStateOf(true)
        setContent {
            val surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = active, onExit = null) }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        h.socket.openEpoch()
        h.socket.pushBytes("before".encodeToByteArray())
        until("the first chunk landed") { h.engine.text == "before" }

        active = false
        waitForIdle()
        // A hidden pane still RECEIVES: its history is what makes coming back instant.
        h.socket.pushBytes("-hidden".encodeToByteArray())
        until("the hidden chunk landed") { h.engine.text == "before-hidden" }

        active = true
        waitForIdle()

        assertEquals("before-hidden", h.engine.text)
        assertEquals(0, h.engine.closes)
        assertEquals(0, h.socket.closedByClient)
        // One socket throughout: hiding is not a disconnect.
        assertEquals(1, h.transport.sockets.size)
    }

    @Test
    fun disposal_closes_the_local_engine_and_ends_the_connection_exactly_once() = runComposeUiTest {
        val h = Harness()
        var mounted by mutableStateOf(true)
        setContent {
            if (mounted) {
                val surface = h.factory.rememberTerminalSurface { h.connect() }
                Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = true, onExit = null) }
            }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        h.socket.openEpoch()
        h.socket.pushBytes("hi".encodeToByteArray())
        until("the terminal is live") { h.engine.text == "hi" }

        mounted = false
        until("the connection was released") { h.transport.released == 1 }
        until("the engine closed") { h.engine.closes > 0 }
        waitForIdle()

        // ONE of everything. The surface asked for one client, opened one engine, held one
        // connection, and let go of both exactly once — which is the whole reason nothing here
        // stops the client a second time "to be safe".
        assertEquals(1, h.connects)
        assertEquals(1, h.engine.closes)
        assertEquals(1, h.transport.released)
        assertEquals(1, h.transport.sockets.size, "a disposed pane does not reconnect")
        assertEquals(
            TerminalSendResult.DISCONNECTED,
            h.client!!.sendInput("x".encodeToByteArray()),
            "the client is stopped for good, not merely between connections",
        )
        // The backing terminal is NOT closed: dropping a viewer is a detach. A close here would
        // kill a shell the user only navigated away from.
        assertTrue(h.socket.controls.none { it.contains("\"close\"") }, "disposal must not close the target")
    }

    @Test
    fun an_exit_ends_the_view_once_and_a_failure_never_does() = runComposeUiTest {
        val h = Harness()
        var exits = 0
        setContent {
            val surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) {
                surface.Content(Modifier, active = true, onExit = remember { { exits++ } })
            }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        h.socket.openEpoch()
        waitForIdle()

        // A recoverable failure is "I cannot see your program", not "your program ended".
        h.socket.push(
            """{"type":"failure","code":"backend-unavailable","recoverable":true,"message":"helper restarting"}""",
        )
        waitForIdle()
        assertEquals(0, exits)

        // The client reconnects on its own; the tab is still standing.
        until("it reconnected") { h.transport.sockets.size >= 2 }
        val second = h.transport.sockets[1]
        second.openEpoch()
        second.push("""{"type":"exit","known":true,"code":0,"signal":null}""")

        until("the view was told") { exits == 1 }
        waitForIdle()
        assertEquals(1, exits)
    }

    @Test
    fun the_surface_only_connects_once_the_grid_is_composed() = runComposeUiTest {
        val h = Harness()
        var draw by mutableStateOf(false)
        setContent {
            val surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) {
                if (draw) surface.Content(Modifier, active = true, onExit = null)
            }
        }
        waitForIdle()
        assertEquals(0, h.transport.sockets.size, "a surface nobody drew opens no socket")

        draw = true
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        assertEquals(1, h.transport.sockets.size)
    }

    @Test
    fun the_grid_reports_its_geometry_and_only_the_visible_pane_claims_it() = runComposeUiTest {
        val h = Harness()
        var active by mutableStateOf(true)
        var windowFocused = false
        setContent {
            windowFocused = LocalWindowInfo.current.isWindowFocused
            val surface = h.factory.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = active, onExit = null) }
        }
        until("the socket opens") { h.transport.sockets.isNotEmpty() }
        h.socket.openEpoch()
        until("the grid reported its geometry") {
            h.socket.controls.any { it.contains("\"resize\"") || it.contains("\"focus\"") }
        }
        waitForIdle()

        // A pane that is BOTH the visible one and in the front window claims the shared size.
        // Whether this harness's window counts as focused is the harness's business, so the
        // assertion follows what it reports rather than assuming one.
        if (windowFocused) {
            assertTrue(
                h.socket.controls.any { it.contains("\"focus\"") && it.contains("true") },
                "the visible pane claims the size: ${h.socket.controls}",
            )
        } else {
            assertTrue(
                h.socket.controls.none { it.contains("\"focus\"") && it.contains("true") },
                "a background window claims nothing: ${h.socket.controls}",
            )
        }

        val before = h.socket.controls.size
        active = false
        until("the claim was released") {
            h.socket.controls.drop(before).any { it.contains("\"focus\"") && it.contains("false") }
        }
    }

    @Test
    fun an_engine_that_never_loads_says_so_and_says_what_to_do() = runComposeUiTest {
        // THE STALE-DEPLOY CASE, WHICH IS THE ONE THAT REACHES USERS AND NEEDS NO CACHE TO HAPPEN.
        // Assets are content-hashed and served `immutable`, so a tab open since before a deploy is
        // holding a bundle whose wasm URL the current build no longer serves — and nobody finds out
        // until the first terminal is opened in that tab, because that is when the engine is first
        // fetched. The engine ships with the app on every target, so this is never "this client has
        // no terminal": it is a deployment fact with exactly one action attached, and a pane that
        // says the wrong one of those leaves the user with nothing to do.
        val h = Harness()
        val refused = GhosttyTerminalViewFactory(
            nowMs = h.clock,
            openSession = { _, _ ->
                error("supermux-terminal.wasm: HTTP 404 (MISSING_BINARY)")
            },
            predictionsOf = { h.predictions },
        )
        setContent {
            val surface = refused.rememberTerminalSurface { h.connect() }
            Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = true, onExit = null) }
        }

        until("the load failure is on screen") {
            onAllNodesWithTag(TERMINAL_LOAD_FAILED_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // The reason the loader gave, verbatim — the status, the ABI or the URL is the only thing
        // that tells anyone which of the failures this was.
        onNodeWithText(
            "supermux-terminal.wasm: HTTP 404 (MISSING_BINARY)",
            substring = true, useUnmergedTree = true,
        ).assertExists()
        // ...and the action, which is the whole point of not drawing a blank pane.
        onNodeWithText("Reload", substring = true, useUnmergedTree = true).assertExists()
        // NOT the "no engine here" hint: this host has one.
        assertEquals(
            0,
            onAllNodesWithTag("terminal_unavailable").fetchSemanticsNodes().size,
            "a failed load is not a host without a terminal",
        )
    }
}
