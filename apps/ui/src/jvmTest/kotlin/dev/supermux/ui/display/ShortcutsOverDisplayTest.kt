package dev.supermux.ui.display

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.net.DisplayStream
import dev.supermux.net.VncClient
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.KeyOs
import dev.supermux.ui.keys.RegisterCommand
import dev.supermux.ui.keys.ShortcutDispatcher
import dev.supermux.ui.keys.ShortcutRoot
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's keys over the REAL remote display: [DisplayStreamSurface]'s VNC surface (a pointer client, so the surface
 * itself takes the keys) inside a [ShortcutRoot], focused with a click, keys pressed on the keyboard.
 *
 * The surface takes every key it can name. Its `KeyContext.Display` scope is what lets the app claim its own keys first
 * (⌘T on a Mac, Ctrl+Shift+T elsewhere); without that scope those keys would go to the remote screen. The client never
 * connects (there is no seam for a live RFB socket), so where a key went is read from both sides of the surface instead.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutsOverDisplayTest {

    private val vncStream = DisplayStream(
        id = "d2", sessionName = "demo", provider = "linux-xvfb",
        transport = "vnc", status = "running",
    )

    /** A real [VncClient] over a MockEngine, as in [DisplayPanelTest]: it never connects, and the surface works all the same. */
    private fun vncActions(): DisplayActions {
        val http = HttpClient(MockEngine { respond(ByteReadChannel(""), HttpStatusCode.NotFound) })
        return DisplayActions(
            displays = MutableStateFlow(listOf(vncStream)),
            listDisplays = { listOf(vncStream) },
            connectVnc = { VncClient("ws://test:9898", "t", http, it) },
        )
    }

    /** The T key's events seen between the app's root and the surface. */
    private class Route {
        /** In the preview pass, below the root: the app let them through to the surface. */
        val passedApp = mutableListOf<KeyEventType>()

        /** In the bubble pass, back out of the surface: it left them unused. */
        val leftBySurface = mutableListOf<KeyEventType>()

        fun clear() {
            passedApp.clear()
            leftBySurface.clear()
        }
    }

    /** The VNC surface in a [ShortcutRoot] for [os], with New terminal registered at the root, clicked into. */
    private fun ComposeUiTest.focusedDisplay(os: KeyOs, route: Route, runs: MutableList<String>) {
        val actions = vncActions()
        setPlatformContent(platform = FakePlatform(), pointer = true) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ShortcutRoot(remember { ShortcutDispatcher(os = os) }) {
                    RegisterCommand(AppCommands.NewTerminal) { runs += "new terminal"; true }
                    Box(
                        Modifier.size(400.dp, 300.dp)
                            .onPreviewKeyEvent { if (it.key == Key.T) route.passedApp += it.type; false }
                            .onKeyEvent { if (it.key == Key.T) route.leftBySurface += it.type; false },
                    ) {
                        DisplayStreamSurface(vncStream, actions)
                    }
                }
            }
        }
        waitForIdle()
        surface().performClick()
        surface().assertIsFocused()
    }

    private fun ComposeUiTest.surface() = onNodeWithTag("vnc_surface")

    @Test fun onAppleCmdTOverARemoteDisplayOpensATerminal() = runComposeUiTest {
        val route = Route()
        val runs = mutableListOf<String>()
        focusedDisplay(KeyOs.Apple, route, runs)

        // A plain t is the remote screen's: the surface takes it, press and release.
        surface().performKeyInput { pressKey(Key.T) }
        waitForIdle()
        assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), route.passedApp)
        assertEquals(emptyList(), route.leftBySurface)
        route.clear()

        surface().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.T) } }
        waitForIdle()
        assertEquals(listOf("new terminal"), runs)
        assertEquals(emptyList(), route.passedApp, "neither ⌘T's press nor its release reached the remote screen")
    }

    @Test fun offAppleARemoteDisplayKeepsCtrlTAndGivesUpCtrlShiftT() = runComposeUiTest {
        val route = Route()
        val runs = mutableListOf<String>()
        focusedDisplay(KeyOs.Other, route, runs)

        // The test injects Ctrl+T with the character 't', as Android reports it, and the surface sends that on. Desktop
        // AWT reports ^T (0x14) instead, which the surface cannot name (keysymForKeyEvent): there it leaves the key, and
        // the app's bubble pass opens a terminal.
        surface().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.T) } }
        waitForIdle()
        assertEquals(emptyList(), runs, "Ctrl+T is the remote screen's")
        assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), route.passedApp)
        assertEquals(emptyList(), route.leftBySurface, "the surface took Ctrl+T")
        route.clear()

        surface().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.T) } } }
        waitForIdle()
        assertEquals(listOf("new terminal"), runs)
        assertEquals(emptyList(), route.passedApp, "neither Ctrl+Shift+T's press nor its release reached the remote screen")
    }
}
