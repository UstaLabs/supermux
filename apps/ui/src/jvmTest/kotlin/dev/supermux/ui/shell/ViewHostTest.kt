package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.state.HostStore
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.rememberChatActions
import dev.supermux.ui.chat.rememberChatState
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.editor.DocumentStore
import dev.supermux.ui.editor.engine.UnavailableEditorEngineFactory
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.workspace.viewTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun view(kind: String, state: Map<String, String>) = ViewDto(
    id = "v1", workspaceId = "w1", kind = kind,
    state = JsonObject(state.mapValues { JsonPrimitive(it.value) }),
)

/**
 * The shared view host (cluster G7). Moved from `:desktop`'s `ViewHostTest` with its case names
 * when both hosts' view hosts collapsed into `:ui`.
 *
 * Workspace terminals inject a pure-Compose stand-in: a SwingPanel/AndroidView engine cannot be
 * hosted under runComposeUiTest.
 *
 * The `file` pane injects an engine factory instead: an unavailable one makes `EditorSurface` draw
 * its native BasicTextField fallback, which is pure Compose AND shows the document's text — so a
 * test can read the buffer a pane is displaying without booting a browser.
 */
@OptIn(ExperimentalTestApi::class)
class ViewHostTest {

    private fun fakeApp(): HostStore = testHostStore()

    /** A machine with the walkthrough seam installed, which is what both real hosts ship. */
    private fun platform() = FakePlatform(caps = NO_CAPS.copy(walkthrough = true))

    /** No engine is ever built under test. Error picks the visible native fallback. */
    private val noJcef = UnavailableEditorEngineFactory("no chromium under test")

    /**
     * Unconfined so a non-suspending fsRead resolves inside [DocumentStore.open] itself — the
     * document is present by the time the pane reads it back.
     */
    private fun store(content: String = "hello from the store") = DocumentStore(
        fsRead = { Result.success(content) },
        fsWrite = { _, _ -> true },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    /** The host as both apps mount it: one [ShellActions] + the panel's two store-backed holders. */
    @Composable
    private fun host(
        app: HostStore,
        view: ViewDto,
        workdir: String = "/w",
        documents: DocumentStore? = null,
        modifier: Modifier = Modifier,
        chatHeaderMode: ChatHeaderMode = defaultChatHeaderMode(),
        onOpenFile: (String, Int?, Int?) -> Unit = { _, _, _ -> },
        workspaceTerminalContent: (@Composable (() -> dev.supermux.net.TerminalClient, Modifier) -> Unit)? = null,
    ) {
        ViewHost(
            view = view,
            workspaceId = "w1",
            workdir = workdir,
            actions = rememberShellActions(app),
            drafts = mutableStateMapOf(),
            documents = documents,
            modifier = modifier,
            chatHeaderMode = chatHeaderMode,
            chatState = { sid -> rememberChatState(app, sid) },
            chatActions = { s -> rememberChatActions(app, s) },
            onOpenFile = onOpenFile,
            editorEngineFactory = noJcef,
            workspaceTerminalContent = workspaceTerminalContent
                ?: { _, mod -> Box(mod.fillMaxSize()) { Text("term-stand-in") } },
        )
    }

    @Test
    fun aWorkspaceTerminalBuildsTheTerminalWidget() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) {
            host(app, view("terminal", mapOf("scope" to "workspace", "terminalId" to "main")))
        }
        onNodeWithTag("terminal-w1-main").assertIsDisplayed()
    }

    @Test
    fun anUnknownKindDrawsAHintRatherThanCrashing() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) { host(app, view("hologram", emptyMap())) }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }

    @Test
    fun aChatViewWithNoSessionIdDrawsTheHint() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) { host(app, view("chat", emptyMap())) }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }

    // ── The editor is three panes, chosen by `mode` (spec §7.2) ──────────────────────────────

    @Test
    fun modeTreeDrawsTheExplorerPane() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) {
            host(app, view("editor", mapOf("mode" to "tree")), workdir = "/some/dir")
        }
        onNodeWithTag("editor-/some/dir").assertIsDisplayed()
        onNodeWithTag("editor_explorer_pane").assertIsDisplayed()
        onNodeWithTag("editor_tree").assertIsDisplayed()
    }

    /** An `editor` row written before this phase has no mode at all. It must keep working. */
    @Test
    fun anEditorViewWithNoModeStillDrawsTheTree() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) {
            host(app, view("editor", emptyMap()), workdir = "/some/dir")
        }
        onNodeWithTag("editor_explorer_pane").assertIsDisplayed()
    }

    @Test
    fun anUnknownModeFallsBackToTheTree() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) {
            host(app, view("editor", mapOf("mode" to "holodeck")), workdir = "/some/dir")
        }
        onNodeWithTag("editor_explorer_pane").assertIsDisplayed()
    }

    @Test
    fun modeFileDrawsOneDocumentFromTheStore() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) {
            host(
                app,
                view("editor", mapOf("mode" to "file", "path" to "src/Main.kt")),
                workdir = "/some/dir",
                documents = store("fun main() {}"),
            )
        }
        onNodeWithTag("editor_file_pane").assertIsDisplayed()
        // No tree and no tab row of its own — the group's strip is the tab row now.
        onNodeWithTag("editor_tree").assertDoesNotExist()
        onNodeWithTag("editor_native_input").assertTextEquals("fun main() {}")
    }

    @Test
    fun modeFileWithNoPathDrawsTheHintRatherThanAnEmptySurface() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) {
            host(
                app,
                view("editor", mapOf("mode" to "file")),
                workdir = "/some/dir",
                documents = store(),
            )
        }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }

    @Test
    fun modeDiffDrawsTheDiffPane() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) {
            host(app, view("editor", mapOf("mode" to "diff")), workdir = "/some/dir")
        }
        onNodeWithTag("editor_diff_pane").assertIsDisplayed()
        onNodeWithTag("diff_view").assertIsDisplayed()
    }

    /** A workspace with no chat has no LSP — the FILE pane says so, quietly. */
    @Test
    fun aFilePaneInAChatlessWorkspaceSaysCodeIntelligenceIsOff() = runComposeUiTest {
        val app = fakeApp()
        setPlatformContent(platform()) {
            host(
                app,
                view("editor", mapOf("mode" to "file", "path" to "a.txt")),
                workdir = "/some/dir",
                documents = store(),
            )
        }
        onNodeWithTag("editor-no-lsp").assertIsDisplayed()
    }

    // ── One store, two panes: the buffer is shared (spec §18) ────────────────────────────────

    @Test
    fun twoFilePanesOnOnePathShowOneBuffer() = runComposeUiTest {
        val app = fakeApp()
        val documents = store("original text")
        setPlatformContent(platform()) {
            Box(Modifier.fillMaxSize()) {
                host(
                    app,
                    view("editor", mapOf("mode" to "file", "path" to "a.kt")),
                    documents = documents,
                    modifier = Modifier.testTag("left"),
                )
                host(
                    app,
                    ViewDto(
                        id = "v2", workspaceId = "w1", kind = "editor",
                        state = JsonObject(
                            mapOf("mode" to JsonPrimitive("file"), "path" to JsonPrimitive("a.kt")),
                        ),
                    ),
                    documents = documents,
                    modifier = Modifier.testTag("right"),
                )
            }
        }
        waitForIdle()
        // ONE document exists for the path, and both panes hold a reference to it — an edit made
        // through either pane's sink is the same edit.
        documents.update("a.kt", "edited in one pane")
        waitForIdle()
        onAllNodesWithTag("editor_native_input")[0].assertTextEquals("edited in one pane")
        onAllNodesWithTag("editor_native_input")[1].assertTextEquals("edited in one pane")
    }

    // ── Tab titles ──────────────────────────────────────────────────────────────────────────

    @Test
    fun aFileTabIsNamedAfterItsFile() {
        assertEquals("Main.kt", viewTitle(view("editor", mapOf("mode" to "file", "path" to "src/Main.kt"))))
        assertEquals("Files", viewTitle(view("editor", mapOf("mode" to "tree"))))
        assertEquals("Files", viewTitle(view("editor", emptyMap())))
        assertEquals("Changes", viewTitle(view("editor", mapOf("mode" to "diff"))))
    }

    // ── The chat-tap conversion (this used to be dropped on the floor) ───────────────────────

    @Test
    fun aTappedPathInsideTheWorkspaceBecomesAWorkdirRelativePath() {
        assertEquals("src/Main.kt", workspaceOpenPath(FilePathRef("/w/src/Main.kt"), "/w"))
        assertEquals("src/Main.kt", workspaceOpenPath(FilePathRef("src/Main.kt"), "/w"))
    }

    @Test
    fun aTappedPathOutsideTheWorkspaceIsNotOpenable() {
        assertNull(workspaceOpenPath(FilePathRef("/etc/passwd"), "/w"))
    }

    /**
     * End-to-end through the REAL transcript. The conversion above is unit-tested, but nothing
     * proved a click on a rendered file-path ref actually reaches it — and "the parameter was never
     * passed" is exactly the bug this wiring was added to fix.
     *
     * The seeded message body IS the ref (nothing else on the line) so the link's range is known;
     * a plain performClick() lands at the fillMaxWidth row's centre, past the short link's glyphs,
     * so this clicks near the text's top-left instead.
     */
    @Test
    fun aFilePathTappedInAWorkspaceTranscriptOpensThatFile() = runComposeUiTest {
        val app = fakeApp()
        seedChat(app)
        val opened = mutableListOf<Triple<String, Int?, Int?>>()
        setPlatformContent(platform()) {
            host(
                app,
                view("chat", mapOf("sessionId" to "s1")),
                onOpenFile = { p, line, endLine -> opened.add(Triple(p, line, endLine)) },
            )
        }
        onNodeWithText("src/main.kt:42").performTouchInput { click(Offset(4f, 4f)) }
        waitForIdle()
        assertEquals(listOf(Triple<String, Int?, Int?>("src/main.kt", 42, null)), opened.toList())
    }

    // ── NEW (G7): every kind renders under BOTH hosts' chrome ────────────────────────────────

    /**
     * The point of the merge: one host draws every view kind on a pointer machine AND on a touch
     * one. The pointer pass is desktop's shape (PANEL chrome); the touch passes are Android's
     * phone (NONE) and tablet (BAR).
     */
    @Test
    fun everyViewKindRendersUnderEveryChatHeaderMode() {
        val kinds = listOf(
            view("terminal", mapOf("scope" to "workspace", "terminalId" to "main")) to "terminal-w1-main",
            view("editor", mapOf("mode" to "tree")) to "editor_explorer_pane",
            view("editor", mapOf("mode" to "diff")) to "editor_diff_pane",
            view("display", mapOf("displayId" to "d1")) to "view_display_pending",
            view("hologram", emptyMap()) to "view-unknown",
        )
        for ((v, tag) in kinds) {
            for (mode in ChatHeaderMode.entries) {
                runComposeUiTest {
                    val app = fakeApp()
                    setPlatformContent(
                        platform(),
                        pointer = mode == ChatHeaderMode.PANEL,
                        widthClass = if (mode == ChatHeaderMode.NONE) WindowWidthClass.Compact
                        else WindowWidthClass.Expanded,
                    ) { host(app, v, chatHeaderMode = mode) }
                    onNodeWithTag(tag).assertIsDisplayed()
                }
            }
        }
    }

    /** A `chat` view draws its transcript under all three chrome shapes. */
    @Test
    fun aChatViewRendersUnderEveryChatHeaderMode() {
        for (mode in ChatHeaderMode.entries) {
            runComposeUiTest {
                val app = fakeApp()
                seedChat(app)
                setPlatformContent(
                    platform(),
                    pointer = mode == ChatHeaderMode.PANEL,
                    widthClass = if (mode == ChatHeaderMode.NONE) WindowWidthClass.Compact
                    else WindowWidthClass.Expanded,
                ) { host(app, view("chat", mapOf("sessionId" to "s1")), chatHeaderMode = mode) }
                onAllNodesWithTag("view_chat")[0].assertIsDisplayed()
                onNodeWithText("src/main.kt:42").assertIsDisplayed()
            }
        }
    }

    /** The BAR shape is the one that carries Android's own chrome: the workspace overflow. */
    @Test
    fun theBarChromeDrawsTheWorkspaceOverflow() = runComposeUiTest {
        val app = fakeApp()
        seedChat(app)
        setPlatformContent(platform(), pointer = false) {
            host(app, view("chat", mapOf("sessionId" to "s1")), chatHeaderMode = ChatHeaderMode.BAR)
        }
        onNodeWithTag("workspace_overflow").assertIsDisplayed()
        onNodeWithTag("shell_overflow").assertDoesNotExist()
    }

    /** …and the PANEL shape carries desktop's, through the panel's header slots. */
    @Test
    fun thePanelChromeDrawsTheChatHeaderOverflow() = runComposeUiTest {
        val app = fakeApp()
        seedChat(app)
        setPlatformContent(platform()) {
            host(app, view("chat", mapOf("sessionId" to "s1")), chatHeaderMode = ChatHeaderMode.PANEL)
        }
        onNodeWithTag("shell_overflow").assertIsDisplayed()
        onNodeWithTag("workspace_overflow").assertDoesNotExist()
    }

    /** The NONE shape is bare: the host above owns every affordance. */
    @Test
    fun theNoneChromeDrawsNoOverflowAtAll() = runComposeUiTest {
        val app = fakeApp()
        seedChat(app)
        setPlatformContent(platform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            host(app, view("chat", mapOf("sessionId" to "s1")), chatHeaderMode = ChatHeaderMode.NONE)
        }
        onNodeWithTag("workspace_overflow").assertDoesNotExist()
        onNodeWithTag("shell_overflow").assertDoesNotExist()
    }

    @Test
    fun theDefaultChromeFollowsPointerThenWidth() = runComposeUiTest {
        val seen = mutableListOf<ChatHeaderMode>()
        setPlatformContent(platform(), pointer = true) { seen.add(defaultChatHeaderMode()) }
        assertEquals(ChatHeaderMode.PANEL, seen.first())
    }

    @Test
    fun aTouchTabletDefaultsToTheBarAndAPhoneToNone() {
        runComposeUiTest {
            val seen = mutableListOf<ChatHeaderMode>()
            setPlatformContent(platform(), pointer = false, widthClass = WindowWidthClass.Expanded) {
                seen.add(defaultChatHeaderMode())
            }
            assertEquals(ChatHeaderMode.BAR, seen.first())
        }
        runComposeUiTest {
            val seen = mutableListOf<ChatHeaderMode>()
            setPlatformContent(platform(), pointer = false, widthClass = WindowWidthClass.Compact) {
                seen.add(defaultChatHeaderMode())
            }
            assertEquals(ChatHeaderMode.NONE, seen.first())
        }
    }

    private fun seedChat(app: HostStore) {
        app.reduce(
            ServerFrame.SessionAdded(
                SessionInfo(id = "s1", name = "demo", workdir = "/w", agent = "claude"),
            ),
        )
        app.reduce(
            ServerFrame.MessageAppend(
                session = "s1",
                entry = LogEntry(
                    id = "m1", ts = "2026-08-09T00:00:00Z", direction = "outbound",
                    text = "src/main.kt:42",
                ),
            ),
        )
    }
}
