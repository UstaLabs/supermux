// A phone saves a file from its top bar (#84).
//
// The wide layouts carry a file's per-file controls — Save and the Markdown preview toggle — on the
// file's TAB (WorkspaceFileTab), and `FilePane` has no action row of its own. A phone has no tab
// strip: its tabs are cards behind one count button. So on a phone an edited file had no way to be
// saved at all. These drive the real shell at Compact width over a fake broker disk.
package dev.supermux.ui.shell

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.toDto
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PhoneFileSaveTest {

    /** The broker's side of the file: what `fs/read` serves and every `fs/write` it was sent. */
    private class Disk(var text: String, val writeOk: Boolean = true) {
        val writes = mutableListOf<Pair<String, String>>()
    }

    /** One workspace holding the session's chat and one file view on [path]. */
    private fun workspaceWithFile(disk: Disk, path: String = "a.txt"): HostStore = testHostStore { req ->
        val at = req.url.encodedPath
        when {
            req.method == HttpMethod.Get && at.endsWith("/workspaces/w1/fs/read") -> respond(disk.text, HttpStatusCode.OK)
            req.method == HttpMethod.Put && at.endsWith("/workspaces/w1/fs/write") -> {
                val body = req.body.toByteArray().decodeToString()
                disk.writes += (req.url.parameters["path"] ?: "") to body
                if (disk.writeOk) {
                    disk.text = body
                    respond("{}", HttpStatusCode.OK)
                } else {
                    respond("disk full", HttpStatusCode.InternalServerError)
                }
            }
            else -> respond("{}", HttpStatusCode.OK)
        }
    }.also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
                workspaces = listOf(
                    WorkspaceDto(
                        id = "w1", name = "proj", workdir = "/w",
                        primarySessionId = "s1",
                        layout = LayoutNode.Group("g1", listOf("c1", "f1"), "f1").toDto(),
                        activeViewId = "f1",
                        views = listOf(
                            ViewDto(
                                id = "c1", workspaceId = "w1", kind = "chat",
                                state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))),
                            ),
                            ViewDto(
                                id = "f1", workspaceId = "w1", kind = "editor",
                                state = JsonObject(mapOf("mode" to JsonPrimitive("file"), "path" to JsonPrimitive(path))),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    /** The phone shell, on the file's tab once its editor has loaded. */
    private fun ComposeUiTest.openFileOnPhone(app: HostStore, platform: FakePlatform = FakePlatform()) {
        val ui = ShellUiState().apply { selectedId = "s1" }
        setPlatformContent(platform = platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui)
        }
        waitForIdle()
        // Opening the session fronts its chat; the user then picks the file's tab.
        app.setActiveView("w1", "f1")
        waitForIdle()
        waitUntil("the file's editor is up", timeoutMillis = 10_000) {
            onAllNodes(editorField()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The file editor's hidden input field (the chat's composer has SetText too, hence the scope). */
    private fun editorField(): SemanticsMatcher =
        hasSetTextAction() and
            !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and
            hasAnyAncestor(hasTestTag("editor_file_pane"))

    private fun ComposeUiTest.type(text: String) {
        onNode(editorField()).requestFocus()
        waitForIdle()
        onNode(editorField()).performTextInput(text)
        waitForIdle()
    }

    @Test fun a_phone_saves_an_edited_file_from_its_top_bar() = runComposeUiTest {
        val disk = Disk("hello\n")
        openFileOnPhone(workspaceWithFile(disk))

        onNodeWithTag("phone_workspace_bar").assertIsDisplayed()
        // A clean file's bar is the plain one: nothing to save, nothing marked.
        onNodeWithTag("phone_file_save").assertDoesNotExist()
        onNodeWithTag("phone_file_unsaved").assertDoesNotExist()
        // Not Markdown, so no preview toggle either.
        onNodeWithTag("phone_file_preview").assertDoesNotExist()

        type("X")

        onNodeWithTag("phone_file_unsaved").assertIsDisplayed()
        onNodeWithTag("phone_file_save").assertIsDisplayed().performClick()
        // The write is a broker round trip; once its answer lands the bar is plain again.
        waitUntil("the file is written", timeoutMillis = 10_000) { disk.writes.isNotEmpty() }
        waitUntil("Save goes once the write lands", timeoutMillis = 10_000) {
            onAllNodesWithTag("phone_file_save").fetchSemanticsNodes().isEmpty()
        }

        assertEquals(listOf("a.txt" to "Xhello\n"), disk.writes)
        onNodeWithTag("phone_file_unsaved").assertDoesNotExist()
    }

    @Test fun a_failed_save_says_so_and_keeps_the_file_unsaved() = runComposeUiTest {
        val disk = Disk("hello\n", writeOk = false)
        val platform = FakePlatform()
        openFileOnPhone(workspaceWithFile(disk), platform)

        type("X")
        onNodeWithTag("phone_file_save").performClick()
        waitUntil("the failure is reported", timeoutMillis = 10_000) { platform.notices.shown.isNotEmpty() }
        waitForIdle()

        assertEquals(listOf("Couldn't save a.txt"), platform.notices.shown)
        // The edit is still unsaved, so Save stays to try again.
        onNodeWithTag("phone_file_unsaved").assertIsDisplayed()
        onNodeWithTag("phone_file_save").assertIsDisplayed()
    }

    @Test fun a_markdown_file_toggles_its_preview_from_the_phone_bar() = runComposeUiTest {
        openFileOnPhone(workspaceWithFile(Disk("# Notes\n\nSome text.\n"), path = "README.md"))
        onNodeWithTag("editor_preview").assertDoesNotExist()

        onNodeWithTag("phone_file_preview").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag("editor_preview").assertIsDisplayed()

        onNodeWithTag("phone_file_preview").performClick()
        waitForIdle()
        onNodeWithTag("editor_preview").assertDoesNotExist()
    }

    @Test fun an_unsaved_file_is_marked_on_its_card_in_the_tab_switcher() = runComposeUiTest {
        openFileOnPhone(workspaceWithFile(Disk("hello\n")))
        type("X")

        // The bar's count button (the hidden chat tab's header draws one too).
        onNode(hasTestTag("phone_tabs_button") and hasAnyAncestor(hasTestTag("phone_workspace_bar"))).performClick()
        waitForIdle()
        onNodeWithTag("phone_tab_switcher").assertIsDisplayed()

        onNodeWithTag("tab-unsaved-f1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("tab-unsaved-c1", useUnmergedTree = true).assertDoesNotExist()
    }
}
