package dev.supermux.ui.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.WorktreeChangesDto
import dev.supermux.net.WorktreeForWorkdirDto
import dev.supermux.net.WorktreeOwnerDto
import dev.supermux.proto.ViewDto
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private fun view(kind: String, state: Map<String, String> = emptyMap()) = ViewDto(
    id = "v1", workspaceId = "w1", kind = kind,
    state = JsonObject(state.mapValues { JsonPrimitive(it.value) }),
)

private fun chatView(sessionId: String) = view("chat", mapOf("sessionId" to sessionId))

@OptIn(ExperimentalTestApi::class)
class CloseViewDialogTest {

    @Test
    fun aChatCloseNamesTheSessionItArchives() = runComposeUiTest {
        setContent {
            CloseViewDialog(
                view = view("chat", mapOf("sessionId" to "s1")),
                sessionNames = mapOf("s1" to "Fix Session Renaming"),
                onConfirm = {}, onDismiss = {},
            )
        }
        onNodeWithText("Close this chat? This archives the session Fix Session Renaming.").assertIsDisplayed()
    }

    @Test
    fun aTerminalCloseSaysItKillsTheTerminal() = runComposeUiTest {
        setContent {
            CloseViewDialog(view = view("terminal", mapOf("terminalId" to "main")), onConfirm = {}, onDismiss = {})
        }
        onNodeWithText("Close this terminal? This stops the terminal main.").assertIsDisplayed()
    }

    @Test
    fun aDisplayCloseSaysItStopsTheStream() = runComposeUiTest {
        setContent {
            CloseViewDialog(view = view("display", mapOf("displayId" to "d1")), onConfirm = {}, onDismiss = {})
        }
        onNodeWithText("Close this display? This stops the stream.").assertIsDisplayed()
    }

    @Test
    fun confirmingCallsOnConfirm() = runComposeUiTest {
        var confirmed = false
        setContent {
            CloseViewDialog(view = view("terminal"), onConfirm = { confirmed = true }, onDismiss = {})
        }
        onNodeWithText("Close").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun theDialogHasExactlyTwoActionsAndNeitherIsAFinishAction() = runComposeUiTest {
        // Spec 9.3: the confirmation is one question with two buttons. It is NOT
        // the Finish flow — no Merge, no Open PR, no Keep, no Discard.
        setContent {
            CloseViewDialog(view = view("chat", mapOf("sessionId" to "s1")), onConfirm = {}, onDismiss = {})
        }
        onNodeWithText("Close").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
        onNodeWithText("Merge locally").assertDoesNotExist()
        onNodeWithText("Open PR").assertDoesNotExist()
        onNodeWithText("Discard").assertDoesNotExist()
    }

    @Test
    fun chatCloseOffersWorktreeDeletionAndRoutesToTheDeletingConfirm() = runComposeUiTest {
        var plain = 0; var deleting = 0; var deletedIds: List<String>? = null
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            CloseViewDialog(
                view = chatView("s1"), onConfirm = { plain++ }, onDismiss = {},
                chatWorkdir = "/w",
                worktreeForWorkdir = { WorktreeForWorkdirDto(id = "s/u", branch = "mux/a", owners = listOf(WorktreeOwnerDto("s1", "S", "live")), changes = WorktreeChangesDto(id = "s/u")) },
                onConfirmDeletingWorktree = { ids -> deleting++; deletedIds = ids },
            )
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_cleanup_checkbox").assertExists(); true }.getOrDefault(false) }
        onNodeWithTag("wt_cleanup_checkbox").performClick()
        onNodeWithText("Close & delete").performClick()
        assertEquals(0, plain); assertEquals(1, deleting)
        assertEquals(listOf("s/u"), deletedIds)
    }
}
