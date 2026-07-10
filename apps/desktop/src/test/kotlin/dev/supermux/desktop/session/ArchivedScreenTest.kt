package dev.supermux.desktop.session

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.workspace.WorkspaceRoot
import dev.supermux.desktop.workspace.WorkspaceStateStore
import dev.supermux.desktop.workspace.WorkspaceUiState
import dev.supermux.net.ArchivedDto
import dev.supermux.net.BrokerApi
import dev.supermux.proto.LogEntry
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4e Task 2 — the ArchivedScreen (project-filtered, searchable list of archived sessions), the
 * read-only ArchivedChatView (a Timeline over the transcript, no composer), Resume, and the
 * overlay wiring into WorkspaceRoot (open from `ui.archivedOpen`, shortcuts gated while up).
 *
 * Two layers, like the launcher suite:
 *  1. The PURE search predicate [archivedMatchesQuery] is unit-tested directly (no Compose).
 *  2. The screen is exercised via [runComposeUiTest] with a faked archived list + loadLogs lambda;
 *     the overlay + shortcut-gating are exercised through the real [WorkspaceRoot] with a
 *     MockEngine-backed [DesktopAppState] (mirrors WorkspaceRootTest).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ArchivedScreenTest {

    private val home = "/home/u"

    private fun dto(id: String, name: String, workdir: String, killed: String? = null) =
        ArchivedDto(id = id, name = name, workdir = workdir, agent = "claude", killed_at = killed, repo_root = workdir)

    /** alpha+gamma live under proj-a, beta under proj-b — two distinct projects for the filter. */
    private val fakeArchived = listOf(
        dto("a1", "alpha", "$home/proj-a", "2026-07-09T10:00:00Z"),
        dto("b1", "beta", "$home/proj-b", "2026-07-09T09:00:00Z"),
        dto("a2", "gamma", "$home/proj-a", "2026-07-09T08:00:00Z"),
    )

    // ── (1) pure search predicate ────────────────────────────────────────────────────────────────

    @Test fun query_blank_matches_everything() {
        assertTrue(archivedMatchesQuery(dto("a1", "alpha", "$home/proj-a"), ""))
        assertTrue(archivedMatchesQuery(dto("a1", "alpha", "$home/proj-a"), "   "))
    }

    @Test fun query_matches_name_case_insensitively() {
        assertTrue(archivedMatchesQuery(dto("a1", "Alpha", "$home/proj-a"), "alph"))
        assertTrue(archivedMatchesQuery(dto("a1", "Alpha", "$home/proj-a"), "ALPHA"))
    }

    @Test fun query_matches_workdir_and_repo_root() {
        assertTrue(archivedMatchesQuery(dto("a1", "alpha", "$home/proj-zebra"), "zebra"))
    }

    @Test fun query_no_match_returns_false() {
        assertFalse(archivedMatchesQuery(dto("a1", "alpha", "$home/proj-a"), "nonesuch"))
    }

    // ── (2a) the list: rows, project labels, filter, search ───────────────────────────────────────

    @Test fun renders_rows_with_names_and_project_labels() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        onNodeWithTag("archived_row_a1").assertIsDisplayed()
        onNodeWithTag("archived_row_b1").assertIsDisplayed()
        onNodeWithText("alpha").assertIsDisplayed()
        onNodeWithText("beta").assertIsDisplayed()
        // Per-row project label (formatWorkdir → ~/proj-a etc.).
        onNodeWithText("~/proj-b").assertIsDisplayed()
    }

    @Test fun project_filter_narrows_to_the_selected_project() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        // Open the project filter, pick proj-b (key == its workdir/repo_root).
        onNodeWithTag("archived_filter").performClick()
        waitForIdle()
        onNodeWithTag("archived_project_$home/proj-b").performClick()
        waitForIdle()
        // Only beta (the sole proj-b session) survives filterArchivedByProject; alpha/gamma gone.
        onNodeWithTag("archived_row_b1").assertIsDisplayed()
        onNodeWithTag("archived_row_a1").assertDoesNotExist()
        onNodeWithTag("archived_row_a2").assertDoesNotExist()
    }

    @Test fun search_narrows_by_name() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        onNodeWithTag("archived_search").performTextInput("gamma")
        waitForIdle()
        onNodeWithTag("archived_row_a2").assertIsDisplayed()
        onNodeWithTag("archived_row_a1").assertDoesNotExist()
        onNodeWithTag("archived_row_b1").assertDoesNotExist()
    }

    // ── (2b) the read-only transcript + resume ────────────────────────────────────────────────────

    @Test fun tapping_a_row_opens_the_read_only_transcript_with_no_composer() = runComposeUiTest {
        val logs = listOf(
            LogEntry(id = "m1", ts = "2026-07-09T10:00:00Z", direction = "inbound", text = "hello from alpha"),
            LogEntry(id = "m2", ts = "2026-07-09T10:00:05Z", direction = "outbound", text = "hi back"),
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { logs })
            }
        }
        waitForIdle()
        onNodeWithTag("archived_row_a1").performClick()
        waitForIdle()

        onNodeWithTag("archived_chat").assertIsDisplayed()
        // The Timeline rendered the transcript messages…
        onNodeWithText("hello from alpha").assertIsDisplayed()
        onNodeWithText("hi back").assertIsDisplayed()
        // …and there is NO composer (read-only).
        onNodeWithTag("composer-input").assertDoesNotExist()
        onNodeWithTag("composer-send").assertDoesNotExist()
    }

    @Test fun resume_fires_on_resume_with_the_session_id() = runComposeUiTest {
        var resumed: String? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = { resumed = it }, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        onNodeWithTag("archived_row_a1").performClick()
        waitForIdle()
        onNodeWithTag("archived_resume").performClick()
        waitForIdle()
        assertEquals("a1", resumed)
    }

    @Test fun escape_from_the_chat_view_returns_to_the_list() = runComposeUiTest {
        var backCalled = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = { backCalled = true }, onResume = {}, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        onNodeWithTag("archived_row_a1").performClick()
        waitForIdle()
        onNodeWithTag("archived_chat").assertIsDisplayed()
        // While in the chat view the list (archived_screen) is not composed.
        onNodeWithTag("archived_screen").assertDoesNotExist()
        // Escape from the chat view returns to the list (NOT onBack).
        onNodeWithTag("archived_root").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(backCalled)
        onNodeWithTag("archived_screen").assertIsDisplayed()
    }

    // ── (2c) overlay wiring into WorkspaceRoot ────────────────────────────────────────────────────

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

    /** A [DesktopAppState] whose HTTP serves the archived list + a transcript + a resume ack. */
    private fun appForArchived(): DesktopAppState {
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
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }

    @Test fun overlay_opens_from_ui_archived_open_and_loads_the_list() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { archivedOpen = true }
        val app = appForArchived()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
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
        val ui = WorkspaceUiState().apply { archivedOpen = true } // sidebarCollapsed defaults false
        val app = appForArchived()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed)

        onNodeWithTag("archived_search").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        waitForIdle()

        assertFalse(ui.layout.sidebarCollapsed) // NOT toggled — the chord never reached the layout
        assertTrue(ui.archivedOpen)             // ...and the overlay stayed up
    }

    @Test fun resume_from_the_overlay_closes_it() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { archivedOpen = true }
        val app = appForArchived()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
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
