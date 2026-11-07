// A configuration change must not take what the user typed with it (card #89).
//
// Android recreates MainActivity on every configuration change (rotate, fold/unfold, split-screen
// resize, dark mode, font scale, locale): the whole composition goes, and only what lives outside
// it comes back — the fleet (the view model's), the saved instance state, the settings on disk. A
// phone turned to landscape, or a Fold opened, is also ≥600dp wide, so the shell swaps its Compact
// layout for the wide one at the same moment. These tests stage both events on the real shell:
// [Recreation] for the recreation, with the fleet built OUTSIDE the content the way the view model
// holds it, and a width-class flip inside one composition for the swap, which is all iOS gets.
package dev.supermux.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BrokerApi
import dev.supermux.proto.DraftPayload
import dev.supermux.proto.LogEntry
import dev.supermux.proto.PromptRequest
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.LauncherDraft
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.PlatformTestProviders
import dev.supermux.ui.chat.TestWalkthroughSeam
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.platform.FakeMic
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.UiPrefs
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an Android configuration change does to a composition: all of it is disposed, and the new one
 * gets back only what lives outside it (here, what a test builds before `setContent`: the fleet,
 * the platform, the prefs) and its `rememberSaveable` state, saved and restored by key. Plain
 * `remember` is gone. (`StateRestorationTester` stages the same thing, but its desktop
 * `emulateSaveAndRestore` is a `TODO()` in CMP 1.12.)
 */
@OptIn(ExperimentalTestApi::class)
private class Recreation(private val test: ComposeUiTest) {
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var shown by mutableStateOf(true)

    fun setContent(content: @Composable () -> Unit) = test.setContent {
        // The same call site before and after, so every rememberSaveable finds its key again.
        if (shown) CompositionLocalProvider(LocalSaveableStateRegistry provides registry, content = content)
    }

    fun recreate() {
        val saved = test.runOnIdle { registry.performSave() }
        shown = false
        test.waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        shown = true
        test.waitForIdle()
    }
}

@OptIn(ExperimentalTestApi::class)
class ConfigurationChangeTest {

    private fun oneChatWorkspace(
        respondTo: MockRequestHandler = { respond("{}", HttpStatusCode.OK) },
    ): HostStore = testHostStore(respondTo).also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
                workspaces = listOf(
                    WorkspaceDto(
                        id = "w1", name = "proj", workdir = "/w",
                        primarySessionId = "s1",
                        layout = singleViewLayout("g1", "v1").toDto(),
                        views = listOf(
                            ViewDto(
                                id = "v1", workspaceId = "w1", kind = "chat",
                                state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun SemanticsNodeInteraction.editableText(): String =
        fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** The transcript's list (inside `chat_body`; the sidebar list has the same scroll action). */
    private fun ComposeUiTest.transcript(): SemanticsNodeInteraction =
        onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("chat_body")))

    /**
     * The shell at a width the test can flip, the way a phone turned to landscape or a Fold opened
     * flips it — one composition, so this is the swap alone, as iOS gets it.
     */
    private fun ComposeUiTest.swappableShell(
        fleet: FleetStore,
        ui: ShellUiState,
        platform: FakePlatform = FakePlatform(),
        prefs: UiPrefs = UiPrefs(FakeSettingsStore()),
    ): MutableState<WindowWidthClass> {
        val width = mutableStateOf(WindowWidthClass.Compact)
        setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = width.value, inputMode = InputMode.Touch, uiPrefs = prefs) {
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        return width
    }

    /** A host that transcribes any recording as "words said while turning". */
    private val transcribes: MockRequestHandler = { request ->
        if ("transcribe" in request.url.encodedPath) {
            respond(
                """{"text":"words said while turning"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        } else {
            respond("{}", HttpStatusCode.OK)
        }
    }

    private fun ComposeUiTest.awaitDraft(text: String) {
        // The transcription answers on the mock engine's thread: wait for it, not for idle.
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithTag("composer-input").editableText() }.getOrNull() == text
        }
        assertEquals(text, onNodeWithTag("composer-input").editableText())
    }

    @Test fun the_composer_draft_survives_a_recreation() = runComposeUiTest {
        val fleet = testFleet(oneChatWorkspace())
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                // MainActivity's own holder: saveable, so the open chat comes back with the activity.
                val ui = rememberSaveable(saver = ShellUiState.Saver) { ShellUiState().apply { selectedId = "s1" } }
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        onNodeWithTag("composer-input").performTextInput("half a thought")
        waitForIdle()

        activity.recreate()

        assertEquals("half a thought", onNodeWithTag("composer-input").editableText())
    }

    // ── Unsaved edits in a file tab ──────────────────────────────────────────────────────────────

    /** A workspace showing one file, "notes.txt", whose text on the host is "on disk". */
    private fun fileWorkspace(): HostStore = testHostStore { req ->
        if (req.url.encodedPath == "/workspaces/w1/fs/read") respond("on disk", HttpStatusCode.OK) else respond("{}", HttpStatusCode.OK)
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
                                state = JsonObject(mapOf("mode" to JsonPrimitive("file"), "path" to JsonPrimitive("notes.txt"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun ShellUiState?.notes() = this?.panesBindFor("w1")?.ws?.documents?.get("notes.txt")

    @Test fun unsaved_edits_in_a_file_tab_survive_a_recreation() = runComposeUiTest {
        val fleet = testFleet(fileWorkspace())
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        var shell: ShellUiState? = null
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                val ui = rememberSaveable(saver = ShellUiState.Saver) { ShellUiState().apply { selectedId = "s1" } }
                shell = ui
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        waitUntil(timeoutMillis = 10_000) { shell.notes() != null }
        shell.notes()!!.content = "my unsaved edit"
        waitForIdle()

        activity.recreate()
        waitUntil(timeoutMillis = 10_000) { shell.notes() != null }

        // Not reloaded from disk: the edit is still there, and still unsaved.
        assertEquals("my unsaved edit", shell.notes()!!.content)
        assertTrue(shell.notes()!!.isDirty)
    }

    @Test fun unsaved_edits_in_a_file_tab_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val fleet = testFleet(fileWorkspace())
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        val ui = ShellUiState().apply { selectedId = "s1" }
        var width by mutableStateOf(WindowWidthClass.Compact)
        setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        waitUntil(timeoutMillis = 10_000) { ui.notes() != null }
        ui.notes()!!.content = "my unsaved edit"
        waitForIdle()

        width = WindowWidthClass.Expanded
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { ui.notes() != null }

        assertEquals("my unsaved edit", ui.notes()!!.content)
        assertTrue(ui.notes()!!.isDirty)
    }

    // ── A dictation in progress ──────────────────────────────────────────────────────────────────

    @Test fun a_dictation_in_progress_lands_in_the_draft_through_a_recreation() = runComposeUiTest {
        val fleet = testFleet(oneChatWorkspace(transcribes))
        // The mic records, and hands back audio when it is stopped.
        val platform = FakePlatform().apply { mic = FakeMic() }
        val prefs = UiPrefs(FakeSettingsStore())
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                val ui = rememberSaveable(saver = ShellUiState.Saver) { ShellUiState().apply { selectedId = "s1" } }
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("voice_stop").assertExists()

        activity.recreate()

        // What was said is not thrown away: it is transcribed into the session's draft.
        awaitDraft("words said while turning")
    }

    @Test fun a_dictation_in_progress_lands_in_the_draft_through_the_compact_to_wide_swap() = runComposeUiTest {
        val width = swappableShell(
            testFleet(oneChatWorkspace(transcribes)),
            ShellUiState().apply { selectedId = "s1" },
            platform = FakePlatform().apply { mic = FakeMic() },
        )
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("voice_stop").assertExists()

        width.value = WindowWidthClass.Expanded

        awaitDraft("words said while turning")
    }

    // ── An agent's question: the answers typed so far ────────────────────────────────────────────

    private val twoQuestions = PromptRequest(
        requestId = "r1",
        kind = "question",
        title = "Goal",
        body = """[
          {"id":"q1","header":"Goal","prompt":"What should we work on?","multiSelect":false,
           "options":[{"id":"o1","label":"Test features"},{"id":"o2","label":"Build something"}]},
          {"id":"q2","header":"Features","prompt":"Which features?","multiSelect":true,
           "options":[{"id":"o1","label":"Choice"},{"id":"o2","label":"Multi"}]}
        ]""",
    )

    @Test fun answers_given_to_a_question_card_survive_a_recreation() = runComposeUiTest {
        val app = oneChatWorkspace().also { it.reduce(ServerFrame.RequestOpen(session = "s1", request = twoQuestions)) }
        val fleet = testFleet(app)
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                val ui = rememberSaveable(saver = ShellUiState.Saver) { ShellUiState().apply { selectedId = "s1" } }
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        onNodeWithTag("request-option:o2").performClick()
        onNodeWithTag("request-next").performClick()
        waitForIdle()
        onNodeWithText("Which features?").assertExists()

        activity.recreate()

        // Still on the second question, and the first answer is still picked.
        onNodeWithText("Which features?").assertExists()
        onNodeWithTag("request-back").performClick()
        waitForIdle()
        onNodeWithTag("request-option:o2").assertIsSelected()
    }

    @Test fun answers_given_to_a_question_card_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val app = oneChatWorkspace().also { it.reduce(ServerFrame.RequestOpen(session = "s1", request = twoQuestions)) }
        val width = swappableShell(testFleet(app), ShellUiState().apply { selectedId = "s1" })
        onNodeWithTag("request-option:o2").performClick()
        onNodeWithTag("request-next").performClick()
        waitForIdle()

        width.value = WindowWidthClass.Expanded
        waitForIdle()

        onNodeWithText("Which features?").assertExists()
        onNodeWithTag("request-back").performClick()
        waitForIdle()
        onNodeWithTag("request-option:o2").assertIsSelected()
    }

    // ── A launcher send still in flight ──────────────────────────────────────────────────────────

    /** A host whose spawn (POST /sessions) waits for [release] before it answers "sess-new". */
    private fun gatedSpawnApp(spawnStarted: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>): HostStore {
        val engine = MockEngine { req ->
            val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (req.url.encodedPath) {
                "/paths/validate" -> respond("""{"ok":true,"path":"/w"}""", HttpStatusCode.OK, json)
                "/sessions" -> {
                    spawnStarted.complete(Unit)
                    release.await()
                    respond("""{"id":"sess-new","name":"feat-x","workdir":"/w","agent":"claude"}""", HttpStatusCode.OK, json)
                }
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = CoroutineScope(Dispatchers.Default), // a real clock: the spawn runs under withTimeout
            deps = shellTestDeps(),
            connectOnInit = false,
            sendFrameOverride = {},
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
            walkthroughSeam = TestWalkthroughSeam,
        )
    }

    @Test fun a_launcher_send_in_flight_still_opens_its_chat_after_a_recreation() = runComposeUiTest {
        val spawnStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fleet = testFleet(gatedSpawnApp(spawnStarted, release))
        val platform = FakePlatform()
        val prefs = UiPrefs(InMemorySettingsStore())
        runBlocking { prefs.putLauncherDraft(LauncherDraft(workdir = "/w")) }
        var shell: ShellUiState? = null
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                val ui = rememberSaveable(saver = ShellUiState.Saver) { ShellUiState().apply { openLauncher() } }
                shell = ui
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("build the thing")
        onNodeWithTag("launcher_submit").performClick()
        waitUntil(timeoutMillis = 10_000) { spawnStarted.isCompleted }

        // The phone turns while the broker is still starting the session.
        activity.recreate()
        release.complete(Unit)

        // The send is not cancelled with the old screen: the new chat opens…
        waitUntil(timeoutMillis = 10_000) { shell?.selectedId == "sess-new" }
        // …and the text it carried does not come back as a draft to send a second time.
        waitUntil(timeoutMillis = 10_000) { runBlocking { prefs.launcherDraft.first().text }.isEmpty() }
    }

    @Test fun a_launcher_send_in_flight_still_opens_its_chat_after_the_compact_to_wide_swap() = runComposeUiTest {
        val spawnStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val prefs = UiPrefs(InMemorySettingsStore())
        runBlocking { prefs.putLauncherDraft(LauncherDraft(workdir = "/w")) }
        val ui = ShellUiState().apply { openLauncher() }
        val width = swappableShell(testFleet(gatedSpawnApp(spawnStarted, release)), ui, prefs = prefs)
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("build the thing")
        onNodeWithTag("launcher_submit").performClick()
        waitUntil(timeoutMillis = 10_000) { spawnStarted.isCompleted }

        // The launcher moves from its own Compact route to the wide detail pane mid-spawn.
        width.value = WindowWidthClass.Expanded
        waitForIdle()
        release.complete(Unit)

        waitUntil(timeoutMillis = 10_000) { ui.selectedId == "sess-new" }
        waitUntil(timeoutMillis = 10_000) { runBlocking { prefs.launcherDraft.first().text }.isEmpty() }
    }

    // ── A reopened task-list draft ───────────────────────────────────────────────────────────────

    @Test fun edits_to_a_reopened_draft_survive_a_recreation() = runComposeUiTest {
        val app = testHostStore().also { app ->
            app.reduce(
                ServerFrame.Snapshot(
                    sessions = listOf(
                        SessionInfo(
                            id = "d1", name = "draft", workdir = "/w", agent = "claude", status = "draft",
                            draftPayload = DraftPayload(text = "the server's copy"),
                        ),
                    ),
                ),
            )
        }
        val fleet = testFleet(app)
        val platform = FakePlatform()
        // A stored workdir, so the launcher opens on its composer rather than the first-run screen.
        val prefs = UiPrefs(InMemorySettingsStore())
        runBlocking { prefs.putLauncherDraft(LauncherDraft(workdir = "/w")) }
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                val ui = rememberSaveable(saver = ShellUiState.Saver) { ShellUiState().apply { openLauncher(draftId = "d1") } }
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        waitForIdle()
        assertEquals("the server's copy", onNodeWithTag("launcher_message").editableText())
        onNodeWithTag("launcher_message").performTextReplacement("my own edit")
        waitForIdle()

        activity.recreate()

        assertEquals("my own edit", onNodeWithTag("launcher_message").editableText())
    }

    @Test fun edits_to_a_reopened_draft_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val app = testHostStore().also { app ->
            app.reduce(
                ServerFrame.Snapshot(
                    sessions = listOf(
                        SessionInfo(
                            id = "d1", name = "draft", workdir = "/w", agent = "claude", status = "draft",
                            draftPayload = DraftPayload(text = "the server's copy"),
                        ),
                    ),
                ),
            )
        }
        val prefs = UiPrefs(InMemorySettingsStore())
        runBlocking { prefs.putLauncherDraft(LauncherDraft(workdir = "/w")) }
        val width = swappableShell(testFleet(app), ShellUiState().apply { openLauncher(draftId = "d1") }, prefs = prefs)
        waitForIdle()
        assertEquals("the server's copy", onNodeWithTag("launcher_message").editableText())
        onNodeWithTag("launcher_message").performTextReplacement("my own edit")
        waitForIdle()

        width.value = WindowWidthClass.Expanded
        waitForIdle()

        assertEquals("my own edit", onNodeWithTag("launcher_message").editableText())
    }

    // ── Where the reader was in the transcript ───────────────────────────────────────────────────

    private fun longChat(): HostStore = oneChatWorkspace().also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = app.sessions.value,
                workspaces = app.workspaces.value,
                logs = mapOf(
                    "s1" to (1..40).map { n ->
                        LogEntry(
                            id = "m$n",
                            ts = "2026-11-07T01:%02d:00Z".format(n),
                            direction = if (n % 2 == 0) "inbound" else "outbound",
                            text = "message $n: one more line of the conversation, long enough to wrap once or twice on a phone.",
                        )
                    },
                ),
            ),
        )
    }

    @Test fun the_transcript_keeps_its_place_through_a_recreation() = runComposeUiTest {
        val fleet = testFleet(longChat())
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                val ui = rememberSaveable(saver = ShellUiState.Saver) { ShellUiState().apply { selectedId = "s1" } }
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        waitForIdle()
        transcript().performScrollToIndex(0)
        waitForIdle()
        onNodeWithText("message 1:", substring = true).assertIsDisplayed()

        activity.recreate()

        // Forty wrapped messages do not fit: had it jumped to the end, the first would be off screen.
        onNodeWithText("message 1:", substring = true).assertIsDisplayed()
    }

    @Test fun the_transcript_keeps_its_place_through_the_compact_to_wide_swap() = runComposeUiTest {
        val fleet = testFleet(longChat())
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        val ui = ShellUiState().apply { selectedId = "s1" }
        var width by mutableStateOf(WindowWidthClass.Compact)
        setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        waitForIdle()
        transcript().performScrollToIndex(0)
        waitForIdle()
        onNodeWithText("message 1:", substring = true).assertIsDisplayed()

        width = WindowWidthClass.Expanded
        waitForIdle()

        onNodeWithText("message 1:", substring = true).assertIsDisplayed()
    }

    @Test fun the_composer_draft_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val fleet = testFleet(oneChatWorkspace())
        val platform = FakePlatform()
        val prefs = UiPrefs(FakeSettingsStore())
        val ui = ShellUiState().apply { selectedId = "s1" }
        var width by mutableStateOf(WindowWidthClass.Compact)
        setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                SupermuxApp(fleet = fleet, ui = ui)
            }
        }
        onNodeWithTag("composer-input").performTextInput("half a thought")
        waitForIdle()

        // The phone turned to landscape, or the Fold opened…
        width = WindowWidthClass.Expanded
        waitForIdle()
        assertEquals("half a thought", onNodeWithTag("composer-input").editableText())

        // …and back.
        width = WindowWidthClass.Compact
        waitForIdle()
        assertEquals("half a thought", onNodeWithTag("composer-input").editableText())
    }
}
