package dev.supermux.desktop.display

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.shell.TestAppShell
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.desktop.testDeps
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.net.BrokerApi
import dev.supermux.state.HostStore
import dev.supermux.ui.nav.Route
import dev.supermux.ui.theme.AppearanceMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The Displays overlay's wiring into [AppShell] — the desktop half of cluster G4 (the screen's own
 * behaviour is `:ui`'s `DisplaysScreenTest`).
 *
 * The overlay suppresses the screen's Back (the HostScopePicker is its chrome) and `shellShortcuts`
 * is gated off while any overlay is up, so ESCAPE is the only way out: the G4 review caught it as a
 * one-way door and this pins the handler the Settings / AppUpdate entries already had.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DisplaysHubTest {

    private val tempFiles = mutableListOf<java.nio.file.Path>()

    private fun tempPath(name: String): java.nio.file.Path {
        val f = Files.createTempFile("displays_hub_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    @AfterTest fun cleanup() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** A [HostStore] whose HTTP serves one running display. */
    private fun appForDisplays(): HostStore {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            if (req.url.encodedPath == "/displays") {
                respond(
                    """[{"id":"d1","sessionName":"demo","provider":"linux-xvfb",""" +
                        """"transport":"vnc","display":":1","status":"running"}]""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            } else {
                respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    @Test fun overlay_opens_from_route_displays_and_lists_the_streams() = runComposeUiTest {
        val ui = ShellUiState().apply { navigate(Route.Displays) }
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(appForDisplays(), ui)
            }
        }
        waitForIdle()
        onNodeWithTag("displays_overlay").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("displays_row_d1").fetchSemanticsNodes().size == 1
        }
        onNodeWithTag("displays_row_d1").assertIsDisplayed()
        assertTrue(ui.displaysOpen)
    }

    @Test fun escape_closes_the_displays_overlay() = runComposeUiTest {
        val ui = ShellUiState().apply { navigate(Route.Displays) }
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(appForDisplays(), ui)
            }
        }
        waitForIdle()
        onNodeWithTag("displays_overlay").assertIsDisplayed()

        onNodeWithTag("displays_overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertFalse(ui.displaysOpen)
        onNodeWithTag("displays_overlay").assertDoesNotExist()
    }
}
