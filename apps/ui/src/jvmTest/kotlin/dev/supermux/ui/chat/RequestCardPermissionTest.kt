package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.PromptRequest
import dev.supermux.proto.PromptRequestOption
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The quoted body reaches [LocalPlatform] (copy to clipboard), so tests supply a fake one. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlatform provides FakePlatform()) { SupermuxTheme { content() } }
}

/** The permission card: header question, de-duplicated body, desktop button order, reject note. */
@OptIn(ExperimentalTestApi::class)
class RequestCardPermissionTest {

    private val bash = PromptRequest(
        requestId = "p1",
        kind = "permission",
        title = "Bash",
        body = "Bash git status --short",
        options = listOf(
            PromptRequestOption(id = "allow_once", label = "Allow once", kind = "allow_once"),
            PromptRequestOption(id = "allow_always", label = "Always allow", kind = "allow_always"),
            PromptRequestOption(id = "reject_once", label = "Reject", kind = "reject_once"),
        ),
    )

    @Test
    fun header_asks_and_body_does_not_repeat_the_tool() = runComposeUiTest {
        setContent { Card { RequestCard(bash, disabled = false, onRespond = {}) } }
        onNodeWithText("Allow Bash?").assertExists()
        onNodeWithTag("request-body:p1").assertTextEquals("git status --short")
    }

    @Test
    fun buttons_read_reject_then_grant_then_allow_once_rightmost() = runComposeUiTest {
        setContent { Card { RequestCard(bash, disabled = false, onRespond = {}) } }
        val reject = onNodeWithTag("request-option:reject_once").getBoundsInRoot()
        val always = onNodeWithTag("request-option:allow_always").getBoundsInRoot()
        val once = onNodeWithTag("request-option:allow_once").getBoundsInRoot()
        assertTrue(reject.right <= always.left && always.right <= once.left, "order: Reject, Always allow, Allow once")
    }

    @Test
    fun allow_once_is_one_tap() = runComposeUiTest {
        var sent: JsonObject? = null
        setContent { Card { RequestCard(bash, disabled = false, onRespond = { sent = it }) } }
        onNodeWithTag("request-option:allow_once").performClick()
        assertEquals("""{"optionId":"allow_once"}""", sent.toString())
    }

    @Test
    fun reject_opens_a_note_then_sends_it() = runComposeUiTest {
        var sent: JsonObject? = null
        setContent { Card { RequestCard(bash, disabled = false, onRespond = { sent = it }) } }
        onNodeWithTag("request-option:reject_once").performClick()
        assertNull(sent)
        onNodeWithTag("request-freetext").performTextInput("too risky")
        onNodeWithTag("request-option:reject_once").performClick()
        assertEquals("""{"optionId":"reject_once","message":"too risky"}""", sent.toString())
    }
}
