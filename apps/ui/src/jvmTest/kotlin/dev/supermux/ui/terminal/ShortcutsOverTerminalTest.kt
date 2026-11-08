package dev.supermux.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.net.TerminalClient
import dev.supermux.terminal.KeyAction
import dev.supermux.terminal.Modifiers
import dev.supermux.terminal.TerminalEngine
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalKeys
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSize
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.KeyOs
import dev.supermux.ui.keys.RegisterCommand
import dev.supermux.ui.keys.ShortcutDispatcher
import dev.supermux.ui.keys.ShortcutRoot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's keys over the REAL terminal: [GhosttyTerminalViewFactory]'s grid inside a [ShortcutRoot], focused with a
 * click, keys pressed on the keyboard.
 *
 * The terminal takes every key it can name. Its grid's `KeyContext.Terminal` scope is what lets the app claim its own
 * keys first (Ctrl+Shift+T off Apple, ⌘T on a Mac); without that scope those keys would go to the shell. What the
 * terminal got is read where it hands keys over: its engine (a [FakeTerminalEngine], recorded).
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutsOverTerminalTest {

    /** A [FakeTerminalEngine] that records every key the terminal hands it, and whether the terminal has the keyboard. */
    private class KeyRecordingEngine(val screen: FakeTerminalEngine) : TerminalEngine by screen {
        /** Written on the session's thread, read on the test's. */
        val keys = CopyOnWriteArrayList<TerminalKey>()

        @Volatile var focused = false
            private set

        override fun key(key: TerminalKey) {
            keys += key
            screen.key(key)
        }

        override fun focus(focused: Boolean) {
            this.focused = focused
            screen.focus(focused)
        }
    }

    /** A real [TerminalClient] over a fake socket and a real [TerminalSession] over [engine], as in [GhosttyTerminalViewFactoryTest]. */
    private class Harness {
        val engine = KeyRecordingEngine(FakeTerminalEngine(TerminalSize(80, 24, 8, 16)))
        val transport = FakeTerminalTransport()
        private val http = HttpClient(MockEngine { respond("{}") })

        @Volatile var session: TerminalSession? = null
            private set

        val factory = GhosttyTerminalViewFactory(
            nowMs = { 1_000L },
            openSession = { size, effects ->
                engine.screen.size = size
                TerminalSession.open(size = size, limits = TerminalLimits(), effects = effects, engineFactory = { _, _ -> engine })
                    .also { session = it }
            },
        )

        fun connect() = TerminalClient(
            baseUrl = "ws://h:1", token = "t", http = http, sessionId = "s1", workspaceId = "w1", transport = transport,
        )

        /** What the terminal handed its engine for the key [physicalCode], in order. */
        fun keysOf(physicalCode: Int): List<TerminalKey> = engine.keys.filter { it.physicalCode == physicalCode }
    }

    /** On a standard test dispatcher, as [GhosttyTerminalViewFactoryTest] runs the surface (it says why). */
    private fun runTerminalTest(block: suspend ComposeUiTest.() -> Unit) =
        runComposeUiTest(effectContext = StandardTestDispatcher(), block = block)

    private fun ComposeUiTest.until(what: String, h: Harness, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = 10_000) { condition() }
        } catch (timeout: Throwable) {
            throw AssertionError("timed out waiting until $what; the terminal's keys: ${h.engine.keys}", timeout)
        }
    }

    /**
     * The real terminal in a [ShortcutRoot] for [os], with New terminal and Find in chat registered at the root (so both
     * are in reach from the terminal), clicked into once its first frame is on screen. Each command run is added to [runs].
     */
    private fun ComposeUiTest.focusedTerminal(os: KeyOs, h: Harness, runs: MutableList<String>) {
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher(os = os) }) {
                RegisterCommand(AppCommands.NewTerminal) { runs += "new terminal"; true }
                RegisterCommand(AppCommands.Find) { runs += "find in chat"; true }
                val surface = h.factory.rememberTerminalSurface { h.connect() }
                Box(Modifier.size(400.dp, 300.dp)) { surface.Content(Modifier, active = true, onExit = null) }
            }
        }
        until("the socket opens", h) { h.transport.sockets.isNotEmpty() }
        h.transport.sockets.first().openEpoch()
        // A click only focuses a grid that has a frame to hit.
        until("the grid sized the session", h) { h.engine.screen.resizes > 0 }
        until("the grid's frame is published", h) { h.session?.let { it.viewports.value.generation == h.engine.screen.generation } == true }
        waitForIdle()
        grid().performClick()
        until("the click gave the terminal the keyboard", h) { h.engine.focused }
    }

    private fun ComposeUiTest.grid() = onNodeWithTag(TERMINAL_GRID_TAG)

    /**
     * Presses a plain x and waits for the terminal to hand its engine the release: the engine takes keys in order, so
     * every key pressed before it has arrived too. A key that is not there now never reached the terminal.
     */
    private fun ComposeUiTest.allKeysIn(h: Harness) {
        val released = h.keysOf(TerminalKeys.X).count { it.action == KeyAction.RELEASE }
        grid().performKeyInput { pressKey(Key.X) }
        until("the x pressed after them reached the terminal", h) {
            h.keysOf(TerminalKeys.X).count { it.action == KeyAction.RELEASE } > released
        }
    }

    private fun key(physicalCode: Int, modifiers: Int, action: Int) = TerminalKey(physicalCode, "", modifiers, action)

    @Test fun offAppleCtrlTReachesTheShellAndCtrlShiftTOpensATerminal() = runTerminalTest {
        val h = Harness()
        val runs = mutableListOf<String>()
        focusedTerminal(KeyOs.Other, h, runs)
        val ctrlT = listOf(key(TerminalKeys.T, Modifiers.CTRL, KeyAction.PRESS), key(TerminalKeys.T, Modifiers.CTRL, KeyAction.RELEASE))

        grid().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.T) } }
        until("^T reached the terminal", h) { h.keysOf(TerminalKeys.T).size >= 2 }
        assertEquals(ctrlT, h.keysOf(TerminalKeys.T))
        assertEquals(emptyList(), runs, "^T is the shell's")

        grid().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.T) } } }
        waitForIdle()
        assertEquals(listOf("new terminal"), runs)
        allKeysIn(h)
        assertEquals(ctrlT, h.keysOf(TerminalKeys.T), "neither Ctrl+Shift+T's press nor its release reached the terminal")
    }

    @Test fun onAppleCmdTOpensATerminalAndTheShellSeesNeitherItsPressNorItsRelease() = runTerminalTest {
        val h = Harness()
        val runs = mutableListOf<String>()
        focusedTerminal(KeyOs.Apple, h, runs)
        val ctrlT = listOf(key(TerminalKeys.T, Modifiers.CTRL, KeyAction.PRESS), key(TerminalKeys.T, Modifiers.CTRL, KeyAction.RELEASE))

        // ⌃T is the shell's on a Mac.
        grid().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.T) } }
        until("⌃T reached the terminal", h) { h.keysOf(TerminalKeys.T).size >= 2 }
        assertEquals(ctrlT, h.keysOf(TerminalKeys.T))
        assertEquals(emptyList(), runs)

        grid().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.T) } }
        waitForIdle()
        assertEquals(listOf("new terminal"), runs)
        allKeysIn(h)
        assertEquals(ctrlT, h.keysOf(TerminalKeys.T), "neither ⌘T's press nor its release reached the terminal")
    }

    @Test fun onAppleTheTerminalKeepsItsOwnCmdKeysAndTheAppGetsTheRest() = runTerminalTest {
        val h = Harness()
        val runs = mutableListOf<String>()
        focusedTerminal(KeyOs.Apple, h, runs)

        // ⌘C is the terminal's (terminalKeeps). The app binds nothing to it; with nothing selected the terminal sends it on.
        grid().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.C) } }
        until("⌘C reached the terminal", h) { h.keysOf(TerminalKeys.C).size >= 2 }
        assertEquals(
            listOf(key(TerminalKeys.C, Modifiers.SUPER, KeyAction.PRESS), key(TerminalKeys.C, Modifiers.SUPER, KeyAction.RELEASE)),
            h.keysOf(TerminalKeys.C),
        )
        assertEquals(emptyList(), runs)

        // Any other ⌘ key is the app's.
        grid().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.T) } }
        waitForIdle()
        assertEquals(listOf("new terminal"), runs)

        // ⌘F is bound (find in chat, in reach at the root), but the terminal keeps it: its own find opens. Last, as the
        // find bar takes the keyboard.
        grid().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.F) } }
        waitForIdle()
        assertEquals(listOf("new terminal"), runs, "⌘F stayed with the terminal")
        onNodeWithTag(TERMINAL_SEARCH_TAG).assertExists()
    }
}
