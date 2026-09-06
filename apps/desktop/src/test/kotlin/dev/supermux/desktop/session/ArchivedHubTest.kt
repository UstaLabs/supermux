package dev.supermux.desktop.session

import dev.supermux.desktop.testDeps

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.ui.nav.Route
import dev.supermux.desktop.shell.AppShell
import dev.supermux.desktop.shell.ShellStateStore
import dev.supermux.desktop.shell.ShellUiState
import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The archived overlay's wiring into [AppShell] — what stays in `:desktop` after cluster E6 moved
 * [dev.supermux.ui.session.ArchivedScreen] to `:ui`: it opens from `Route.Archived`, loads the
 * list, gates workspace chords while it is up, and closes on Resume.
 *
 * The screen's own behaviour is `:ui`'s `ArchivedScreenTest`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ArchivedHubTest {

    private val home = "/home/u"

    // ── (2c) overlay wiring into AppShell ────────────────────────────────────────────────────

    private val tempFiles = mutableListOf<java.nio.file.Path>()

    private fun tempPath(name: String): java.nio.file.Path {
        val f = Files.createTempFile("archived_screen_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    @AfterTest fun cleanup() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** A [HostStore] whose HTTP serves the archived list + a transcript + a resume ack. */
    private fun appForArchived(): HostStore {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                req.url.encodedPath == "/archived-sessions" -> respond(
                    """[{"id":"a1","name":"alpha","workdir":"$home/proj-a","agent":"claude",""" +
                        """"killed_at":"2026-07-09T10:00:00Z","repo_root":"$home/proj-a"}]""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                req.url.encodedPath.endsWith("/messages") -> respond(
                    """[{"id":"m1","ts":"2026-07-09T10:00:00Z","direction":"inbound","text":"hello from alpha"}]""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }

    @Test fun overlay_opens_from_ui_archived_open_and_loads_the_list() = runComposeUiTest {
        val ui = ShellUiState().apply { navigate(Route.Archived) }
        val app = appForArchived()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(app, ui, ShellStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        onNodeWithTag("archived_overlay").assertIsDisplayed()
        onNodeWithTag("archived_screen").assertIsDisplayed()
        // The list loaded from app.archived() (MockEngine) and rendered the row.
        onNodeWithTag("archived_row_a1").assertIsDisplayed()
    }

    @Test fun workspace_shortcuts_are_gated_off_while_the_archived_overlay_is_up() = runComposeUiTest {
        // Mirrors the launcher gating test: Ctrl+B while the archived overlay is up must NOT toggle
        // the sidebar behind it (ui.overlayOpen gates workspaceShortcuts OFF).
        val ui = ShellUiState().apply { navigate(Route.Archived) } // sidebarCollapsed defaults false
        val app = appForArchived()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(app, ui, ShellStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        assertFalse(ui.sidebarCollapsed)

        onNodeWithTag("archived_search").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        waitForIdle()

        assertFalse(ui.sidebarCollapsed) // NOT toggled — the chord never reached the layout
        assertTrue(ui.archivedOpen)             // ...and the overlay stayed up
    }

    @Test fun resume_from_the_overlay_closes_it() = runComposeUiTest {
        val ui = ShellUiState().apply { navigate(Route.Archived) }
        val app = appForArchived()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(app, ui, ShellStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        onNodeWithTag("archived_row_a1").performClick()
        waitForIdle()
        onNodeWithTag("archived_resume").performClick()
        waitForIdle()

        assertFalse(ui.archivedOpen)
        assertNull(ui.selectedId) // resume brings the session back via a WS frame, not a selection
        onNodeWithTag("archived_overlay").assertDoesNotExist()
    }
}
