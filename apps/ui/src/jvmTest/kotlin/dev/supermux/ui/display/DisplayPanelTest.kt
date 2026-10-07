package dev.supermux.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BrokerApi
import dev.supermux.net.DisplayStream
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.FakeVideoSurfaceFactory
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The shared [DisplayPanel] (cluster G4) — desktop's suite moved by name, plus the transport switch
 * Android contributed.
 *
 * Covers the panel's outer state machine (empty / loading / start-display) — the parts that never
 * construct a live `VncClient`. The connected-and-painting VNC path is still NOT unit tested (there
 * is no live-broker seam for a raw WS+RFB socket); the h264 path IS, because the decoder is behind
 * the G1 `VideoSurfaceFactory` seam and a fake stands in for it.
 *
 * TIMING: the panel's `LaunchedEffect { listDisplays() }` suspends on a real HTTP call (the ktor
 * MockEngine, on its OWN dispatcher — not the Compose test frame clock), so a bare `waitForIdle()`
 * can return before that hydration lands. Every test therefore polls with [waitForTag].
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DisplayPanelTest {

    private fun deps(client: HttpClient) = HostStoreDeps(
        httpFactory = { client },
        settings = FakeSettingsStore(),
        clock = FixedClock(),
    )

    private fun appWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): HostStore {
        val engine = MockEngine {
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine)
        return HostStore(
            baseUrl = "ws://test:9898", token = "t",
            scope = TestScope(UnconfinedTestDispatcher()), connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", http),
            deps = deps(http),
        )
    }

    private fun ComposeUiTest.tagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Block until a node with [tag] exists (hydration / async HTTP settle). */
    private fun ComposeUiTest.waitForTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { tagCount(tag) == 1 }

    @Composable
    private fun panel(app: HostStore, sessionName: String = "demo") =
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            DisplayPanel(sessionName = sessionName, actions = rememberDisplayActions(app))
        }

    // ── desktop's three, by name ──────────────────────────────────────────────────────────────

    @Test fun no_running_display_shows_the_empty_state_with_a_start_button() = runComposeUiTest {
        val app = appWith("[]")
        setPlatformContent { panel(app) }
        waitForTag("display_start_button")
        onNodeWithTag("display_empty_state").assertIsDisplayed()
        onNodeWithTag("display_start_button").assertIsDisplayed()
        onNodeWithTag("display_refresh_button").assertIsDisplayed()
    }

    @Test fun a_running_display_for_a_different_session_still_shows_the_empty_state() = runComposeUiTest {
        val app = appWith("""[{"id":"d1","sessionName":"other","status":"running"}]""")
        setPlatformContent { panel(app) }
        waitForTag("display_empty_state")
        onNodeWithTag("display_empty_state").assertIsDisplayed()
    }

    @Test fun clicking_start_display_calls_start_display_for_this_session() = runComposeUiTest {
        var recordedBody: String? = null
        val engine = MockEngine { req ->
            recordedBody = (req.body as? TextContent)?.text
            respond(
                ByteReadChannel("""{"id":"d1","sessionName":"demo","status":"running"}"""),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898", token = "t",
            scope = TestScope(UnconfinedTestDispatcher()), connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", http),
            deps = deps(http),
        )
        setPlatformContent { panel(app) }
        waitForTag("display_start_button")

        onNodeWithTag("display_start_button").performClick()
        waitUntil(timeoutMillis = 5_000) { recordedBody != null }

        assertTrue(recordedBody?.contains("\"demo\"") == true)
    }

    // ── the transport switch (Android's, now over the G1 seam) ────────────────────────────────

    private fun actionsWith(vararg streams: DisplayStream) = DisplayActions(
        displays = MutableStateFlow(streams.toList()),
        listDisplays = { streams.toList() },
    )

    private val vncStream = DisplayStream(
        id = "d2", sessionName = "demo", provider = "linux-xvfb",
        transport = "vnc", status = "running",
    )

    private val h264 = DisplayStream(
        id = "d1", sessionName = "demo", provider = "android-scrcpy",
        transport = "h264", status = "running",
    )

    @Test fun an_h264_stream_renders_through_the_platform_video_decoder() = runComposeUiTest {
        val video = FakeVideoSurfaceFactory()
        val platform = FakePlatform(caps = NO_CAPS.copy(scrcpy = true)).apply { this.video = video }
        setPlatformContent(platform = platform) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DisplayStreamSurface(h264, actionsWith(h264))
            }
        }
        waitForIdle()
        onNodeWithTag("fake_video_surface").assertIsDisplayed()
        assertEquals(listOf("d1"), video.streams)
        assertEquals(0, tagCount("vnc_surface"))
    }

    @Test fun an_h264_stream_without_a_decoder_says_unsupported_rather_than_speaking_rfb() =
        runComposeUiTest {
            // Desktop: `videoDecoder()` is null, so an H.264 socket must NOT be handed to a VNC
            // client — the panel keeps desktop's honest "unsupported transport" message.
            setPlatformContent(platform = FakePlatform()) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    DisplayStreamSurface(h264, actionsWith(h264))
                }
            }
            waitForIdle()
            onNodeWithTag("display_unsupported").assertIsDisplayed()
            assertEquals(0, tagCount("vnc_surface"))
            assertEquals(0, tagCount("fake_video_surface"))
        }

    @Test fun an_h264_stream_without_the_scrcpy_cap_does_not_take_the_decoder_branch() =
        runComposeUiTest {
            val video = FakeVideoSurfaceFactory()
            // A decoder exists but the host does not declare the capability: same answer as none.
            val platform = FakePlatform().apply { this.video = video }
            setPlatformContent(platform = platform) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    DisplayStreamSurface(h264, actionsWith(h264))
                }
            }
            waitForIdle()
            onNodeWithTag("display_unsupported").assertIsDisplayed()
            assertTrue(video.streams.isEmpty())
        }

    @Test fun the_transport_switch_is_a_rule_not_a_branch() {
        // h264 needs BOTH a decoder and the cap; without either it is unsupported, never RFB.
        assertEquals(DisplayTransport.VIDEO, displayTransportFor("h264", hasDecoder = true, scrcpyCap = true))
        assertEquals(DisplayTransport.UNSUPPORTED, displayTransportFor("h264", hasDecoder = false, scrcpyCap = true))
        assertEquals(DisplayTransport.UNSUPPORTED, displayTransportFor("h264", hasDecoder = true, scrcpyCap = false))
        // VNC never takes the decoder branch, even where one exists.
        assertEquals(DisplayTransport.VNC, displayTransportFor("vnc", hasDecoder = true, scrcpyCap = true))
        // An older broker sends no transport at all — that was always VNC.
        assertEquals(DisplayTransport.VNC, displayTransportFor("", hasDecoder = false, scrcpyCap = false))
        assertEquals(DisplayTransport.UNSUPPORTED, displayTransportFor("webrtc", hasDecoder = true, scrcpyCap = true))
    }
    // ── the VNC surface's chrome: hidden keyboard under Touch only ────────────────────────────

    /** A real [dev.supermux.net.VncClient] over a MockEngine: its `run()` fails the WS upgrade and
     *  backs off, which is all these tests need — the surface composes and paints its chrome. */
    private fun vncActions(): DisplayActions {
        val http = HttpClient(MockEngine { respond(ByteReadChannel(""), HttpStatusCode.NotFound) })
        return DisplayActions(
            displays = MutableStateFlow(listOf(vncStream)),
            listDisplays = { listOf(vncStream) },
            connectVnc = { dev.supermux.net.VncClient("ws://test:9898", "t", http, it) },
        )
    }

    @Test fun a_touch_client_gets_the_hidden_keyboard_field_and_its_toggle() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(), pointer = false) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DisplayStreamSurface(vncStream, vncActions())
            }
        }
        waitForIdle()
        onNodeWithTag("vnc_surface").assertIsDisplayed()
        assertEquals(1, tagCount("display_hidden_keyboard"))
        assertEquals(1, tagCount("display_keyboard_toggle"))
        // The union control bar: Ctrl+Alt+Del is there for both clients.
        assertEquals(1, tagCount("display_ctrl_alt_del"))
        assertEquals(1, tagCount("display_status_chip"))
    }

    @Test fun a_pointer_client_has_a_real_keyboard_and_gets_no_hidden_field() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(), pointer = true) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DisplayStreamSurface(vncStream, vncActions())
            }
        }
        waitForIdle()
        onNodeWithTag("vnc_surface").assertIsDisplayed()
        assertEquals(0, tagCount("display_hidden_keyboard"))
        assertEquals(0, tagCount("display_keyboard_toggle"))
        assertEquals(1, tagCount("display_ctrl_alt_del"))
    }

    @Test fun a_touch_surface_does_not_steal_focus_from_the_hidden_keyboard_on_a_tap() =
        runComposeUiTest {
            setPlatformContent(platform = FakePlatform(), pointer = false) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    DisplayStreamSurface(vncStream, vncActions())
                }
            }
            waitForIdle()
            // Raise the soft keyboard, then tap the remote surface.
            onNodeWithTag("display_keyboard_toggle").performClick()
            waitForIdle()
            onNodeWithTag("display_hidden_keyboard").assertIsFocused()

            onNodeWithTag("vnc_surface").performTouchInput { click(Offset(10f, 10f)) }
            waitForIdle()

            // The surface is not focusable under Touch (G4 review): the IME stays up, and the
            // field only re-requests focus when the toggle flips, so a steal here was permanent.
            onNodeWithTag("display_hidden_keyboard").assertIsFocused()
        }
}
