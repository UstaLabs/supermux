// A configuration change must not lose where the user was (card #89, phase 2 batch C): the session
// list's scroll, the chat's media viewer, the Files tree, the Changes pane's base, menu and folders,
// whose walkthrough it shows, a close question with its worktree tick, the phone's tab grid and its
// add sheet, a terminal strip's tab, the Displays viewer and a display's keyboard, and (where the
// host keeps no attachments of its own: iOS, desktop) staged chips with their uploads.
//
// The same two events as the other ConfigurationChange suites, on the real shell where it reaches
// the spot: [Recreation] for an Android recreation (rotate, fold, dark mode, font scale), and a
// Compact→Expanded flip in one composition for the swap (a phone turned, a Fold opened; all iOS
// gets). Two spots run one level below the shell, inside the fleet's retained state provided by
// hand and rebuilt in a Row instead of a Column for the swap: a terminal's tab strip (a terminal
// needs its engine, so its panes are stand-ins) and a display's keyboard (a display needs a live RFB
// socket, so its clients are stopped before they connect). The phone's tab grid and add sheet exist
// only in the Compact layout: they have a recreation test and no swap test.
package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.net.DisplayStream
import dev.supermux.net.FsEntry
import dev.supermux.net.VncClient
import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.RetainedUiState
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.PlatformTestProviders
import dev.supermux.ui.chat.TINY_PNG_BYTES
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.display.DisplayActions
import dev.supermux.ui.display.DisplayPanel
import dev.supermux.ui.files.TreeViewEdits
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.retain.LocalRetainedUiState
import dev.supermux.ui.session.LocalContextMenuAvailable
import dev.supermux.ui.session.WorkspaceListTestIds
import dev.supermux.ui.terminal.TerminalKeySink
import dev.supermux.ui.terminal.TerminalSurface
import dev.supermux.ui.terminal.TerminalTabs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.singleViewLayout
import dev.supermux.workspace.toDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Holds the first request it sees until [release] completes; later ones pass straight through. */
private class HeldRequest {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val first = AtomicBoolean(true)

    suspend fun hold() {
        if (first.getAndSet(false)) {
            started.complete(Unit)
            release.await()
        }
    }
}

/**
 * A broker for the mock engine: answers each "METHOD /path" with [answers] ("{}" when it has
 * none) or a path with [bytes], holds a request at its [gates], and records every request it saw.
 */
private class MockBroker(
    private val answers: Map<String, String> = emptyMap(),
    private val gates: Map<String, HeldRequest> = emptyMap(),
    private val bytes: Map<String, ByteArray> = emptyMap(),
) {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val handler: MockRequestHandler = { request ->
        val key = "${request.method.value} ${request.url.encodedPath}"
        requests += key
        gates[key]?.hold()
        val raw = bytes[request.url.encodedPath]
        if (raw != null) {
            respond(raw, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        } else {
            respond(
                answers[key] ?: "{}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    }

    fun count(key: String): Int = synchronized(requests) { requests.count { it == key } }
}

/** Android's phone around the shell: no platform context menu, so a row's actions are in its ⋮. */
private fun phone(): @Composable (@Composable () -> Unit) -> Unit = { content ->
    CompositionLocalProvider(LocalContextMenuAvailable provides false, content = content)
}

private fun chatView(id: String, workspaceId: String, sessionId: String) = ViewDto(
    id = id, workspaceId = workspaceId, kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

private fun editorView(id: String, workspaceId: String, mode: String) = ViewDto(
    id = id, workspaceId = workspaceId, kind = "editor",
    state = JsonObject(mapOf("mode" to JsonPrimitive(mode))),
)

@OptIn(ExperimentalTestApi::class)
class ConfigurationChangeViewTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The phone's shell over [fleet] in a [Recreation], at [width]: MainActivity's saveable
     * [ShellUiState], started by [initial]. The fleet and the platform are built by the caller,
     * outside the content, as the view model holds them through a recreation.
     */
    private fun ComposeUiTest.phoneShell(
        fleet: FleetStore,
        platform: FakePlatform = FakePlatform(),
        width: WindowWidthClass = WindowWidthClass.Compact,
        initial: ShellUiState.() -> Unit = {},
    ): Recreation = Recreation(this).also {
        it.setShell(fleet, platform, UiPrefs(FakeSettingsStore()), widthClass = width, wrap = phone(), initial = initial)
    }

    /** The same phone's shell at a width the test flips to Expanded: the phone turned, the Fold opened. */
    private fun ComposeUiTest.flippablePhone(
        fleet: FleetStore,
        platform: FakePlatform = FakePlatform(),
        initial: ShellUiState.() -> Unit = {},
    ): MutableState<WindowWidthClass> =
        swappableShell(fleet, ShellUiState().apply(initial), platform = platform, wrap = phone())

    private fun ComposeUiTest.unfold(width: MutableState<WindowWidthClass>) {
        width.value = WindowWidthClass.Expanded
        waitForIdle()
    }

    /** The mock broker answers on its own thread, which idling does not track: wait for what it brings. */
    private fun ComposeUiTest.await(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** The fleet's retained state, as `SupermuxApp` provides it; built outside the content. */
    private fun retainedUi() = RetainedUiState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private fun chatWorkspace(id: String, name: String, sessionId: String) = WorkspaceDto(
        id = id, name = name, workdir = "/w",
        primarySessionId = sessionId,
        layout = singleViewLayout("$id-g", "$id-chat").toDto(),
        views = listOf(chatView("$id-chat", id, sessionId)),
    )

    // ── The session list's scroll ────────────────────────────────────────────────────────────────

    /** Thirty workspaces in one group, in id order w01…w30: more than a screen holds. */
    private fun manyWorkspaces(): HostStore = testHostStore().also { app ->
        val ids = (1..30).map { "%02d".format(it) }
        app.reduce(
            ServerFrame.Snapshot(
                sessions = ids.map { SessionInfo(id = "s$it", name = "worker-$it", workdir = "/w", agent = "claude") },
                workspaces = ids.map { chatWorkspace("w$it", "workspace $it", "s$it") },
            ),
        )
    }

    private fun ComposeUiTest.assertTheListIsAtTheEnd() {
        onNodeWithTag(WorkspaceListTestIds.row("w30")).assertIsDisplayed()
        // Back at the top, the first rows would be composed again.
        onNodeWithTag(WorkspaceListTestIds.row("w01")).assertDoesNotExist()
    }

    private fun ComposeUiTest.scrollTheListToTheEnd() {
        await(WorkspaceListTestIds.row("w01"))
        onNodeWithTag(WorkspaceListTestIds.LIST).performScrollToNode(hasTestTag(WorkspaceListTestIds.row("w30")))
        waitForIdle()
        assertTheListIsAtTheEnd()
    }

    @Test fun the_session_list_keeps_its_place_through_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testFleet(manyWorkspaces()))
        scrollTheListToTheEnd()

        activity.recreate()

        assertTheListIsAtTheEnd()
    }

    @Test fun the_session_list_keeps_its_place_through_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(testFleet(manyWorkspaces()))
        scrollTheListToTheEnd()

        unfold(width)

        // The wide sidebar's list, a different list from the phone's, at the same place.
        assertTheListIsAtTheEnd()
    }

    // ── The chat's media viewer and the item on screen ───────────────────────────────────────────

    private fun photo(id: String) = Attachment(file_id = id, kind = "photo", mime = "image/png", name = "$id.png")

    /** The one chat, with a photo in each of two messages; the host serves their bytes. */
    private fun chatWithPhotos(): HostStore {
        val broker = MockBroker(bytes = mapOf("/files/a" to TINY_PNG_BYTES, "/files/b" to TINY_PNG_BYTES))
        return testHostStore(broker.handler).also { app ->
            app.reduce(
                ServerFrame.Snapshot(
                    sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
                    workspaces = listOf(chatWorkspace("w1", "proj", "s1")),
                    logs = mapOf(
                        "s1" to listOf(
                            LogEntry(id = "in:1", ts = "2026-11-07T10:00:01Z", direction = "inbound", text = "one", attachments = listOf(photo("a"))),
                            LogEntry(id = "out:1", ts = "2026-11-07T10:00:02Z", direction = "outbound", text = "two", attachments = listOf(photo("b"))),
                        ),
                    ),
                ),
            )
        }
    }

    private fun ComposeUiTest.counter(): String =
        onNodeWithTag("chat_media_viewer_counter", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    /** Opens the viewer on a photo, then steps to the other one; answers what its counter then reads. */
    private fun ComposeUiTest.openAPhotoAndStepToTheOther(): String {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("attachment_image").fetchSemanticsNodes().size == 2 }
        onAllNodesWithTag("attachment_image")[0].performClick()
        await("chat_media_viewer")
        val opened = counter()
        onNodeWithTag("chat_media_viewer").performKeyInput {
            pressKey(if (opened == "1 / 2") Key.DirectionRight else Key.DirectionLeft)
        }
        waitForIdle()
        return counter().also { assertNotEquals(opened, it) }
    }

    @Test fun the_media_viewer_and_its_item_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testFleet(chatWithPhotos())) { selectedId = "s1" }
        val onScreen = openAPhotoAndStepToTheOther()

        activity.recreate()

        // Still open, and on the item the user stepped to rather than the one it was opened on.
        await("chat_media_viewer")
        assertEquals(onScreen, counter())
    }

    @Test fun the_media_viewer_and_its_item_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(testFleet(chatWithPhotos())) { selectedId = "s1" }
        val onScreen = openAPhotoAndStepToTheOther()

        unfold(width)

        await("chat_media_viewer")
        assertEquals(onScreen, counter())
    }

    // ── The Files tree: open folders, selection, scroll ──────────────────────────────────────────

    /** One workspace showing its Files pane (v2, the tree at /w); its chat (v1) is s1's. */
    private fun filesWorkspace(): HostStore = testHostStore { req ->
        if (req.url.encodedPath == "/fs/list") respond("""{"path":"/w","version":"1"}""", HttpStatusCode.OK)
        else respond("{}", HttpStatusCode.OK)
    }.also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
                workspaces = listOf(
                    WorkspaceDto(
                        id = "w1", name = "proj", workdir = "/w",
                        primarySessionId = "s1",
                        layout = singleViewLayout("g1", "v2").toDto(),
                        activeViewId = "v2",
                        views = listOf(chatView("v1", "w1", "s1"), editorView("v2", "w1", "tree")),
                    ),
                ),
            ),
        )
    }

    /** What the fleet keeps for the Files pane v2 (selection included, which the tree only paints). */
    private fun treeKept(fleet: FleetStore): TreeViewEdits = fleet.retainedUi.getOrPut("files:w1:v2:/w") { TreeViewEdits() }

    /**
     * The tree's folder `src` opened (a click selects it too), then the list scrolled to its last
     * file. The host's listings arrive as `fs_dir` frames, once the pane has subscribed.
     */
    private fun ComposeUiTest.openSrcAndScrollToTheEnd(app: HostStore) {
        await("tree_menu")
        runOnIdle {
            val files = (1..40).map { FsEntry(name = "f%02d.txt".format(it), type = "file") }
            app.reduce(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "src", type = "dir")) + files))
        }
        await("tree_row:src")
        onNodeWithTag("tree_row:src").performClick()
        waitForIdle()
        runOnIdle { app.reduce(ServerFrame.FsDir(path = "/w/src", version = "1", entries = listOf(FsEntry(name = "main.kt", type = "file")))) }
        await("tree_row:main.kt")
        onNodeWithTag("editor_tree").performScrollToNode(hasTestTag("tree_row:f40.txt"))
        waitForIdle()
        onNodeWithTag("tree_row:src").assertDoesNotExist()
    }

    private fun ComposeUiTest.assertTheTreeIsWhereItWas(fleet: FleetStore) {
        await("tree_row:f40.txt")
        onNodeWithTag("tree_row:f40.txt").assertIsDisplayed()
        // Not back at the top, where `src` is…
        onNodeWithTag("tree_row:src").assertDoesNotExist()
        // …which is still open, and still the selection.
        onNodeWithTag("editor_tree").performScrollToNode(hasTestTag("tree_row:main.kt"))
        onNodeWithTag("tree_row:main.kt").assertIsDisplayed()
        assertEquals("/w/src", treeKept(fleet).selected)
    }

    @Test fun the_files_tree_keeps_its_folders_selection_and_scroll_through_a_recreation() = runComposeUiTest {
        val app = filesWorkspace()
        val fleet = testFleet(app)
        val activity = phoneShell(fleet) { selectedId = "s1" }
        openSrcAndScrollToTheEnd(app)

        activity.recreate()

        assertTheTreeIsWhereItWas(fleet)
    }

    @Test fun the_files_tree_keeps_its_folders_selection_and_scroll_through_the_compact_to_wide_swap() = runComposeUiTest {
        val app = filesWorkspace()
        val fleet = testFleet(app)
        val width = flippablePhone(fleet) { selectedId = "s1" }
        openSrcAndScrollToTheEnd(app)

        unfold(width)

        assertTheTreeIsWhereItWas(fleet)
    }

    // ── The Changes pane: the base picked, its open menu, a closed folder ────────────────────────

    /** One workspace showing its Changes pane (v2): one changed file, src/a.txt. */
    private fun changesWorkspace(): HostStore = testHostStore(
        MockBroker(
            mapOf(
                "GET /workspaces/w1/changes" to
                    """{"repos":[{"repo":"","files":[{"path":"src/a.txt","status":"modified","added":1,"removed":1}]}]}""",
            ),
        ).handler,
    ).also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
                workspaces = listOf(
                    WorkspaceDto(
                        id = "w1", name = "proj", workdir = "/w",
                        primarySessionId = "s1",
                        layout = singleViewLayout("g1", "v2").toDto(),
                        activeViewId = "v2",
                        views = listOf(chatView("v1", "w1", "s1"), editorView("v2", "w1", "diff")),
                    ),
                ),
            ),
        )
    }

    private val chipOnUncommitted = hasTestTag("diff_base_chip") and hasText("Uncommitted", substring = true)

    /** Closes the folder `src`, picks "Uncommitted" as the base, then opens the base menu again. */
    private fun ComposeUiTest.pickABaseCloseAFolderAndOpenTheMenu() {
        await("diff_file_0")
        onNodeWithTag("diff_folder_src").performClick()
        waitForIdle()
        onNodeWithTag("diff_file_0").assertDoesNotExist()
        onNodeWithTag("diff_base_chip").performClick()
        await("diff_base_option_head")
        onNodeWithTag("diff_base_option_head").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(chipOnUncommitted).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("diff_base_chip").performClick()
        await("diff_base_menu")
    }

    private fun ComposeUiTest.assertTheChangesPaneIsAsItWas() {
        await("diff_base_menu")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(chipOnUncommitted).fetchSemanticsNodes().isNotEmpty() }
        // The reloaded list is there, with `src` still closed over its file.
        await("diff_folder_src")
        onNodeWithTag("diff_file_0").assertDoesNotExist()
    }

    @Test fun the_changes_panes_base_menu_and_folders_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testFleet(changesWorkspace())) { selectedId = "s1" }
        pickABaseCloseAFolderAndOpenTheMenu()

        activity.recreate()

        assertTheChangesPaneIsAsItWas()
    }

    @Test fun the_changes_panes_base_menu_and_folders_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(testFleet(changesWorkspace())) { selectedId = "s1" }
        pickABaseCloseAFolderAndOpenTheMenu()

        unfold(width)

        // The phone's sheet is a menu now, still open.
        assertTheChangesPaneIsAsItWas()
    }

    // ── Whose walkthrough the Changes pane shows ─────────────────────────────────────────────────

    /** A machine with the walkthrough seam installed, which both real hosts ship. */
    private fun walkthroughPlatform() = FakePlatform(caps = NO_CAPS.copy(walkthrough = true))

    /**
     * One workspace with two chats, s1's (v1, the primary) and s2's (v3, on screen), and the Changes
     * pane (v2) in a second group. s2 has a walkthrough, and its chat says so; s1 has none.
     */
    private fun walkthroughWorkspace(): HostStore = testHostStore(
        MockBroker(
            mapOf(
                "GET /sessions/s2/walkthrough" to
                    """{"walkthrough":{"id":"wt1","title":"The login flow","steps":[{"id":"st1","title":"The form"}]}}""",
            ),
        ).handler,
    ).also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(
                    SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude"),
                    SessionInfo(id = "s2", name = "worker-2", workdir = "/w", agent = "claude"),
                ),
                workspaces = listOf(
                    WorkspaceDto(
                        id = "w1", name = "proj", workdir = "/w",
                        primarySessionId = "s1",
                        layout = LayoutNode.Split(
                            direction = "row",
                            sizes = listOf(0.5, 0.5),
                            children = listOf(
                                LayoutNode.Group("g1", listOf("v1", "v3"), "v3"),
                                LayoutNode.Group("g2", listOf("v2"), "v2"),
                            ),
                        ).toDto(),
                        activeViewId = "v3",
                        views = listOf(chatView("v1", "w1", "s1"), editorView("v2", "w1", "diff"), chatView("v3", "w1", "s2")),
                    ),
                ),
                logs = mapOf(
                    "s2" to listOf(
                        LogEntry(id = "in:1", ts = "2026-11-07T10:00:01Z", direction = "inbound", text = "📖 Walkthrough ready: The login flow"),
                    ),
                ),
            ),
        )
    }

    private fun ComposeUiTest.openS2sWalkthrough() {
        await("walkthrough_ready_card")
        onNodeWithTag("walkthrough_ready_card").performClick()
        waitForIdle()
    }

    @Test fun the_walkthrough_the_changes_pane_shows_survives_a_recreation() = runComposeUiTest {
        // Wide from the start, so the chat and the Changes pane are on screen together.
        val activity = phoneShell(testFleet(walkthroughWorkspace()), walkthroughPlatform(), WindowWidthClass.Expanded) { selectedId = "s2" }
        openS2sWalkthrough()
        await("walkthrough_toggle")

        activity.recreate()

        // Still s2's: the primary chat's (s1's) has no walkthrough, so no toggle.
        await("walkthrough_toggle")
    }

    @Test fun the_walkthrough_the_changes_pane_shows_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(testFleet(walkthroughWorkspace()), walkthroughPlatform()) { selectedId = "s2" }
        openS2sWalkthrough()

        unfold(width)

        await("walkthrough_toggle")
    }

    // ── A close question and its worktree tick ───────────────────────────────────────────────────

    /** The one chat, whose workdir is a worktree the close can delete. */
    private fun chatInAWorktree(): HostStore = oneChatWorkspace(
        MockBroker(
            mapOf("GET /worktrees/by-workdir" to """{"id":"wt1","branch":"feature/login","owners":[],"changes":{"id":"wt1"},"bytes":1024}"""),
        ).handler,
    )

    /** The phone's tab grid, the chat card's ×, then "also delete worktree" ticked. */
    private fun ComposeUiTest.askToCloseTheChatAndTickTheWorktree() {
        await("phone_tabs_button")
        onNodeWithTag("phone_tabs_button").performClick()
        await("tab-close-v1")
        onNodeWithTag("tab-close-v1").performClick()
        await("wt_cleanup_checkbox")
        onNodeWithTag("wt_cleanup_checkbox").performClick()
        waitForIdle()
        awaitText("Close & delete")
    }

    private fun ComposeUiTest.assertTheQuestionIsStillTicked() {
        // The section reloads, then reports the kept tick again with the worktree it found.
        await("wt_cleanup_checkbox")
        onNodeWithTag("wt_cleanup_checkbox").assertIsOn()
        awaitText("Close & delete")
    }

    @Test fun a_close_question_and_its_worktree_tick_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testFleet(chatInAWorktree())) { selectedId = "s1" }
        askToCloseTheChatAndTickTheWorktree()

        activity.recreate()

        assertTheQuestionIsStillTicked()
    }

    @Test fun a_close_question_and_its_worktree_tick_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(testFleet(chatInAWorktree())) { selectedId = "s1" }
        askToCloseTheChatAndTickTheWorktree()

        unfold(width)

        // Asked again by the wide layout's own dialog.
        assertTheQuestionIsStillTicked()
    }

    // ── The phone's tab grid and its add sheet (Compact only: no swap test) ──────────────────────

    @Test fun the_phone_tab_grid_stays_open_through_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testFleet(oneChatWorkspace())) { selectedId = "s1" }
        await("phone_tabs_button")
        onNodeWithTag("phone_tabs_button").performClick()
        await("phone_tab_switcher")

        activity.recreate()

        await("phone_tab_switcher")
        onNodeWithTag("tab-card-v1").assertIsDisplayed()
    }

    @Test fun the_new_tab_page_stays_open_through_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testFleet(oneChatWorkspace())) { selectedId = "s1" }
        await("phone_tabs_button")
        onNodeWithTag("phone_tabs_button").performClick()
        await("phone_add_view")
        onNodeWithTag("phone_add_view").performClick()
        await("new-tab-choice-terminal")

        activity.recreate()

        await("new-tab-choice-terminal")
        onNodeWithTag("new-tab-choice-display").assertExists()
        // The phone offers no Chat here, as before (phoneAddKinds).
        onNodeWithTag("new-tab-choice-chat").assertDoesNotExist()
    }

    // ── One level down: a terminal strip's selected tab ──────────────────────────────────────────

    /** A stand-in terminal pane: records whether the strip shows it as the active one. */
    private class StandInTerminal(private val tabId: String, private val active: MutableMap<String, Boolean>) : TerminalSurface {
        override val keys = TerminalKeySink(onHideKeyboard = {}) {}

        @Composable
        override fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?) {
            this.active[tabId] = active
            Box(modifier.fillMaxSize().testTag("terminal-stand-in-$tabId"))
        }
    }

    /** A host whose session s1 has two terminals, t1 then t2. */
    private fun twoTerminals(): HostStore = testHostStore { req ->
        val body = if (req.url.encodedPath.contains("/api/term/list")) {
            """{"terminals":[{"id":"t1","createdAt":1},{"id":"t2","createdAt":2}]}"""
        } else {
            "{}"
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }

    @Composable
    private fun Strip(app: HostStore, active: MutableMap<String, Boolean>) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            TerminalTabs(app = app, sessionId = "s1", surfaceFor = { tabId, _ -> remember(tabId) { StandInTerminal(tabId, active) } })
        }
    }

    private fun ComposeUiTest.pickTheSecondTerminal(active: MutableMap<String, Boolean>) {
        await("term-tab-t2")
        onNodeWithTag("term-tab-t2").performClick()
        waitUntil(timeoutMillis = 10_000) { active["t2"] == true }
        active.clear()
    }

    private fun ComposeUiTest.assertTheSecondTerminalIsActive(active: MutableMap<String, Boolean>) {
        // After the rebuilt strip has re-listed its tabs: without the kept choice it lands on t1.
        waitUntil(timeoutMillis = 10_000) { active["t2"] == true }
        waitForIdle()
        assertTrue(active["t1"] != true)
    }

    @Test fun a_terminal_strips_tab_survives_a_recreation() = runComposeUiTest {
        val retained = retainedUi()
        val app = twoTerminals()
        val active = ConcurrentHashMap<String, Boolean>()
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = true, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Pointer, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { Strip(app, active) }
            }
        }
        pickTheSecondTerminal(active)

        activity.recreate()

        assertTheSecondTerminalIsActive(active)
    }

    @Test fun a_terminal_strips_tab_survives_a_move_to_the_wide_layout() = runComposeUiTest {
        val retained = retainedUi()
        val app = twoTerminals()
        val active = ConcurrentHashMap<String, Boolean>()
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = true, widthClass = width, inputMode = InputMode.Pointer, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    // Rebuilt somewhere else in the tree, as the shell's wide layout does.
                    if (wide) Row { Strip(app, active) } else Column { Strip(app, active) }
                }
            }
        }
        pickTheSecondTerminal(active)

        wide = true
        waitForIdle()

        assertTheSecondTerminalIsActive(active)
    }

    // ── The Displays screen's open viewer ────────────────────────────────────────────────────────

    /** A host running one VNC display, d2. Its viewer's client never connects (no socket here). */
    private fun displaysHost(): HostStore = testHostStore(
        MockBroker(
            mapOf("GET /displays" to """[{"id":"d2","sessionName":"demo","provider":"linux-xvfb","transport":"vnc","status":"running"}]"""),
        ).handler,
    )

    private fun ComposeUiTest.openTheViewer() {
        await("displays_row_d2")
        onNodeWithTag("displays_row_d2").performClick()
        await("display_viewer")
    }

    @Test fun the_displays_viewer_stays_open_through_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testFleet(displaysHost())) { openDisplays() }
        openTheViewer()

        activity.recreate()

        await("display_viewer")
    }

    @Test fun the_displays_viewer_stays_open_through_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(testFleet(displaysHost())) { openDisplays() }
        openTheViewer()

        unfold(width)

        await("display_viewer")
    }

    // ── One level down: a display's soft keyboard ────────────────────────────────────────────────

    private val vncStream = DisplayStream(id = "d2", sessionName = "demo", provider = "linux-xvfb", transport = "vnc", status = "running")

    /** Every client the display made, stopped before it connects; the host's streams, built once. */
    private inner class StoppedClients {
        val made: MutableList<VncClient> = Collections.synchronizedList(mutableListOf())
        val displays: StateFlow<List<DisplayStream>> = MutableStateFlow(listOf(vncStream))

        fun connect(streamId: String): VncClient =
            VncClient("ws://test:9898", "t", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }), streamId)
                .also { it.stop() }
                .also { made += it }
    }

    /** A workspace's display tab (w1's view v3), as ViewHost names it. */
    @Composable
    private fun Display(clients: StoppedClients) {
        val actions = remember { DisplayActions(displays = clients.displays, listDisplays = { listOf(vncStream) }, connectVnc = clients::connect) }
        SupermuxTheme(appearance = AppearanceMode.DARK) { DisplayPanel(sessionName = "demo", actions = actions, retainKey = "w1:v3") }
    }

    private fun ComposeUiTest.raiseTheKeyboard() {
        await("display_keyboard_toggle")
        onNodeWithTag("display_keyboard_toggle").performClick()
        waitForIdle()
        onNodeWithTag("display_hidden_keyboard").assertIsFocused()
    }

    @Test fun a_displays_keyboard_stays_up_through_a_recreation() = runComposeUiTest {
        val retained = retainedUi()
        val clients = StoppedClients()
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { Display(clients) }
            }
        }
        raiseTheKeyboard()

        activity.recreate()

        // A new view, with its keyboard up again (its field takes focus, which raises the IME).
        waitUntil(timeoutMillis = 10_000) { clients.made.size == 2 }
        await("display_hidden_keyboard")
        onNodeWithTag("display_hidden_keyboard").assertIsFocused()
    }

    @Test fun a_displays_keyboard_stays_up_through_a_move_to_the_wide_layout() = runComposeUiTest {
        val retained = retainedUi()
        val clients = StoppedClients()
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    if (wide) Row { Display(clients) } else Column { Display(clients) }
                }
            }
        }
        raiseTheKeyboard()

        wide = true
        waitForIdle()

        waitUntil(timeoutMillis = 10_000) { clients.made.size == 2 }
        await("display_hidden_keyboard")
        onNodeWithTag("display_hidden_keyboard").assertIsFocused()
    }

    // ── A staged attachment and its upload, with no store from the host (iOS, desktop) ───────────

    private fun shot() = PickedFile("shot.jpg", "image/jpeg", ByteArrayChunkSource(byteArrayOf(1, 2, 3)))

    /** The one chat on a host that holds its first upload at [gate]. */
    private fun uploadHost(gate: HeldRequest): Pair<HostStore, MockBroker> {
        val broker = MockBroker(
            mapOf("POST /upload" to """{"file_id":"f1","size":3,"mime":"image/jpeg","name":"shot.jpg"}"""),
            gates = mapOf("POST /upload" to gate),
        )
        return oneChatWorkspace(broker.handler) to broker
    }

    /** A photo staged in the composer the way a picker hands it over; its upload is at the host. */
    private fun ComposeUiTest.stageAPhoto(platform: FakePlatform, gate: HeldRequest) {
        await("composer-input")
        waitForIdle()
        assertTrue(platform.pendingPicks.tryEmit(shot()))
        waitUntil(timeoutMillis = 10_000) { gate.started.isCompleted }
        onNodeWithTag("composer-chip").assertExists()
        // Uploading: a chip has no × until its upload is done.
        onNodeWithTag("composer-chip-remove").assertDoesNotExist()
    }

    private fun ComposeUiTest.assertTheChipAndItsUploadAreThere(broker: MockBroker) {
        // Done (now it can be removed), and uploaded once: not cancelled, not run again.
        await("composer-chip-remove")
        onNodeWithTag("composer-chip").assertExists()
        assertEquals(1, broker.count("POST /upload"))
    }

    @Test fun a_staged_photo_and_its_upload_survive_a_recreation_without_a_host_store() = runComposeUiTest {
        val gate = HeldRequest()
        val (app, broker) = uploadHost(gate)
        val platform = FakePlatform()
        val activity = phoneShell(testFleet(app), platform) { selectedId = "s1" }
        stageAPhoto(platform, gate)

        activity.recreate()
        gate.release.complete(Unit)

        assertTheChipAndItsUploadAreThere(broker)
    }

    @Test fun a_staged_photo_and_its_upload_survive_the_compact_to_wide_swap_without_a_host_store() = runComposeUiTest {
        val gate = HeldRequest()
        val (app, broker) = uploadHost(gate)
        val platform = FakePlatform()
        val width = flippablePhone(testFleet(app), platform) { selectedId = "s1" }
        stageAPhoto(platform, gate)

        unfold(width)
        gate.release.complete(Unit)

        assertTheChipAndItsUploadAreThere(broker)
    }
}
