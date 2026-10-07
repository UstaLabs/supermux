package dev.supermux.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import dev.supermux.net.ForgeAccount
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeSearchResponse
import dev.supermux.net.PathValidation
import dev.supermux.net.RemoteRepo
import dev.supermux.session.formatWorkdir
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/**
 * The shared [ProjectPicker] — desktop's forge omnibox (typed-path validation, failure-vs-empty
 * search, paging, keyboard actions, the un-abortable clone's Hide overlay) moved out of
 * `SessionLauncherScreen` verbatim, plus the Compact branch that used to be Android's
 * `ProjectPickerSheet`: the same body inside a `ModalBottomSheet`.
 *
 * The desktop cases run at the desktop width class so they assert exactly what they always did;
 * the `compact_*` cases provide the phone container (open / select / real-gesture dismiss, a
 * dismiss while a clone is in flight, and the omnibox itself).
 */
@OptIn(ExperimentalTestApi::class)
class ProjectPickerTest {

    private fun ComposeUiTest.pickerContent(
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        /** The container follows THIS, not the width: no pointer → the bottom sheet. */
        pointer: Boolean = widthClass != WindowWidthClass.Compact,
        content: @Composable () -> Unit,
    ) = setPlatformContent(
        pointer = pointer,
        widthClass = widthClass,
        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
    }

    /** Waits for [block] to stop throwing (the moved suite's polling idiom). */
    private fun ComposeUiTest.waitFor(block: () -> Unit) =
        waitUntil(timeoutMillis = 5_000) {
            try {
                block()
                true
            } catch (_: Throwable) {
                false
            }
        }

    private fun forgeConn(
        id: String = "c1",
        host: String = "github.com",
        login: String = "alice",
    ) = ForgeConnection(id = id, host = host, account = ForgeAccount(login = login))

    private fun remote(
        connectionId: String = "c1",
        owner: String = "alice",
        name: String = "widget",
    ) = RemoteRepo(
        connectionId = connectionId,
        owner = owner,
        name = name,
        fullName = "$owner/$name",
    )

    private fun searchOk(vararg repos: RemoteRepo) = ForgeSearchResponse(repos = repos.toList())

    // ── the picker under its pre-F5 argument shape (the lambdas now travel in LauncherActions) ──

    @Composable
    private fun Picker(
        expanded: Boolean = true,
        current: String,
        projects: List<String> = emptyList(),
        home: String = "/home/u",
        validatePath: suspend (String) -> PathValidation? = { null },
        loadForges: suspend () -> List<ForgeConnection> = { emptyList() },
        searchForge: suspend (String) -> ForgeSearchResponse? = { ForgeSearchResponse() },
        cloneForge: suspend (String, String, String) -> String? = { _, _, _ -> null },
        createLocalRepo: suspend (String) -> String? = { null },
        createForge: suspend (String, String) -> String? = { _, _ -> null },
        onPick: (String) -> Unit,
        onDismiss: () -> Unit,
        useDropdownMenu: Boolean = true,
    ) = ProjectPicker(
        expanded = expanded,
        current = current,
        projects = projects,
        home = home,
        actions = LauncherActions(
            validatePath = validatePath,
            listForges = loadForges,
            searchForge = searchForge,
            cloneForge = cloneForge,
            createLocalRepo = createLocalRepo,
            createForge = createForge,
        ),
        onPick = onPick,
        onDismiss = onDismiss,
        useDropdownMenu = useDropdownMenu,
    )

    @Test fun project_picker_search_field_filters_project_list() = runComposeUiTest {
        pickerContent {
            Box {
                Picker(
                    current = "/home/u/alpha",
                    projects = listOf("/home/u/alpha", "/home/u/beta", "/home/u/gamma"),
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        // All three projects visible to start.
        onNodeWithTag("project_row_/home/u/alpha").assertIsDisplayed()
        onNodeWithTag("project_row_/home/u/beta").assertIsDisplayed()
        onNodeWithTag("project_row_/home/u/gamma").assertIsDisplayed()

        // Typing into the search field narrows the list.
        onNodeWithTag("launcher_project_search").performTextInput("beta")
        waitForIdle()
        onNodeWithTag("project_row_/home/u/beta").assertIsDisplayed()
        onNodeWithTag("project_row_/home/u/alpha").assertDoesNotExist()
        onNodeWithTag("project_row_/home/u/gamma").assertDoesNotExist()
    }

    @Test fun project_picker_invalid_path_shows_validation_and_does_not_pick() = runComposeUiTest {
        // Free path is typed into the single search field → "Use this path" row, which still
        // validates via validatePath before it picks.
        var picked: String? = null
        var dismissed = false
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    validatePath = { PathValidation(ok = false, path = null, error = "no such directory") },
                    onPick = { picked = it },
                    onDismiss = { dismissed = true },
                )
            }
        }
        onNodeWithTag("launcher_project_search").performTextInput("/nope")
        waitForIdle()
        onNodeWithTag("launcher_use_path").assertIsDisplayed()
        onNodeWithTag("launcher_use_path").performClick()
        waitForIdle()
        onNodeWithTag("launcher_path_error").assertIsDisplayed()
        onNodeWithText("no such directory").assertIsDisplayed()
        assertNull(picked)        // invalid path never picks
        assertFalse(dismissed)    // ...and keeps the picker open
    }

    @Test fun project_picker_valid_path_picks_resolved_and_dismisses() = runComposeUiTest {
        var picked: String? = null
        var dismissed = false
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    validatePath = { PathValidation(ok = true, path = "/home/u/proj") },
                    onPick = { picked = it },
                    onDismiss = { dismissed = true },
                )
            }
        }
        onNodeWithTag("launcher_project_search").performTextInput("~/proj")
        waitForIdle()
        onNodeWithTag("launcher_use_path").performClick()
        waitForIdle()
        assertEquals("/home/u/proj", picked) // the RESOLVED path, not the typed one
        assertTrue(dismissed)
    }

    @Test fun project_picker_use_this_path_appears_for_free_query() = runComposeUiTest {
        // showTypedPath: query non-empty and not an exact known project path.
        pickerContent {
            Box {
                Picker(
                    current = "/home/u/alpha",
                    projects = listOf("/home/u/alpha", "/home/u/beta"),
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag("launcher_use_path").assertDoesNotExist()
        // A bare word is a search, not a path.
        onNodeWithTag("launcher_project_search").performTextInput("misc")
        waitForIdle()
        onNodeWithTag("launcher_use_path").assertDoesNotExist()
        onNodeWithTag("launcher_project_search").performTextClearance()
        onNodeWithTag("launcher_project_search").performTextInput("~/misc")
        waitForIdle()
        onNodeWithTag("launcher_use_path").assertIsDisplayed()
        onNodeWithText("Use this path").assertIsDisplayed()
        // Exact project path match → no free-path row.
        onNodeWithTag("launcher_project_search").performTextClearance()
        onNodeWithTag("launcher_project_search").performTextInput("/home/u/alpha")
        waitForIdle()
        onNodeWithTag("launcher_use_path").assertDoesNotExist()
        onNodeWithTag("project_row_/home/u/alpha").assertIsDisplayed()
    }

    // ── Forge omnibox ───────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors the launcher's workdir field + picker wiring: onPick updates the displayed workdir
     * label (formatWorkdir), not a bare capture.
     */
    @Composable
    private fun WorkdirPickerHarness(
        loadForges: suspend () -> List<ForgeConnection> = { emptyList() },
        searchForge: suspend (String) -> ForgeSearchResponse? = { ForgeSearchResponse() },
        cloneForge: suspend (String, String, String) -> String? = { _, _, _ -> null },
        createLocalRepo: suspend (String) -> String? = { null },
        createForge: suspend (String, String) -> String? = { _, _ -> null },
        projects: List<String> = emptyList(),
    ) {
        val home = "/home/u"
        var workdir by remember { mutableStateOf("~") }
        var menu by remember { mutableStateOf(true) }
        Column {
            Text(
                formatWorkdir(workdir, home),
                modifier = Modifier.testTag("launcher_workdir_label"),
            )
            Box {
                Picker(
                    expanded = menu,
                    current = workdir,
                    projects = projects,
                    home = home,
                    loadForges = loadForges,
                    searchForge = searchForge,
                    cloneForge = cloneForge,
                    createLocalRepo = createLocalRepo,
                    createForge = createForge,
                    onPick = { workdir = it },
                    onDismiss = { menu = false },
                )
            }
        }
    }

    @Test fun project_picker_create_local_lands_path_in_launcher_workdir() = runComposeUiTest {
        pickerContent {
            WorkdirPickerHarness(createLocalRepo = { name -> "/home/u/$name" })
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("brand-new")
        waitForIdle()
        waitFor { onNodeWithTag("forge_create_local").assertIsDisplayed() }
        onNodeWithTag("forge_create_local").performClick()
        waitForIdle()
        waitFor { onNodeWithTag("launcher_workdir_label").assertTextEquals("~/brand-new") }
    }

    @Test fun project_picker_clone_success_lands_path_in_launcher_workdir() = runComposeUiTest {
        val cloned = AtomicBoolean(false)
        pickerContent {
            WorkdirPickerHarness(
                loadForges = { listOf(forgeConn()) },
                searchForge = { searchOk(remote()) },
                cloneForge = { cid, owner, name ->
                    cloned.set(true)
                    assertEquals("c1", cid)
                    assertEquals("alice", owner)
                    assertEquals("widget", name)
                    "/home/u/widget"
                },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("widget")
        waitForIdle()
        waitFor { onNodeWithTag("forge_clone_alice/widget").assertIsDisplayed() }
        onNodeWithTag("forge_clone_alice/widget").performClick()
        waitForIdle()
        waitFor { onNodeWithTag("launcher_workdir_label").assertTextEquals("~/widget") }
        assertTrue(cloned.get())
    }

    @Test fun project_picker_clone_failure_surfaces_error_keeps_query_and_progress_label() = runComposeUiTest {
        var dismissed = false
        val gate = CompletableDeferred<Unit>()
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    loadForges = { listOf(forgeConn()) },
                    searchForge = { searchOk(remote(name = "failme")) },
                    cloneForge = { _, _, _ ->
                        gate.await()
                        null
                    },
                    onPick = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("failme")
        waitForIdle()
        waitFor { onNodeWithTag("forge_clone_alice/failme").assertIsDisplayed() }
        onNodeWithTag("forge_clone_alice/failme").performClick()
        waitForIdle()
        // Progress overlay + specific wording while clone is in flight.
        waitFor { onNodeWithTag("launcher_forge_resolving").assertIsDisplayed() }
        onNodeWithTag("launcher_forge_resolving_label").assertIsDisplayed()
        onNodeWithText("Cloning alice/failme…").assertIsDisplayed()
        // Honest Hide (not Cancel) — host clone is not abortable.
        onNodeWithTag("launcher_forge_hide").assertIsDisplayed()
        onNodeWithText("Hide").assertIsDisplayed()
        onNodeWithTag("launcher_forge_hide_hint").assertIsDisplayed()
        // Query retained during resolve.
        onNodeWithTag("launcher_project_search").assertIsDisplayed()

        gate.complete(Unit)
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_error").assertIsDisplayed() }
        assertFalse(dismissed)
        onNodeWithTag("launcher_project_menu").assertIsDisplayed()
        // Search query retained after failure (tag is unique; text nodes also contain "failme").
        onNodeWithTag("launcher_project_search").assertTextEquals("failme")
    }

    /**
     * Models real broker behaviour: clone is NOT cooperatively cancellable (host runs
     * `git clone` via execFileSync). Hide only drops the UI overlay; the fake must keep
     * running and surface a discoverable ready path — not pretend Cancel aborted the work.
     */
    @Test fun project_picker_hide_clone_keeps_host_op_and_surfaces_ready_path() = runComposeUiTest {
        val finished = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val picked = AtomicReference<String?>(null)
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    loadForges = { listOf(forgeConn()) },
                    searchForge = { searchOk(remote(name = "slow")) },
                    cloneForge = { _, _, _ ->
                        started.complete(Unit)
                        // Suspends until the test releases — production does NOT cancel this
                        // job on Hide, so finished becomes true even after the overlay is gone.
                        hold.await()
                        finished.set(true)
                        "/home/u/slow"
                    },
                    onPick = { picked.set(it) },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("slow")
        waitForIdle()
        waitFor { onNodeWithTag("forge_clone_alice/slow").assertIsDisplayed() }
        onNodeWithTag("forge_clone_alice/slow").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { started.isCompleted }
        onNodeWithTag("launcher_forge_resolving").assertIsDisplayed()
        onNodeWithTag("launcher_forge_hide").performClick()
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_resolving").assertDoesNotExist() }
        // Hide returns to the picker with the query; host op still in flight.
        onNodeWithTag("launcher_project_menu").assertIsDisplayed()
        onNodeWithTag("launcher_project_search").assertTextEquals("slow")
        onNodeWithTag("launcher_forge_host_continues").assertIsDisplayed()
        onNodeWithText("Clone alice/slow continues on the host…").assertIsDisplayed()
        assertFalse(finished.get())
        assertNull(picked.get())

        // Host finishes after Hide — path must be discoverable, not silent.
        hold.complete(Unit)
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_ready").assertIsDisplayed() }
        assertTrue(finished.get())
        onNodeWithTag("launcher_forge_host_continues").assertDoesNotExist()
        onNodeWithText("Ready — ~/slow").assertIsDisplayed()
        onNodeWithTag("launcher_forge_use_ready").performClick()
        waitForIdle()
        assertEquals("/home/u/slow", picked.get())
    }

    @Test fun project_picker_search_5xx_shows_error_not_empty() = runComposeUiTest {
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    loadForges = { listOf(forgeConn()) },
                    searchForge = { null }, // transport/5xx
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("widget")
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_search_error").assertIsDisplayed() }
        onNodeWithText("Couldn't search repositories — check the connection and try again.")
            .assertIsDisplayed()
        // Must NOT look like a successful empty search.
        onNodeWithTag("launcher_forge_empty").assertDoesNotExist()
    }

    @Test fun project_picker_empty_search_shows_no_repos_message() = runComposeUiTest {
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    loadForges = { listOf(forgeConn()) },
                    searchForge = { searchOk() },
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("zzzz")
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_empty").assertIsDisplayed() }
        onNodeWithText("No repos match \"zzzz\".").assertIsDisplayed()
        onNodeWithTag("launcher_forge_search_error").assertDoesNotExist()
    }

    @Test fun project_picker_slow_search_shows_searching_indicator() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    loadForges = { listOf(forgeConn()) },
                    searchForge = {
                        gate.await()
                        searchOk(remote())
                    },
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("widget")
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_searching").assertIsDisplayed() }
        onNodeWithText("Searching repos…").assertIsDisplayed()
        gate.complete(Unit)
        waitForIdle()
        waitFor { onNodeWithTag("forge_clone_alice/widget").assertIsDisplayed() }
    }

    @Test fun project_picker_paging_load_more_reveals_remaining_repos() = runComposeUiTest {
        val many = (1..FORGE_OMNIBOX_PAGE_SIZE + 3).map { i ->
            remote(name = "repo$i")
        }
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    loadForges = { listOf(forgeConn()) },
                    searchForge = { searchOk(*many.toTypedArray()) },
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("repo")
        waitForIdle()
        waitFor { onNodeWithTag("forge_clone_alice/repo1").assertIsDisplayed() }
        // Page 1 only — last items hidden until Load more.
        onNodeWithTag("forge_clone_alice/repo${FORGE_OMNIBOX_PAGE_SIZE + 1}").assertDoesNotExist()
        onNodeWithTag("launcher_forge_load_more").performScrollTo().assertIsDisplayed()
        onNodeWithTag("launcher_forge_load_more").performClick()
        waitForIdle()
        onNodeWithTag("forge_clone_alice/repo${FORGE_OMNIBOX_PAGE_SIZE + 1}").performScrollTo().assertIsDisplayed()
    }

    @Test fun project_picker_omnibox_key_actions_escape_enter_arrows() {
        // Pure decision table for the omnibox key handler — UI injection of arrow keys into a
        // focused OutlinedTextField is unreliable under skiko; the handler itself is unit-tested.
        assertEquals(
            OmniboxKeyAction.Dismiss,
            omniboxKeyAction(Key.Escape, highlight = 0, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.HideResolve,
            omniboxKeyAction(Key.Escape, highlight = 0, count = 2, resolving = true),
        )
        assertEquals(
            OmniboxKeyAction.Activate(0),
            omniboxKeyAction(Key.Enter, highlight = 0, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.Activate(1),
            omniboxKeyAction(Key.Enter, highlight = 1, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.MoveHighlight(1),
            omniboxKeyAction(Key.DirectionDown, highlight = 0, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.MoveHighlight(0),
            omniboxKeyAction(Key.DirectionUp, highlight = 1, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.MoveHighlight(0),
            omniboxKeyAction(Key.DirectionDown, highlight = 1, count = 2, resolving = false),
        ) // wraps
        assertNull(omniboxKeyAction(Key.A, highlight = 0, count = 2, resolving = false))
        assertNull(omniboxKeyAction(Key.Enter, highlight = 0, count = 0, resolving = false))
    }

    @Test fun project_picker_search_is_entry_point_when_opened() = runComposeUiTest {
        // Plain host (no DropdownMenu) so headless skiko reports IsFocused after requestFocus.
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    projects = listOf("/home/u/alpha"),
                    onPick = {},
                    onDismiss = {},
                    useDropdownMenu = false,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").assertIsDisplayed()
        onNodeWithTag("launcher_project_search").assertIsEnabled()
        onNodeWithTag("launcher_omnibox_root").assertIsDisplayed()
        waitFor { onNodeWithTag("launcher_project_search").assertIsFocused() }
        onNodeWithTag("launcher_project_autofocus_ready").assertIsDisplayed()
    }

    /**
     * Regression: autofocus used to await loadForges(), so a slow/stalled broker delayed the
     * keyboard. Hold forge load forever and require the search field to be *actually focused*
     * (IsFocused + ready tag driven by onFocusChanged) before forges resolve — a version that
     * only flips a side-effect flag, or still sequences requestFocus after loadForges, fails.
     *
     * Hosted without [DropdownMenu] so focus semantics are observable under headless skiko.
     */
    @Test fun project_picker_autofocuses_without_waiting_for_forges() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    projects = listOf("/home/u/alpha"),
                    loadForges = {
                        gate.await()
                        listOf(forgeConn())
                    },
                    onPick = {},
                    onDismiss = {},
                    useDropdownMenu = false,
                )
            }
        }
        waitForIdle()
        // Real focus while loadForges is still suspended — not a pre-set marker.
        waitFor {
            onNodeWithTag("launcher_project_search").assertIsFocused()
            onNodeWithTag("launcher_project_autofocus_ready").assertIsDisplayed()
        }
        assertFalse(gate.isCompleted, "autofocus must not wait for loadForges")
        onNodeWithTag("launcher_project_search").assertIsFocused()
        onNodeWithTag("launcher_project_search").assertIsEnabled()
        onNodeWithTag("launcher_project_search").performTextInput("typed-before-forges")
        onNodeWithTag("launcher_project_search").assertTextEquals("typed-before-forges")
        // Still unresolved after focus + typing — proves we never awaited the forge load.
        assertFalse(gate.isCompleted, "forge load must remain pending after focused typing")
        gate.complete(Unit)
        waitForIdle()
    }

    @Test fun project_picker_create_on_forge_uses_connection_id() = runComposeUiTest {
        val picked = AtomicReference<String?>(null)
        val target = AtomicReference<String?>(null)
        pickerContent {
            Box {
                Picker(
                    current = "~",
                    loadForges = { listOf(forgeConn(id = "conn-9", login = "bob")) },
                    searchForge = { searchOk() },
                    createForge = { cid, name ->
                        target.set(cid)
                        "/home/u/$name"
                    },
                    onPick = { picked.set(it) },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("solo-proj")
        waitForIdle()
        waitFor { onNodeWithTag("forge_create_conn-9").assertIsDisplayed() }
        onNodeWithTag("forge_create_conn-9").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { picked.get() != null }
        assertEquals("conn-9", target.get())
        assertEquals("/home/u/solo-proj", picked.get())
    }

    // ── Compact: the container Android's ProjectPickerSheet contributed ──────────────────────────

    /**
     * The phone shape end to end: a closed picker shows nothing, opening it raises the sheet
     * (titled, as Android's was), a project row picks and dismisses — and a REAL swipe-down on the
     * sheet dismisses it through the launcher's own callback, not by silently unmounting.
     */
    @Test fun compact_sheet_opens_selects_and_dismisses() = runComposeUiTest {
        var open by mutableStateOf(false)
        var picked: String? = null
        var dismissals = 0
        pickerContent(WindowWidthClass.Compact) {
            Picker(
                expanded = open,
                current = "/home/u/alpha",
                projects = listOf("/home/u/alpha", "/home/u/beta"),
                onPick = { picked = it },
                onDismiss = { open = false; dismissals++ },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_menu").assertDoesNotExist()

        open = true
        waitForIdle()
        waitFor { onNodeWithTag("launcher_omnibox_root").assertIsDisplayed() }
        onNodeWithTag("project_picker_title").assertIsDisplayed()
        onNodeWithTag("project_row_/home/u/beta").performClick()
        waitForIdle()
        assertEquals("/home/u/beta", picked)
        assertEquals(1, dismissals)
        waitFor { onNodeWithTag("launcher_omnibox_root").assertDoesNotExist() }

        // Reopen and dismiss with the gesture a phone actually uses.
        open = true
        waitForIdle()
        waitFor { onNodeWithTag("launcher_omnibox_root").assertIsDisplayed() }
        onNodeWithTag("launcher_project_menu").performTouchInput { swipeDown() }
        waitForIdle()
        waitFor { onNodeWithTag("launcher_omnibox_root").assertDoesNotExist() }
        assertEquals(2, dismissals)
        assertFalse(open)
    }

    /**
     * The M3 sheet dismiss trap (cluster E3): Material hides the sheet BEFORE calling
     * `onDismissRequest`, so a guard that merely returns leaves the picker composed-but-invisible
     * while the launcher still believes it is open — the project heading would go dead. A swipe
     * during a clone must drop the progress overlay (the host op is not abortable) and leave the
     * FORM reachable.
     */
    @Test fun compact_dismiss_while_submitting_keeps_the_form_reachable() = runComposeUiTest {
        val hold = CompletableDeferred<Unit>()
        var open by mutableStateOf(true)
        var dismissed = false
        pickerContent(WindowWidthClass.Compact) {
            Picker(
                expanded = open,
                current = "~",
                loadForges = { listOf(forgeConn()) },
                searchForge = { searchOk(remote(name = "slow")) },
                cloneForge = { _, _, _ ->
                    hold.await()
                    "/home/u/slow"
                },
                onPick = {},
                onDismiss = { open = false; dismissed = true },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("slow")
        waitForIdle()
        waitFor { onNodeWithTag("forge_clone_alice/slow").assertIsDisplayed() }
        onNodeWithTag("forge_clone_alice/slow").performClick()
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_resolving").assertIsDisplayed() }

        onNodeWithTag("launcher_project_menu").performTouchInput { swipeDown() }
        waitForIdle()
        // The sheet came back up: the search field is still there and still carries the query.
        waitFor { onNodeWithTag("launcher_project_search").assertIsDisplayed() }
        onNodeWithTag("launcher_project_search").assertTextEquals("slow")
        onNodeWithTag("launcher_forge_host_continues").assertIsDisplayed()
        assertFalse(dismissed, "a dismiss mid-clone must not close the picker")
        assertTrue(open)

        hold.complete(Unit)
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_ready").assertIsDisplayed() }
    }

    /** The omnibox itself under Compact: filtering, the typed-path row, and forge clone rows. */
    @Test fun compact_omnibox_filters_projects_and_offers_typed_path_and_repos() = runComposeUiTest {
        pickerContent(WindowWidthClass.Compact) {
            Picker(
                current = "/home/u/alpha",
                projects = listOf("/home/u/alpha", "/home/u/beta"),
                loadForges = { listOf(forgeConn()) },
                searchForge = { searchOk(remote(name = "widget")) },
                onPick = {},
                onDismiss = {},
            )
        }
        waitForIdle()
        onNodeWithTag("project_row_/home/u/alpha").assertIsDisplayed()
        onNodeWithTag("project_row_/home/u/beta").assertIsDisplayed()
        onNodeWithTag("launcher_use_path").assertDoesNotExist()

        onNodeWithTag("launcher_project_search").performTextInput("widget")
        waitForIdle()
        // A bare word is not offered as a path; the forge search lands and the locals drop out.
        onNodeWithTag("launcher_use_path").assertDoesNotExist()
        onNodeWithTag("project_row_/home/u/alpha").assertDoesNotExist()
        waitFor { onNodeWithTag("forge_clone_alice/widget").assertIsDisplayed() }
        onNodeWithTag("forge_group_c1").assertIsDisplayed()
    }

    /**
     * The container follows the INPUT DEVICE, not the width. An unfolded foldable or a tablet held
     * in the hand is Expanded and still has no pointer: it must get the sheet (Android's shape),
     * not a dropdown anchored to a heading it cannot comfortably drive.
     */
    @Test fun a_touch_host_gets_the_sheet_at_every_width() = runComposeUiTest {
        var open by mutableStateOf(true)
        var dismissals = 0
        pickerContent(WindowWidthClass.Expanded, pointer = false) {
            Picker(
                expanded = open,
                current = "/home/u/alpha",
                projects = listOf("/home/u/alpha"),
                onPick = {},
                onDismiss = { open = false; dismissals++ },
            )
        }
        waitForIdle()
        waitFor { onNodeWithTag("launcher_omnibox_root").assertIsDisplayed() }
        // The sheet-only heading Android's ProjectPickerSheet carried.
        onNodeWithTag("project_picker_title").assertIsDisplayed()
        // ...and the sheet's own gesture closes it, which a dropdown has no notion of.
        onNodeWithTag("launcher_project_menu").performTouchInput { swipeDown() }
        waitForIdle()
        waitFor { onNodeWithTag("launcher_omnibox_root").assertDoesNotExist() }
        assertEquals(1, dismissals)
    }

    /**
     * The mirror: a pointer at a Compact width — a narrow desktop window, or a docked/DeX Android
     * device — keeps the anchored dropdown and desktop's body. Before the container branched on
     * pointer availability this window silently became a bottom sheet.
     */
    @Test fun a_pointer_host_keeps_the_anchored_menu_when_narrow() = runComposeUiTest {
        var picked: String? = null
        pickerContent(WindowWidthClass.Compact, pointer = true) {
            Box {
                Picker(
                    current = "/home/u/alpha",
                    projects = listOf("/home/u/alpha", "/home/u/beta"),
                    onPick = { picked = it },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_omnibox_root").assertIsDisplayed()
        onNodeWithTag("project_picker_title").assertDoesNotExist()
        onNodeWithTag("project_row_/home/u/beta").performClick()
        waitForIdle()
        assertEquals("/home/u/beta", picked)
    }

    /**
     * The picker is composed whether or not it is open, so a clone that finishes AFTER the user
     * closed it must not land: it would rewrite the launcher's workdir out from under them.
     */
    @Test fun a_clone_that_lands_after_a_dismiss_is_dropped() = runComposeUiTest {
        val hold = CompletableDeferred<Unit>()
        val finished = AtomicBoolean(false)
        var open by mutableStateOf(true)
        val picked = AtomicReference<String?>(null)
        pickerContent {
            Box {
                Picker(
                    expanded = open,
                    current = "~",
                    loadForges = { listOf(forgeConn()) },
                    searchForge = { searchOk(remote(name = "slow")) },
                    cloneForge = { _, _, _ ->
                        hold.await()
                        finished.set(true)
                        "/home/u/slow"
                    },
                    onPick = { picked.set(it) },
                    onDismiss = { open = false },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("slow")
        waitForIdle()
        waitFor { onNodeWithTag("forge_clone_alice/slow").assertIsDisplayed() }
        onNodeWithTag("forge_clone_alice/slow").performClick()
        waitForIdle()
        waitFor { onNodeWithTag("launcher_forge_resolving").assertIsDisplayed() }

        open = false
        waitForIdle()
        // The host keeps working (it is not abortable) and completes...
        hold.complete(Unit)
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { finished.get() }
        waitForIdle()
        // ...but nothing lands in the launcher.
        assertNull(picked.get(), "a clone finishing after a dismiss must not rewrite the workdir")
    }
}
