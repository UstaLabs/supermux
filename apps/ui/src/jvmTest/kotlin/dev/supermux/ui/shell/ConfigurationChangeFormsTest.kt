// A configuration change must not close what the user had open, nor empty what they typed in it
// (card #89, phase 2): search fields, rename and create dialogs, a subagent's message, the
// "Continue in new conversation" form, review comments being written.
//
// The same two events as ConfigurationChangeTest, on the real shell where the shell reaches the
// spot: [Recreation] for an Android recreation (rotate, fold, split-screen, dark mode, font scale),
// and a Compact→Expanded flip inside one composition for the layout swap (a phone turned to
// landscape, a Fold opened; all iOS gets). The shell here is Android's phone: touch, and no
// platform context menu, so a sidebar row carries its ⋮. Two spots the shell cannot drive in a test
// are driven one level down, inside the fleet's retained state: the Changes pane's patch rows (the
// workspace's diff pane draws files on the native editor) and a terminal's find bar (a terminal
// needs its engine).
package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.DiffFile
import dev.supermux.net.RepoDiff
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.Subagent
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.state.HostStore
import dev.supermux.state.LauncherDraft
import dev.supermux.state.RetainedUiState
import dev.supermux.terminal.compose.TerminalSearchState
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.CHAT_FIND_FIELD_TAG
import dev.supermux.ui.chat.ContinueTestIds
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.PlatformTestProviders
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.editor.DiffView
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.retain.LocalRetainedUiState
import dev.supermux.ui.session.LocalContextMenuAvailable
import dev.supermux.ui.session.OrganizeProjectsTestIds
import dev.supermux.ui.session.ProjectSettingsTestIds
import dev.supermux.ui.session.ProjectTestIds
import dev.supermux.ui.session.SidebarSearchTestIds
import dev.supermux.ui.session.WorkspaceListTestIds
import dev.supermux.ui.terminal.LocalTerminalFindKey
import dev.supermux.ui.terminal.TERMINAL_SEARCH_FIELD_TAG
import dev.supermux.ui.terminal.TerminalSearchBar
import dev.supermux.ui.terminal.rememberRetainedTerminalSearch
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.workspace.singleViewLayout
import dev.supermux.workspace.toDto
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Android's phone around the shell: no platform context menu (a sidebar row's actions are in its ⋮),
 * and a hardware keyboard only when [keyboard], which picks the Files pane's in-place name field
 * over its dialogs.
 */
private fun phone(keyboard: Boolean = false): @Composable (@Composable () -> Unit) -> Unit = { content ->
    CompositionLocalProvider(
        LocalContextMenuAvailable provides false,
        LocalHardwareKeyboard provides keyboard,
        content = content,
    )
}

@OptIn(ExperimentalTestApi::class)
class ConfigurationChangeFormsTest {

    /**
     * The phone's shell over [app] in a [Recreation]: MainActivity's saveable [ShellUiState],
     * started by [initial]. The fleet and the platform are built here, outside the content, as the
     * view model holds them through a recreation.
     */
    private fun ComposeUiTest.phoneShell(
        app: HostStore,
        keyboard: Boolean = false,
        prefs: UiPrefs = UiPrefs(FakeSettingsStore()),
        initial: ShellUiState.() -> Unit = {},
    ): Recreation {
        val fleet = testFleet(app)
        val platform = FakePlatform()
        return Recreation(this).also { it.setShell(fleet, platform, prefs, wrap = phone(keyboard), initial = initial) }
    }

    /** The same phone's shell at a width the test flips to Expanded: the phone turned, the Fold opened. */
    private fun ComposeUiTest.flippablePhone(
        app: HostStore,
        keyboard: Boolean = false,
        prefs: UiPrefs = UiPrefs(FakeSettingsStore()),
        initial: ShellUiState.() -> Unit = {},
    ): MutableState<WindowWidthClass> =
        swappableShell(testFleet(app), ShellUiState().apply(initial), prefs = prefs, wrap = phone(keyboard))

    private fun ComposeUiTest.unfold(width: MutableState<WindowWidthClass>) {
        width.value = WindowWidthClass.Expanded
        waitForIdle()
    }

    private fun chatWorkspace(id: String, name: String, sessionId: String) = WorkspaceDto(
        id = id, name = name, workdir = "/w",
        primarySessionId = sessionId,
        layout = singleViewLayout("$id-g", "$id-chat").toDto(),
        views = listOf(
            ViewDto(
                id = "$id-chat", workspaceId = id, kind = "chat",
                state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
            ),
        ),
    )

    // ── The chat's find bar ──────────────────────────────────────────────────────────────────────

    private fun ComposeUiTest.findInTheChat(query: String) {
        onNodeWithTag("chat_find_open").performClick()
        onNodeWithTag(CHAT_FIND_FIELD_TAG).performTextInput(query)
        waitForIdle()
    }

    @Test fun the_chat_find_bar_and_its_query_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(oneChatWorkspace()) { selectedId = "s1" }
        findInTheChat("needle")

        activity.recreate()

        assertEquals("needle", onNodeWithTag(CHAT_FIND_FIELD_TAG).editableText())
    }

    @Test fun the_chat_find_bar_and_its_query_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(oneChatWorkspace()) { selectedId = "s1" }
        findInTheChat("needle")

        unfold(width)

        assertEquals("needle", onNodeWithTag(CHAT_FIND_FIELD_TAG).editableText())
    }

    // ── The chat header's Rename ─────────────────────────────────────────────────────────────────

    private fun ComposeUiTest.renameFromTheChatHeader(name: String) {
        onNodeWithTag("shell_overflow").performClick()
        onNodeWithTag("overflow_rename").performClick()
        onNodeWithTag("overflow_rename_field").performTextReplacement(name)
        waitForIdle()
    }

    @Test fun the_chat_header_rename_and_its_text_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(oneChatWorkspace()) { selectedId = "s1" }
        renameFromTheChatHeader("the login bug")

        activity.recreate()

        assertEquals("the login bug", onNodeWithTag("overflow_rename_field").editableText())
    }

    @Test fun the_chat_header_rename_and_its_text_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(oneChatWorkspace()) { selectedId = "s1" }
        renameFromTheChatHeader("the login bug")

        unfold(width)

        assertEquals("the login bug", onNodeWithTag("overflow_rename_field").editableText())
    }

    // ── "Continue in new conversation" ───────────────────────────────────────────────────────────

    private fun ComposeUiTest.writeAHandoff(text: String) {
        onNodeWithTag("shell_overflow").performClick()
        onNodeWithTag(ContinueTestIds.CONTINUE).performClick()
        waitForIdle()
        onNodeWithTag(ContinueTestIds.CONTINUE_FIELD).performTextReplacement(text)
        waitForIdle()
    }

    @Test fun the_continue_form_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(oneChatWorkspace()) { selectedId = "s1" }
        writeAHandoff("Pick up the login bug where it stopped.")

        activity.recreate()

        assertEquals("Pick up the login bug where it stopped.", onNodeWithTag(ContinueTestIds.CONTINUE_FIELD).editableText())
    }

    @Test fun the_continue_form_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(oneChatWorkspace()) { selectedId = "s1" }
        writeAHandoff("Pick up the login bug where it stopped.")

        unfold(width)

        assertEquals("Pick up the login bug where it stopped.", onNodeWithTag(ContinueTestIds.CONTINUE_FIELD).editableText())
    }

    // ── A subagent's card: open, and the message being written to it ─────────────────────────────

    /** The one chat, with a running subagent "Explore" (sa1) that takes messages. */
    private fun chatWithASubagent(): HostStore = oneChatWorkspace().also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = app.sessions.value,
                workspaces = app.workspaces.value,
                subagents = mapOf(
                    "s1" to listOf(
                        Subagent(id = "sa1", name = "Explore", status = "running", messaging = "relay", startedAt = 1_790_600_000_000L),
                    ),
                ),
            ),
        )
    }

    private fun ComposeUiTest.writeToTheSubagent(text: String) {
        onNodeWithTag("subagent-card-header:sa1").performClick()
        onNodeWithTag("subagent-message-field:sa1").performTextInput(text)
        waitForIdle()
    }

    @Test fun an_open_subagent_card_and_its_message_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(chatWithASubagent()) { selectedId = "s1" }
        writeToTheSubagent("check logout too")

        activity.recreate()

        // Still open (the field is only in an open card), and the message is still there.
        assertEquals("check logout too", onNodeWithTag("subagent-message-field:sa1").editableText())
    }

    @Test fun an_open_subagent_card_and_its_message_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(chatWithASubagent()) { selectedId = "s1" }
        writeToTheSubagent("check logout too")

        unfold(width)

        assertEquals("check logout too", onNodeWithTag("subagent-message-field:sa1").editableText())
    }

    // ── The sidebar search and its ↑/↓ cursor ────────────────────────────────────────────────────

    /** Two workspaces a search for "alpha" finds both of: "alpha one" (w1) and "alpha two" (w2). */
    private fun twoWorkspaces(): HostStore = testHostStore().also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(
                    SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude"),
                    SessionInfo(id = "s2", name = "worker-2", workdir = "/w", agent = "claude"),
                ),
                workspaces = listOf(chatWorkspace("w1", "alpha one", "s1"), chatWorkspace("w2", "alpha two", "s2")),
            ),
        )
    }

    private val cursorOnTheSecondMatch =
        hasTestTag(SidebarSearchTestIds.CURSOR) and hasAnyDescendant(hasTestTag(WorkspaceListTestIds.row("w2")))

    private fun ComposeUiTest.searchTheSidebarAndStepDown() {
        onNodeWithTag(SidebarSearchTestIds.OPEN).performClick()
        onNodeWithTag(SidebarSearchTestIds.FIELD).performTextInput("alpha")
        waitForIdle()
        onNodeWithTag(SidebarSearchTestIds.FIELD).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNode(cursorOnTheSecondMatch).assertExists()
    }

    @Test fun the_sidebar_search_and_its_cursor_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(twoWorkspaces())
        searchTheSidebarAndStepDown()

        activity.recreate()

        assertEquals("alpha", onNodeWithTag(SidebarSearchTestIds.FIELD).editableText())
        onNode(cursorOnTheSecondMatch).assertExists()
    }

    @Test fun the_sidebar_search_and_its_cursor_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(twoWorkspaces())
        searchTheSidebarAndStepDown()

        unfold(width)

        assertEquals("alpha", onNodeWithTag(SidebarSearchTestIds.FIELD).editableText())
        onNode(cursorOnTheSecondMatch).assertExists()
    }

    // ── The sidebar's Rename workspace ───────────────────────────────────────────────────────────

    private fun ComposeUiTest.renameTheWorkspace(name: String) {
        onNode(hasContentDescription("More") and hasAnyAncestor(hasTestTag(WorkspaceListTestIds.row("w1"))), useUnmergedTree = true)
            .performClick()
        onNodeWithText("Rename").performClick()
        onNodeWithTag("sidebar_rename_field").performTextReplacement(name)
        waitForIdle()
    }

    @Test fun the_sidebar_rename_and_its_text_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(oneChatWorkspace())
        renameTheWorkspace("login work")

        activity.recreate()

        assertEquals("login work", onNodeWithTag("sidebar_rename_field").editableText())
    }

    @Test fun the_sidebar_rename_and_its_text_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(oneChatWorkspace())
        renameTheWorkspace("login work")

        unfold(width)

        assertEquals("login work", onNodeWithTag("sidebar_rename_field").editableText())
    }

    // ── The sidebar's multi-select ───────────────────────────────────────────────────────────────

    private fun ComposeUiTest.selectTheWorkspace() {
        onNodeWithTag(WorkspaceListTestIds.SELECT_TOGGLE).performClick()
        onNodeWithTag(WorkspaceListTestIds.row("w1")).performClick()
        onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test fun the_sidebar_selection_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(oneChatWorkspace())
        selectTheWorkspace()

        activity.recreate()

        onNodeWithTag(WorkspaceListTestIds.BATCH_BAR).assertIsDisplayed()
        onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test fun the_sidebar_selection_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(oneChatWorkspace())
        selectTheWorkspace()

        unfold(width)

        onNodeWithTag(WorkspaceListTestIds.BATCH_BAR).assertIsDisplayed()
        onNodeWithText("1 selected").assertIsDisplayed()
    }

    // ── New Agent ────────────────────────────────────────────────────────────────────────────────
    //
    // The dialog's character preview and every gallery tile draw an AvatarRigView, which redraws on
    // every frame for as long as it is shown: with the clock advancing on its own the UI is never
    // idle. So the clock is frozen once the dialog is up, and advanced by hand ([settle]), as
    // IntroTest does for its animations.

    /** [oneChatWorkspace] on a host with Claude installed, offering one model, "Opus". */
    private fun withClaude(): HostStore = oneChatWorkspace { req ->
        when (req.url.encodedPath) {
            "/agents/status" -> respond("""[{"kind":"claude","installed":true}]""", HttpStatusCode.OK)
            "/models" -> respond("""{"models":[{"id":"opus","displayName":"Opus"}]}""", HttpStatusCode.OK)
            else -> respond("{}", HttpStatusCode.OK)
        }
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    /**
     * The providers and the model have arrived (the mock host answers on its own thread, which
     * idling does not track) and the dialog settled on them: "Opus" is the picked model's label.
     */
    private fun ComposeUiTest.awaitTheModel() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Opus").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.nameANewAgent(name: String) {
        mainClock.autoAdvance = false
        onNodeWithTag("agents_new").performClick()
        settle()
        onNodeWithTag("agents_create_name").performTextReplacement(name)
        settle()
        awaitTheModel()
    }

    @Test fun the_new_agent_dialog_and_its_name_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(withClaude())
        nameANewAgent("Scout")

        activity.recreate()
        settle()

        assertEquals("Scout", onNodeWithTag("agents_create_name").editableText())
        // And the picks stand: the model was not reset by the rebuilt dialog's effects.
        awaitTheModel()
    }

    @Test fun the_new_agent_dialog_and_its_name_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(withClaude())
        nameANewAgent("Scout")

        unfold(width)
        settle()

        assertEquals("Scout", onNodeWithTag("agents_create_name").editableText())
        awaitTheModel()
    }

    // ── Projects: Organize, New project, Project settings ────────────────────────────────────────

    /** [oneChatWorkspace] on a host with a project catalog: "Alpha" (pa, holding w1 at /w) and "Beta" (pb). */
    private fun withProjects(): HostStore = oneChatWorkspace().also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = app.sessions.value,
                workspaces = app.workspaces.value,
                projects = listOf(
                    ProjectDto(id = "pa", name = "Alpha", sortOrder = 0, locations = listOf(ProjectLocationDto("l1", "/w"))),
                    ProjectDto(id = "pb", name = "Beta", sortOrder = 1),
                ),
                // A workspace joins its project's group by membership: Alpha's header, with its ⋮.
                projectMembership = mapOf("w1" to "pa"),
            ),
        )
    }

    private fun ComposeUiTest.openOrganizeProjects() {
        onNodeWithTag("list_overflow").performClick()
        onNodeWithTag("nav_organize_projects").performClick()
        onNodeWithTag(OrganizeProjectsTestIds.SHEET).assertExists()
    }

    @Test fun organize_projects_stays_open_through_a_recreation() = runComposeUiTest {
        val activity = phoneShell(withProjects())
        openOrganizeProjects()

        activity.recreate()

        onNodeWithTag(OrganizeProjectsTestIds.SHEET).assertExists()
        onNodeWithTag(OrganizeProjectsTestIds.row("h1", "pa")).assertExists()
    }

    @Test fun organize_projects_stays_open_through_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(withProjects())
        openOrganizeProjects()

        unfold(width)

        onNodeWithTag(OrganizeProjectsTestIds.SHEET).assertExists()
        onNodeWithTag(OrganizeProjectsTestIds.row("h1", "pa")).assertExists()
    }

    private fun ComposeUiTest.nameANewProject(name: String) {
        onNodeWithTag("list_overflow").performClick()
        onNodeWithTag("nav_new_project").performClick()
        onNodeWithTag(ProjectSettingsTestIds.NEW_PROJECT_NAME).performTextInput(name)
        waitForIdle()
    }

    @Test fun the_new_project_dialog_and_its_name_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(withProjects())
        nameANewProject("Gamma")

        activity.recreate()

        assertEquals("Gamma", onNodeWithTag(ProjectSettingsTestIds.NEW_PROJECT_NAME).editableText())
    }

    @Test fun the_new_project_dialog_and_its_name_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(withProjects())
        nameANewProject("Gamma")

        unfold(width)

        assertEquals("Gamma", onNodeWithTag(ProjectSettingsTestIds.NEW_PROJECT_NAME).editableText())
    }

    private fun ComposeUiTest.renameAProject(name: String) {
        onNodeWithTag(ProjectTestIds.menu("p:h1:pa")).performClick()
        onNodeWithTag(ProjectTestIds.SETTINGS).performClick()
        onNodeWithTag(ProjectSettingsTestIds.NAME).performTextReplacement(name)
        waitForIdle()
    }

    @Test fun the_project_settings_sheet_and_its_name_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(withProjects())
        renameAProject("Alpha prime")

        activity.recreate()

        assertEquals("Alpha prime", onNodeWithTag(ProjectSettingsTestIds.NAME).editableText())
    }

    @Test fun the_project_settings_sheet_and_its_name_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(withProjects())
        renameAProject("Alpha prime")

        unfold(width)

        assertEquals("Alpha prime", onNodeWithTag(ProjectSettingsTestIds.NAME).editableText())
    }

    // ── The host switcher's Rename ───────────────────────────────────────────────────────────────

    private fun ComposeUiTest.renameTheHost(name: String) {
        onNodeWithTag("host_switcher").performClick()
        onNodeWithTag("host_switcher_rename").performClick()
        onNodeWithTag("host_rename_field").performTextReplacement(name)
        waitForIdle()
    }

    @Test fun the_host_rename_and_its_text_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(oneChatWorkspace())
        renameTheHost("Studio Mac")

        activity.recreate()

        assertEquals("Studio Mac", onNodeWithTag("host_rename_field").editableText())
    }

    @Test fun the_host_rename_and_its_text_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(oneChatWorkspace())
        renameTheHost("Studio Mac")

        unfold(width)

        assertEquals("Studio Mac", onNodeWithTag("host_rename_field").editableText())
    }

    // ── The Files pane: "Go to file…", New file (its dialog, and its in-place field) ────────────

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
                        views = listOf(
                            ViewDto(
                                id = "v1", workspaceId = "w1", kind = "chat",
                                state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))),
                            ),
                            ViewDto(
                                id = "v2", workspaceId = "w1", kind = "editor",
                                state = JsonObject(mapOf("mode" to JsonPrimitive("tree"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun ComposeUiTest.goToFile(query: String) {
        onNodeWithTag("files_search_field").performTextInput(query)
        waitForIdle()
    }

    @Test fun the_go_to_file_query_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(filesWorkspace()) { selectedId = "s1" }
        goToFile("notes")

        activity.recreate()

        assertEquals("notes", onNodeWithTag("files_search_field").editableText())
    }

    @Test fun the_go_to_file_query_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(filesWorkspace()) { selectedId = "s1" }
        goToFile("notes")

        unfold(width)

        assertEquals("notes", onNodeWithTag("files_search_field").editableText())
    }

    /** "New file" from the tree's ⋮, then a name typed into [field] (its dialog, or its in-place row). */
    private fun ComposeUiTest.nameANewFile(field: String) {
        onNodeWithTag("tree_menu").performClick()
        onNodeWithTag("tree_menu_root_new_file").performClick()
        onNodeWithTag(field).performTextInput("notes.md")
        waitForIdle()
    }

    @Test fun the_new_file_dialog_and_its_name_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(filesWorkspace()) { selectedId = "s1" }
        nameANewFile("tree_dialog_name")

        activity.recreate()

        assertEquals("notes.md", onNodeWithTag("tree_dialog_name").editableText())
    }

    @Test fun the_new_file_dialog_and_its_name_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(filesWorkspace()) { selectedId = "s1" }
        nameANewFile("tree_dialog_name")

        unfold(width)

        assertEquals("notes.md", onNodeWithTag("tree_dialog_name").editableText())
    }

    @Test fun an_in_place_new_file_name_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(filesWorkspace(), keyboard = true) { selectedId = "s1" }
        nameANewFile("tree_inline_field")

        activity.recreate()

        // Not committed or dropped by the blur of its own removal: still open, still named.
        assertEquals("notes.md", onNodeWithTag("tree_inline_field").editableText())
    }

    @Test fun an_in_place_new_file_name_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(filesWorkspace(), keyboard = true) { selectedId = "s1" }
        nameANewFile("tree_inline_field")

        unfold(width)

        assertEquals("notes.md", onNodeWithTag("tree_inline_field").editableText())
    }

    // ── The Archived screen's search ─────────────────────────────────────────────────────────────

    /** A host with a chat and no workspace: Archived lists sessions, and offers its search. */
    private fun sessionsOnly(): HostStore = testHostStore().also { app ->
        app.reduce(ServerFrame.Snapshot(sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude"))))
    }

    @Test fun the_archived_search_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(sessionsOnly()) { openArchived() }
        onNodeWithTag("archived_search").performTextInput("login")
        waitForIdle()

        activity.recreate()

        assertEquals("login", onNodeWithTag("archived_search").editableText())
    }

    @Test fun the_archived_search_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(sessionsOnly()) { openArchived() }
        onNodeWithTag("archived_search").performTextInput("login")
        waitForIdle()

        unfold(width)

        assertEquals("login", onNodeWithTag("archived_search").editableText())
    }

    // ── The launcher's project picker ────────────────────────────────────────────────────────────

    /** Prefs with a stored workdir, so the launcher opens on its composer, not the first-run screen. */
    private fun launcherPrefs(): UiPrefs = UiPrefs(InMemorySettingsStore()).also { prefs ->
        runBlocking { prefs.putLauncherDraft(LauncherDraft(workdir = "/w")) }
    }

    private fun ComposeUiTest.typeIntoTheProjectPicker(text: String) {
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        onNodeWithTag("launcher_project_search").performTextInput(text)
        waitForIdle()
    }

    @Test fun the_project_picker_and_its_query_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(oneChatWorkspace(), prefs = launcherPrefs()) { openLauncher() }
        typeIntoTheProjectPicker("~/code/app")

        activity.recreate()

        assertEquals("~/code/app", onNodeWithTag("launcher_project_search").editableText())
    }

    @Test fun the_project_picker_and_its_query_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(oneChatWorkspace(), prefs = launcherPrefs()) { openLauncher() }
        typeIntoTheProjectPicker("~/code/app")

        unfold(width)

        assertEquals("~/code/app", onNodeWithTag("launcher_project_search").editableText())
    }

    // ── One level down: a review comment being written in the Changes pane ───────────────────────
    //
    // A workspace's diff pane draws its files on the native editor, whose composer is driven
    // through the editor's own gutter; the patch rows below are the same pane's other renderer, and
    // what both keep for a rebuilt pane is the same [retainKey]'d state.

    private val oneChange = listOf(
        RepoDiff(
            repo = "",
            files = listOf(DiffFile(path = "a.txt", status = "modified", diff = "@@ -1,2 +1,2 @@\n-old\n+new\n context\n")),
        ),
    )

    /** The fleet's retained state, as `SupermuxApp` provides it; built outside the content. */
    private fun retainedUi() = RetainedUiState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Composable
    private fun ChangesPane() {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            DiffView(
                repos = oneChange,
                comments = emptyList(),
                onAddComment = { _, _, _, _, _, _ -> },
                onResolve = {},
                onSubmit = {},
                onReload = {},
                onClose = {},
                retainKey = "diff:w1:v2",
            )
        }
    }

    private fun ComposeUiTest.startAReviewComment(text: String) {
        onNodeWithTag("diff_file_0").performClick()
        onAllNodesWithTag("diff_add_comment")[0].performClick()
        onNodeWithTag("diff_comment_draft").performTextInput(text)
        waitForIdle()
    }

    @Test fun a_review_comment_being_written_survives_a_recreation() = runComposeUiTest {
        val retained = retainedUi()
        val platform = FakePlatform()
        val prefs = UiPrefs(InMemorySettingsStore())
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { ChangesPane() }
            }
        }
        startAReviewComment("this needs a test")

        activity.recreate()

        // The file is open again, its composer too, with what was written.
        assertEquals("this needs a test", onNodeWithTag("diff_comment_draft").editableText())
    }

    @Test fun a_review_comment_being_written_survives_a_move_to_the_wide_layout() = runComposeUiTest {
        val retained = retainedUi()
        val platform = FakePlatform()
        val prefs = UiPrefs(InMemorySettingsStore())
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    // Rebuilt somewhere else in the tree, as the shell's wide layout does.
                    if (wide) Row { ChangesPane() } else Column { ChangesPane() }
                }
            }
        }
        startAReviewComment("this needs a test")

        wide = true
        waitForIdle()

        assertEquals("this needs a test", onNodeWithTag("diff_comment_draft").editableText())
    }

    // ── One level down: a terminal's find bar ───────────────────────────────────────────────────
    //
    // The bar and its state as the terminal surface builds them (`rememberRetainedTerminalSearch`,
    // with the terminal named the way ViewHost names a workspace's); opened the way the surface's
    // Cmd+F opens it.

    @Composable
    private fun TerminalFind(onSearch: (TerminalSearchState) -> Unit) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            CompositionLocalProvider(LocalTerminalFindKey provides "w1:main") {
                val search = rememberRetainedTerminalSearch()
                onSearch(search)
                if (search.isOpen) TerminalSearchBar(search)
            }
        }
    }

    @Test fun a_terminal_find_bar_and_its_query_survive_a_recreation() = runComposeUiTest {
        val retained = retainedUi()
        val platform = FakePlatform()
        val prefs = UiPrefs(InMemorySettingsStore())
        var search: TerminalSearchState? = null
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { TerminalFind { search = it } }
            }
        }
        runOnIdle { search!!.open() }
        onNodeWithTag(TERMINAL_SEARCH_FIELD_TAG).performTextInput("error")
        waitForIdle()

        activity.recreate()

        assertEquals("error", onNodeWithTag(TERMINAL_SEARCH_FIELD_TAG).editableText())
    }

    @Test fun a_terminal_find_bar_and_its_query_survive_a_move_to_the_wide_layout() = runComposeUiTest {
        val retained = retainedUi()
        val platform = FakePlatform()
        val prefs = UiPrefs(InMemorySettingsStore())
        var search: TerminalSearchState? = null
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    if (wide) Row { TerminalFind { search = it } } else Column { TerminalFind { search = it } }
                }
            }
        }
        runOnIdle { search!!.open() }
        onNodeWithTag(TERMINAL_SEARCH_FIELD_TAG).performTextInput("error")
        waitForIdle()

        wide = true
        waitForIdle()

        assertEquals("error", onNodeWithTag(TERMINAL_SEARCH_FIELD_TAG).editableText())
    }
}
