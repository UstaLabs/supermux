package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.editor.DocumentStore
import dev.supermux.desktop.editor.KcefState
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.net.BrokerApi
import dev.supermux.proto.ViewDto
import dev.supermux.ui.FilePathRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * ViewHost on disk takes [DesktopAppState] + drafts (Phase 3). The plan's simplified
 * signature is adapted here — follow the disk.
 *
 * Workspace terminals inject a pure-Compose stand-in: SwingPanel/JediTerm cannot
 * be hosted under runComposeUiTest (same pattern as SessionDetailTest's nativePanelContent).
 *
 * The `file` pane injects a KCEF state instead: an Error state makes [EditorSurface] draw its
 * native BasicTextField fallback, which is pure Compose AND shows the document's text — so a
 * test can read the buffer a pane is displaying without booting Chromium.
 */
@OptIn(ExperimentalTestApi::class)
class ViewHostTest {

    private fun fakeApp(): DesktopAppState {
        val engine = MockEngine { req ->
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = CoroutineScope(Dispatchers.Default),
            connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    /** KCEF is never Ready in a test, so no engine is ever built. Error picks the visible fallback. */
    private val noKcef = MutableStateFlow<KcefState>(KcefState.Error("no chromium under test"))

    /**
     * Unconfined so a non-suspending fsRead resolves inside [DocumentStore.open] itself — the
     * document is present by the time the pane reads it back.
     */
    private fun store(content: String = "hello from the store") = DocumentStore(
        fsRead = { Result.success(content) },
        fsWrite = { _, _ -> true },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun aWorkspaceTerminalBuildsTheTerminalWidget() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("terminal", mapOf("scope" to "workspace", "terminalId" to "main")),
                workspaceId = "w1",
                workdir = "/w",
                app = app,
                drafts = mutableStateMapOf(),
                workspaceTerminalContent = { _, mod ->
                    Box(mod.fillMaxSize()) { Text("term-stand-in") }
                },
            )
        }
        onNodeWithTag("terminal-w1-main").assertIsDisplayed()
    }

    @Test
    fun anUnknownKindDrawsAHintRatherThanCrashing() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("hologram", emptyMap()),
                workspaceId = "w1",
                workdir = "/w",
                app = app,
                drafts = mutableStateMapOf(),
            )
        }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }

    @Test
    fun aChatViewWithNoSessionIdDrawsTheHint() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("chat", emptyMap()),
                workspaceId = "w1",
                workdir = "/w",
                app = app,
                drafts = mutableStateMapOf(),
            )
        }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }

    // ── The editor is three panes, chosen by `mode` (spec §7.2) ──────────────────────────────

    @Test
    fun modeTreeDrawsTheExplorerPane() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("editor", mapOf("mode" to "tree")),
                workspaceId = "w1",
                workdir = "/some/dir",
                app = app,
                drafts = mutableStateMapOf(),
                editorKcefState = noKcef,
                editorEnsureInit = {},
            )
        }
        onNodeWithTag("editor-/some/dir").assertIsDisplayed()
        onNodeWithTag("editor_explorer_pane").assertIsDisplayed()
        onNodeWithTag("editor_tree").assertIsDisplayed()
    }

    /** An `editor` row written before this phase has no mode at all. It must keep working. */
    @Test
    fun anEditorViewWithNoModeStillDrawsTheTree() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("editor", emptyMap()),
                workspaceId = "w1",
                workdir = "/some/dir",
                app = app,
                drafts = mutableStateMapOf(),
                editorKcefState = noKcef,
                editorEnsureInit = {},
            )
        }
        onNodeWithTag("editor_explorer_pane").assertIsDisplayed()
    }

    @Test
    fun anUnknownModeFallsBackToTheTree() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("editor", mapOf("mode" to "holodeck")),
                workspaceId = "w1",
                workdir = "/some/dir",
                app = app,
                drafts = mutableStateMapOf(),
                editorKcefState = noKcef,
                editorEnsureInit = {},
            )
        }
        onNodeWithTag("editor_explorer_pane").assertIsDisplayed()
    }

    @Test
    fun modeFileDrawsOneDocumentFromTheStore() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("editor", mapOf("mode" to "file", "path" to "src/Main.kt")),
                workspaceId = "w1",
                workdir = "/some/dir",
                app = app,
                drafts = mutableStateMapOf(),
                documents = store("fun main() {}"),
                editorKcefState = noKcef,
                editorEnsureInit = {},
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
        setContent {
            ViewHost(
                view = view("editor", mapOf("mode" to "file")),
                workspaceId = "w1",
                workdir = "/some/dir",
                app = app,
                drafts = mutableStateMapOf(),
                documents = store(),
                editorKcefState = noKcef,
                editorEnsureInit = {},
            )
        }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }

    @Test
    fun modeDiffDrawsTheDiffPane() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("editor", mapOf("mode" to "diff")),
                workspaceId = "w1",
                workdir = "/some/dir",
                app = app,
                drafts = mutableStateMapOf(),
                editorKcefState = noKcef,
                editorEnsureInit = {},
            )
        }
        onNodeWithTag("editor_diff_pane").assertIsDisplayed()
        onNodeWithTag("diff_view").assertIsDisplayed()
    }

    /** A workspace with no chat has no LSP — the FILE pane says so, quietly. */
    @Test
    fun aFilePaneInAChatlessWorkspaceSaysCodeIntelligenceIsOff() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("editor", mapOf("mode" to "file", "path" to "a.txt")),
                workspaceId = "w1",
                workdir = "/some/dir",
                app = app,
                drafts = mutableStateMapOf(),
                documents = store(),
                editorKcefState = noKcef,
                editorEnsureInit = {},
            )
        }
        onNodeWithTag("editor-no-lsp").assertIsDisplayed()
    }

    // ── One store, two panes: the buffer is shared (spec §18) ────────────────────────────────

    @Test
    fun twoFilePanesOnOnePathShowOneBuffer() = runComposeUiTest {
        val app = fakeApp()
        val documents = store("original text")
        setContent {
            Box(Modifier.fillMaxSize()) {
                ViewHost(
                    view = view("editor", mapOf("mode" to "file", "path" to "a.kt")),
                    workspaceId = "w1",
                    workdir = "/w",
                    app = app,
                    drafts = mutableStateMapOf(),
                    documents = documents,
                    editorKcefState = noKcef,
                    editorEnsureInit = {},
                    modifier = Modifier.testTag("left"),
                )
                ViewHost(
                    view = ViewDto(
                        id = "v2", workspaceId = "w1", kind = "editor",
                        state = JsonObject(mapOf("mode" to JsonPrimitive("file"), "path" to JsonPrimitive("a.kt"))),
                    ),
                    workspaceId = "w1",
                    workdir = "/w",
                    app = app,
                    drafts = mutableStateMapOf(),
                    documents = documents,
                    editorKcefState = noKcef,
                    editorEnsureInit = {},
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
        assertEquals("Explorer", viewTitle(view("editor", mapOf("mode" to "tree"))))
        assertEquals("Explorer", viewTitle(view("editor", emptyMap())))
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
}
