package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.ArchivedDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.workspace.ProjectRef
import dev.supermux.workspace.projectGroupKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared [ArchivedScreen] (cluster E6) — desktop's suite, moved by name.
 *
 * Two layers, as before:
 *  1. The PURE search predicate [archivedMatchesQuery] is unit-tested directly (no Compose).
 *  2. The screen is exercised via [runComposeUiTest] with a faked archived list + loadLogs lambda.
 *
 * The `AppShell` overlay wiring stays in `:desktop` (`ArchivedHubTest`). New here: the archived
 * WORKSPACE list with Restore that Android contributed, the Compact/standalone `TopAppBar`, the
 * touch-only per-row Resume, and the `ArchivedActions` overload Android's `Route.Archived` uses.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ArchivedScreenTest {

    private fun ComposeUiTest.archivedContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(
        platform = FakePlatform(),
        pointer = pointer,
        widthClass = widthClass,
        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
    ) {
        content()
    }

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
        archivedContent {
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
        archivedContent {
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
        archivedContent {
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

    @Test fun loading_shows_a_spinner_not_the_empty_text() = runComposeUiTest {
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(emptyList(), home, onBack = {}, onResume = {}, loadLogs = { emptyList() }, loading = true)
            }
        }
        waitForIdle()
        // While the fetch is in flight the empty text must NOT flash…
        onNodeWithText("No archived sessions.").assertDoesNotExist()
        onNodeWithText("No matches.").assertDoesNotExist()
    }

    @Test fun resolved_empty_shows_the_no_archived_sessions_text() = runComposeUiTest {
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(emptyList(), home, onBack = {}, onResume = {}, loadLogs = { emptyList() }, loading = false)
            }
        }
        waitForIdle()
        onNodeWithText("No archived sessions.").assertIsDisplayed()
    }

    @Test fun resolved_nonempty_shows_rows_not_the_empty_text() = runComposeUiTest {
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() }, loading = false)
            }
        }
        waitForIdle()
        onNodeWithTag("archived_row_a1").assertIsDisplayed()
        onNodeWithText("No archived sessions.").assertDoesNotExist()
    }

    @Test fun filtered_empty_shows_no_matches_not_no_archived_sessions() = runComposeUiTest {
        // A non-empty archived list but a search that matches nothing → "No matches." (there ARE
        // archived sessions, just none in view), distinct from the truly-empty "No archived sessions."
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        onNodeWithTag("archived_search").performTextInput("nonesuch-zzz")
        waitForIdle()
        onNodeWithText("No matches.").assertIsDisplayed()
        onNodeWithText("No archived sessions.").assertDoesNotExist()
    }

    @Test fun escape_from_the_list_closes_the_overlay_via_on_back() = runComposeUiTest {
        var backCalled = false
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = { backCalled = true }, onResume = {}, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        onNodeWithTag("archived_screen").assertIsDisplayed()
        onNodeWithTag("archived_root").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertTrue(backCalled)
    }

    // ── (2b) the read-only transcript + resume ────────────────────────────────────────────────────

    @Test fun tapping_a_row_opens_the_read_only_transcript_with_no_composer() = runComposeUiTest {
        val logs = listOf(
            LogEntry(id = "m1", ts = "2026-07-09T10:00:00Z", direction = "inbound", text = "hello from alpha"),
            LogEntry(id = "m2", ts = "2026-07-09T10:00:05Z", direction = "outbound", text = "hi back"),
        )
        archivedContent {
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

    @Test fun force_open_id_opens_the_transcript_with_no_click_then_consumes_itself() = runComposeUiTest {
        val logs = listOf(
            LogEntry(id = "m1", ts = "2026-07-09T10:00:00Z", direction = "inbound", text = "hello from alpha"),
        )
        var consumedCount = 0
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { logs },
                    forceOpenId = "a1",
                    onForceOpenConsumed = { consumedCount++ },
                )
            }
        }
        waitForIdle()
        // No click on the row — the chat view is already showing (SM_ARCHIVED_OPEN's live-verification path).
        onNodeWithTag("archived_chat").assertIsDisplayed()
        onNodeWithText("hello from alpha").assertIsDisplayed()
        assertEquals(1, consumedCount)
    }

    @Test fun resume_fires_on_resume_with_the_session_id() = runComposeUiTest {
        var resumed: String? = null
        archivedContent {
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
        archivedContent {
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

    // ── (3) NEW: the archived WORKSPACE list Android contributed ────────────────────────────────

    private fun ws(id: String, name: String, root: String, archivedAt: String? = null) = WorkspaceDto(
        id = id,
        name = name,
        status = "archived",
        workdir = root,
        repoRoot = root,
        archivedAt = archivedAt,
    )

    private val fakeWorkspaces = listOf(
        ws("w1", "feature-a", "$home/proj-a", "2026-07-09T10:00:00Z"),
        ws("w2", "feature-b", "$home/proj-b", "2026-07-09T09:00:00Z"),
    )

    @Test fun use_workspaces_lists_archived_workspaces_grouped_by_project() = runComposeUiTest {
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    archived = emptyList(), home = home, onBack = {}, onResume = {},
                    loadLogs = { emptyList() },
                    workspaces = fakeWorkspaces, useWorkspaces = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("archived_workspace_row_w1").assertExists()
        onNodeWithTag("archived_workspace_row_w2").assertExists()
        onNodeWithTag("archived_workspace_group_$home/proj-a").assertExists()
        onNodeWithText("feature-a").assertIsDisplayed()
        // The session-archive affordances are not this list's.
        onNodeWithTag("archived_search").assertDoesNotExist()
        onNodeWithTag("archived_row_a1").assertDoesNotExist()
    }

    @Test fun restoring_a_workspace_fires_on_restore_and_disables_that_row() = runComposeUiTest {
        var restored: String? = null
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    archived = emptyList(), home = home, onBack = {}, onResume = {},
                    loadLogs = { emptyList() },
                    workspaces = fakeWorkspaces, useWorkspaces = true,
                    onRestore = { restored = it },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("archived_restore_w1").performClick()
        waitForIdle()
        assertEquals("w1", restored)
        onNodeWithText("Restored").assertIsDisplayed()
        onNodeWithTag("archived_restore_w1").assertIsNotEnabled()
        // The other row is untouched.
        onNodeWithTag("archived_restore_w2").assertExists()
    }

    @Test fun use_workspaces_with_nothing_archived_says_no_archived_workspaces() = runComposeUiTest {
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    archived = emptyList(), home = home, onBack = {}, onResume = {},
                    loadLogs = { emptyList() },
                    workspaces = emptyList(), useWorkspaces = true,
                )
            }
        }
        waitForIdle()
        onNodeWithText("No archived workspaces.").assertIsDisplayed()
        onNodeWithText("No archived sessions.").assertDoesNotExist()
    }

    // ── (4) NEW: Compact / standalone chrome ────────────────────────────────────────────────────

    @Test fun compact_paints_a_top_bar_with_back_and_the_filter() = runComposeUiTest {
        var backCalled = false
        archivedContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    fakeArchived, home, onBack = { backCalled = true }, onResume = {},
                    loadLogs = { emptyList() },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("archived_filter").assertExists()
        onNodeWithTag("archived_back").performClick()
        assertTrue(backCalled)
    }

    @Test fun standalone_keeps_the_top_bar_above_compact() = runComposeUiTest {
        archivedContent(pointer = false, widthClass = WindowWidthClass.Medium) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() },
                    standalone = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("archived_back").assertExists()
    }

    @Test fun a_bar_painted_above_suppresses_this_screens_own() = runComposeUiTest {
        archivedContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() },
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("archived_back").assertDoesNotExist()
        onNodeWithTag("archived_filter").assertExists() // desktop's in-body header row instead
    }

    @Test fun a_pointer_host_that_owns_its_chrome_gets_no_bar_even_when_compact() = runComposeUiTest {
        // Desktop narrowed below 600dp: the archived overlay already paints the host picker and
        // closes with Esc, so the screen must stay on its in-body header, not grow a phone bar.
        archivedContent(pointer = true, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() },
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("archived_back").assertDoesNotExist()
        onNodeWithTag("archived_filter").assertExists()
        onNodeWithTag("archived_search").assertExists()
    }

    @Test fun touch_rows_carry_a_resume_button_pointers_do_not() = runComposeUiTest {
        var resumed: String? = null
        archivedContent(pointer = false) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = { resumed = it }, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        val h = onNodeWithTag("archived_row_resume_a1").fetchSemanticsNode().size.height
        val minPx = with(density) { 48.dp.toPx() }
        assertTrue(h >= minPx, "touch Resume target was ${h}px, want >= ${minPx}px")
        onNodeWithTag("archived_row_resume_a1").performClick()
        waitForIdle()
        assertEquals("a1", resumed)
        onNodeWithTag("archived_row_resume_a1").assertIsNotEnabled()
    }

    @Test fun pointer_rows_have_no_inline_resume() = runComposeUiTest {
        archivedContent(pointer = true) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(fakeArchived, home, onBack = {}, onResume = {}, loadLogs = { emptyList() })
            }
        }
        waitForIdle()
        onNodeWithTag("archived_row_resume_a1").assertDoesNotExist()
    }

    // ── (5) NEW: the stateful ArchivedActions overload ──────────────────────────────────────────

    private fun actions(
        archivedWorkspaces: List<WorkspaceDto> = emptyList(),
        liveWorkspaces: List<WorkspaceDto> = emptyList(),
        loadArchivedSessions: suspend () -> List<ArchivedDto> = { fakeArchived },
        resume: suspend (String) -> Unit = {},
        restore: (String) -> Unit = {},
    ) = ArchivedActions(
        archivedWorkspaces = MutableStateFlow(archivedWorkspaces),
        liveWorkspaces = MutableStateFlow(liveWorkspaces),
        loadArchivedSessions = loadArchivedSessions,
        loadLogs = { emptyList() },
        resume = resume,
        restoreWorkspace = restore,
    )

    @Test fun the_actions_overload_falls_back_to_sessions_with_no_live_workspaces() = runComposeUiTest {
        var loads = 0
        archivedContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    actions = actions(loadArchivedSessions = { loads++; fakeArchived }),
                    home = home, standalone = true,
                )
            }
        }
        waitForIdle()
        assertEquals(1, loads)
        onNodeWithTag("archived_row_a1").assertExists()
    }

    @Test fun the_actions_overload_shows_workspaces_when_the_broker_has_live_ones() = runComposeUiTest {
        var loads = 0
        archivedContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    actions = actions(
                        archivedWorkspaces = fakeWorkspaces,
                        liveWorkspaces = listOf(ws("live", "live-one", "$home/proj-a")),
                        loadArchivedSessions = { loads++; fakeArchived },
                    ),
                    home = home, standalone = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("archived_workspace_row_w1").assertExists()
        // The session archive is never fetched on the workspace path.
        assertEquals(0, loads)
    }

    @Test fun the_actions_overload_restores_through_the_holder() = runComposeUiTest {
        var restored: String? = null
        archivedContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    actions = actions(
                        archivedWorkspaces = fakeWorkspaces,
                        liveWorkspaces = listOf(ws("live", "live-one", "$home/proj-a")),
                        restore = { restored = it },
                    ),
                    home = home, standalone = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("archived_restore_w2").performClick()
        waitForIdle()
        assertEquals("w2", restored)
    }

    // ── (6) Persistent-project headers: settings reachable from the archive ─────────────────────

    @Test fun an_archived_project_header_offers_project_settings() = runComposeUiTest {
        val beta = ProjectRef("h1", ProjectDto(id = "b", name = "Beta"))
        var opened: ProjectRef? = null
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    archived = emptyList(), home = home, onBack = {}, onResume = {},
                    loadLogs = { emptyList() },
                    workspaces = listOf(ws("w1", "feature-a", "$home/proj-a").copy(projectId = "b")),
                    useWorkspaces = true,
                    projects = listOf(beta),
                    workspaceHost = { "h1" },
                    onProjectSettings = { opened = it },
                )
            }
        }
        waitForIdle()
        val key = projectGroupKey("h1", "b")
        onNodeWithTag(ProjectTestIds.menu(key)).performClick()
        onNodeWithText("Project settings…").assertIsDisplayed()
        // The archive has no order of its own: settings only.
        onNodeWithTag(ProjectTestIds.MOVE_UP).assertDoesNotExist()
        onNodeWithTag(ProjectTestIds.MOVE_DOWN).assertDoesNotExist()
        onNodeWithTag(ProjectTestIds.SETTINGS).performClick()
        assertEquals(beta, opened)
    }

    @Test fun the_actions_overload_forwards_project_settings() = runComposeUiTest {
        val beta = ProjectRef("h1", ProjectDto(id = "b", name = "Beta"))
        var opened: ProjectRef? = null
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    actions = actions(
                        archivedWorkspaces = listOf(ws("w1", "feature-a", "$home/proj-a").copy(projectId = "b")),
                        liveWorkspaces = listOf(ws("live", "live-one", "$home/proj-c")),
                    ),
                    home = home,
                    projects = listOf(beta),
                    workspaceHost = { "h1" },
                    onProjectSettings = { opened = it },
                )
            }
        }
        waitForIdle()
        onNodeWithTag(ProjectTestIds.menu(projectGroupKey("h1", "b"))).performClick()
        onNodeWithTag(ProjectTestIds.SETTINGS).performClick()
        assertEquals(beta, opened)
    }

    @Test fun a_path_group_has_no_project_menu() = runComposeUiTest {
        archivedContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ArchivedScreen(
                    archived = emptyList(), home = home, onBack = {}, onResume = {},
                    loadLogs = { emptyList() },
                    workspaces = fakeWorkspaces, useWorkspaces = true,
                    onProjectSettings = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag(ProjectTestIds.menu("$home/proj-a")).assertDoesNotExist()
    }
}
