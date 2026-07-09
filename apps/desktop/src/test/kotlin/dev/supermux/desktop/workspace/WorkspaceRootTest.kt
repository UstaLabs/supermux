package dev.supermux.desktop.workspace

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.input.key.Key
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.proto.ClientFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import dev.supermux.net.BrokerApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4a Task 5 — wiring the launcher into the app shell. [WorkspaceRoot] wasn't previously
 * UI-tested (its detail pane, [SessionDetail], drags in the KCEF-backed editor); this suite adds
 * the minimal harness needed to exercise the launcher overlay without ever selecting a session
 * (so [SessionDetail]/KCEF never mounts): a real [DesktopAppState] (connectOnInit=false, HTTP via
 * a ktor MockEngine, outbound WS frames captured through `sendFrameOverride`) and a real
 * [WorkspaceUiState] + [WorkspaceStateStore]/[LauncherStore] pointed at a scratch temp file each,
 * so no test ever touches the developer's real ~/.config/supermux-desktop.
 *
 * Covers: onNewSession (rail `+`, and by extension Ctrl+N/the menu item, which just flip the SAME
 * `ui.launcherOpen`) opens the overlay; a submit whose `createSessionWithFirstMessage` resolves
 * selects the session, sends the first message, and closes the overlay; a submit that resolves to
 * null (invalid workdir) keeps the overlay open and surfaces the inline error; Escape closes
 * without spawning and leaves the draft on disk.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class WorkspaceRootTest {

    private val tempFiles = mutableListOf<java.nio.file.Path>()

    private fun tempPath(name: String): java.nio.file.Path {
        val f = Files.createTempFile("workspace_root_test_$name", ".json")
        Files.deleteIfExists(f) // the stores create-on-write; start absent like a fresh profile
        tempFiles.add(f)
        return f
    }

    @AfterTest fun cleanup() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** A [DesktopAppState] whose HTTP answers /paths/validate and /sessions (spawn); outbound WS
     *  frames (e.g. the first-message Send) land in [sent] instead of a live socket. */
    private fun appFor(sent: MutableList<ClientFrame>, validateOk: Boolean = true, spawnId: String = "sess-new"): DesktopAppState {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when (req.url.encodedPath) {
                "/paths/validate" -> respond(
                    """{"ok":$validateOk,"path":${if (validateOk) "\"/resolved\"" else "null"}}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                "/sessions" -> respond(
                    """{"id":"$spawnId","name":"feat-x","workdir":"/resolved","agent":"claude"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { sent.add(it) },
            apiOverride = api,
        )
    }

    @Test fun on_new_session_opens_the_launcher_overlay() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { layout.sidebarCollapsed = true } // rail mode → "rail_new"
        val app = appFor(mutableListOf())
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_overlay").assertDoesNotExist()

        onNodeWithTag("rail_new").performClick()
        waitForIdle()

        assertTrue(ui.launcherOpen)
        onNodeWithTag("launcher_overlay").assertIsDisplayed()
        onNodeWithTag("launcher_message").assertIsDisplayed()
    }

    @Test fun submit_with_a_resolved_id_selects_sends_and_closes() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val app = appFor(sent, spawnId = "sess-new")
        val ui = WorkspaceUiState().apply { launcherOpen = true }
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("hello there")
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()

        assertEquals("sess-new", ui.selectedId)
        assertFalse(ui.launcherOpen)
        onNodeWithTag("launcher_overlay").assertDoesNotExist()

        val send = sent.filterIsInstance<ClientFrame.Send>().singleOrNull()
        assertEquals("sess-new", send?.session)
        assertEquals("hello there", send?.args?.text)
    }

    @Test fun submit_with_a_null_id_keeps_the_overlay_open_and_surfaces_the_error() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val app = appFor(sent, validateOk = false) // invalid workdir → createSessionWithFirstMessage returns null
        val ui = WorkspaceUiState().apply { launcherOpen = true }
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("hello there")
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()

        assertNull(ui.selectedId)
        assertTrue(ui.launcherOpen) // stays open — the caller never got an id to select/close on
        onNodeWithTag("launcher_overlay").assertIsDisplayed()
        onNodeWithTag("launcher_error").assertIsDisplayed()
        assertTrue(sent.filterIsInstance<ClientFrame.Send>().isEmpty()) // never sent a first message
    }

    @Test fun escape_closes_without_spawning_and_the_draft_survives_on_disk() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val app = appFor(sent)
        val ui = WorkspaceUiState().apply { launcherOpen = true }
        val launcherStore = LauncherStore(tempPath("launcher"))
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), launcherStore)
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("a draft in progress")
        waitForIdle()

        onNodeWithTag("launcher_overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertFalse(ui.launcherOpen)
        assertNull(ui.selectedId) // no session was ever created
        onNodeWithTag("launcher_overlay").assertDoesNotExist()
        // Never spawned/sent — WorkspaceRoot's own viewing-presence heartbeat may still emit a
        // Viewing frame (unrelated to the launcher), so check the Send frame specifically.
        assertTrue(sent.filterIsInstance<ClientFrame.Send>().isEmpty())
        // The dispose-flush (T4) persists the in-progress text on the way out.
        assertEquals("a draft in progress", launcherStore.loadDraft().text)
    }
}
