package dev.supermux.desktop.display

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.BrokerApi
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M5-2 Task 4: [DisplayPanel]'s outer state machine (empty / loading / start-display) — the parts
 * that never construct a live [dev.supermux.net.VncClient]. The connected-and-painting path is
 * NOT unit tested here (there is no live-broker seam for a raw WS+RFB socket, and Android has
 * zero test coverage for the equivalent VncView/VncFramebuffer either) — it's proven by this
 * milestone's Task 5 live-verification hook instead.
 *
 * TIMING: [DisplayPanel]'s `LaunchedEffect(session.id) { app.listDisplays() }` suspends on a real
 * HTTP call (the ktor MockEngine, on its OWN dispatcher — not the Compose test frame clock), so a
 * bare `waitForIdle()` can return before that hydration lands (observed as a real, reproducible
 * flake under this suite's full ~620-test run, though never in isolation) — same TIMING note as
 * [dev.supermux.desktop.terminal.TerminalTabsTest]. Every test therefore polls with [waitForTag].
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DisplayPanelTest {

    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude")

    private fun appWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): DesktopAppState {
        val engine = MockEngine { respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json")) }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return DesktopAppState(
            baseUrl = "ws://test:9898", token = "t",
            scope = TestScope(UnconfinedTestDispatcher()), connectOnInit = false, apiOverride = api,
        )
    }

    private fun ComposeUiTest.tagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Block until a node with [tag] exists (hydration / async HTTP settle) — see this class's
     *  TIMING note. */
    private fun ComposeUiTest.waitForTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { tagCount(tag) == 1 }

    @Test fun no_running_display_shows_the_empty_state_with_a_start_button() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DisplayPanel(app = appWith("[]"), session = session)
            }
        }
        waitForTag("display_start_button")
        onNodeWithTag("display_empty_state").assertIsDisplayed()
        onNodeWithTag("display_start_button").assertIsDisplayed()
    }

    @Test fun a_running_display_for_a_different_session_still_shows_the_empty_state() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DisplayPanel(
                    app = appWith("""[{"id":"d1","sessionName":"other","status":"running"}]"""),
                    session = session,
                )
            }
        }
        waitForTag("display_empty_state")
        onNodeWithTag("display_empty_state").assertIsDisplayed()
    }

    @Test fun clicking_start_display_calls_start_display_for_this_session() = runComposeUiTest {
        var recordedBody: String? = null
        val engine = MockEngine { req ->
            // BrokerApi.postReturningJson sets a JSON String body, which ktor wraps as TextContent —
            // mirrors DesktopLspSettingsTest/DesktopFinishTest's bodyText(req.body) helper pattern.
            recordedBody = (req.body as? TextContent)?.text
            respond(
                ByteReadChannel("""{"id":"d1","sessionName":"demo","status":"running"}"""),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        val app = DesktopAppState(
            baseUrl = "ws://test:9898", token = "t",
            scope = TestScope(UnconfinedTestDispatcher()), connectOnInit = false, apiOverride = api,
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) { DisplayPanel(app = app, session = session) }
        }
        waitForTag("display_start_button")

        onNodeWithTag("display_start_button").performClick()
        waitUntil(timeoutMillis = 5_000) { recordedBody != null }

        assertTrue(recordedBody?.contains("\"demo\"") == true)
    }
}
