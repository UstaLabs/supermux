package dev.supermux.desktop.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.ViewDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private fun view(kind: String, state: Map<String, String> = emptyMap()) = ViewDto(
    id = "v1", workspaceId = "w1", kind = kind,
    state = JsonObject(state.mapValues { JsonPrimitive(it.value) }),
)

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
}
