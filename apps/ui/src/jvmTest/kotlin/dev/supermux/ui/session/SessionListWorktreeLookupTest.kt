package dev.supermux.ui.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import dev.supermux.net.WorktreeChangesDto
import dev.supermux.net.WorktreeDeleteResultDto
import dev.supermux.net.WorktreeForWorkdirDto
import dev.supermux.net.WorktreeOwnerDto
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Final review I-4: the archive dialog asks the OWNING host — it names the workspace it archives. */
@OptIn(ExperimentalTestApi::class)
class SessionListWorktreeLookupTest {
    @Test fun archiveWorkspaceDialogLooksUpTheWorktreeByWorkspace() = runComposeUiTest {
        val asked = mutableListOf<Pair<String, String>>()
        val view = ViewDto(id = "v1", workspaceId = "w1", kind = "chat", state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))))
        val ws = WorkspaceDto(
            id = "w1", name = "solo", workdir = "/home/u/p", views = listOf(view),
            layout = LayoutNodeDto.Group(id = "g", viewIds = listOf("v1")),
        )
        setPlatformContent(platform = FakePlatform()) {
            CompositionLocalProvider(
                // Pointer row with the overflow menu (the touch swipe path has pre-existing test failures).
                LocalInputMode provides InputMode.Pointer,
                LocalContextMenuAvailable provides false,
            ) {
                SessionListScreen(
                    mode = SessionListMode.Fleet,
                    workspaces = listOf(ws),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    openWorkspaceByWorkspaceId = false,
                    actions = SessionListActions(
                        worktreeForWorkspaceWorkdir = { wid, wd -> synchronized(asked) { asked += wid to wd }; null },
                        worktreeForSessionWorkdir = { _, _ -> error("a workspace archive must not look up by session") },
                    ),
                )
            }
        }
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Archive").performClick()
        waitUntil(timeoutMillis = 5_000) { synchronized(asked) { asked.isNotEmpty() } }
        assertEquals(listOf("w1" to "/home/u/p"), synchronized(asked) { asked.toList() })
    }

    // Final review m1: "Archive & delete" closes the dialog at once and hands the (possibly long)
    // call to the store; the notice arrives through the callback, whatever the screen did meanwhile.
    @Test fun archiveAndDeleteDismissesFirstAndReportsThroughTheCallback() = runComposeUiTest {
        var pending: ((List<WorktreeDeleteResultDto>?) -> Unit)? = null
        var sent: Pair<String, List<String>>? = null
        val platform = FakePlatform()
        val view = ViewDto(id = "v1", workspaceId = "w1", kind = "chat", state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))))
        val ws = WorkspaceDto(
            id = "w1", name = "solo", workdir = "/home/u/p", views = listOf(view),
            layout = LayoutNodeDto.Group(id = "g", viewIds = listOf("v1")),
        )
        setPlatformContent(platform = platform) {
            CompositionLocalProvider(LocalInputMode provides InputMode.Pointer, LocalContextMenuAvailable provides false) {
                SessionListScreen(
                    mode = SessionListMode.Fleet,
                    workspaces = listOf(ws),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    openWorkspaceByWorkspaceId = false,
                    actions = SessionListActions(
                        worktreeForWorkspaceWorkdir = { _, _ ->
                            WorktreeForWorkdirDto(id = "s/u", branch = "mux/a", owners = listOf(WorktreeOwnerDto("s1", "S", "live")),
                                changes = WorktreeChangesDto(id = "s/u", unmergedKnown = true))
                        },
                        archiveWorkspaceAndDeleteWorktree = { id, ids, onDone -> sent = id to ids; pending = onDone },
                    ),
                )
            }
        }
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Archive").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_cleanup_checkbox").assertExists(); true }.getOrDefault(false) }
        onNodeWithTag("wt_cleanup_checkbox").performClick()
        onNodeWithText("Archive & delete").performClick()
        waitForIdle()
        assertEquals("w1" to listOf("s/u"), sent)
        onNodeWithText("Archive workspace?").assertDoesNotExist()   // gone while the call is still running
        pending!!(null)
        assertEquals(1, platform.notices.shown.size)
        assert(platform.notices.shown.single().startsWith("Couldn't archive: "))
    }
}
