package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.PromptRequest
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The question card: paged questions, a submit that waits for every answer, free text as an
 * option row, and the exact answer shape the broker maps back to option labels.
 */
@OptIn(ExperimentalTestApi::class)
class RequestCardQuestionTest {

    private val twoQuestions = PromptRequest(
        requestId = "r1",
        kind = "question",
        title = "Goal",
        body = """[
          {"id":"q1","header":"Goal","prompt":"What should we work on?","multiSelect":false,
           "options":[{"id":"o1","label":"Test features","description":"End to end"},{"id":"o2","label":"Build something"}]},
          {"id":"q2","header":"Features","prompt":"Which features?","multiSelect":true,
           "options":[{"id":"o1","label":"Choice"},{"id":"o2","label":"Multi"},{"id":"o3","label":"Files"}]}
        ]""",
    )

    @Test
    fun a_question_set_is_paged_and_submits_every_answer() = runComposeUiTest {
        var sent: JsonObject? = null
        setContent { SupermuxTheme { RequestCard(twoQuestions, disabled = false, onRespond = { sent = it }) } }

        // The card title is the count; "Goal" appears once, as its step tab — never twice.
        onNodeWithText("2 questions").assertExists()
        assertEquals(1, onAllNodesWithText("Goal").fetchSemanticsNodes().size)
        onNodeWithText("End to end").assertExists()

        onNodeWithTag("request-next").assertIsNotEnabled()
        onNodeWithTag("request-option:o1").performClick()
        onNodeWithTag("request-option:o1").assertIsSelected()
        onNodeWithTag("request-next").assertIsEnabled().performClick()
        waitForIdle()

        onNodeWithText("Which features?").assertExists()
        onNodeWithText("Select all that apply").assertExists()
        onNodeWithTag("request-send").assertIsNotEnabled()
        onNodeWithTag("request-option:o1").performClick()
        onNodeWithTag("request-option:o3").performClick()
        onNodeWithTag("request-send").assertIsEnabled().performClick()

        assertEquals("""{"answers":{"q1":"o1","q2":["o1","o3"]}}""", sent.toString())
    }

    @Test
    fun back_keeps_the_earlier_answer() = runComposeUiTest {
        var sent: JsonObject? = null
        setContent { SupermuxTheme { RequestCard(twoQuestions, disabled = false, onRespond = { sent = it }) } }
        onNodeWithTag("request-option:o2").performClick()
        onNodeWithTag("request-next").performClick()
        waitForIdle()
        onNodeWithTag("request-back").performClick()
        waitForIdle()
        onNodeWithTag("request-option:o2").assertIsSelected()
        assertNull(sent)
    }

    @Test
    fun typing_other_replaces_the_picked_option() = runComposeUiTest {
        var sent: JsonObject? = null
        val req = PromptRequest(
            requestId = "r2",
            kind = "question",
            title = "Branch",
            body = """[{"id":"q1","header":"Branch","prompt":"Name it","allowFreeText":true,
              "options":[{"id":"o1","label":"main"}]}]""",
        )
        setContent { SupermuxTheme { RequestCard(req, disabled = false, onRespond = { sent = it }) } }
        onNodeWithTag("request-option:o1").performClick()
        onNodeWithTag("request-freetext").performTextInput("feature/x")
        onNodeWithTag("request-send").performClick()
        assertEquals("""{"answers":{"q1":"feature/x"}}""", sent.toString())
    }

    @Test
    fun decline_is_one_tap() = runComposeUiTest {
        var sent: JsonObject? = null
        setContent { SupermuxTheme { RequestCard(twoQuestions, disabled = false, onRespond = { sent = it }) } }
        onNodeWithTag("request-decline").performClick()
        assertEquals("""{"decline":true}""", sent.toString())
    }

    @Test
    fun an_unparsed_question_answers_with_an_option_id() = runComposeUiTest {
        var sent: JsonObject? = null
        val req = PromptRequest(
            requestId = "r3",
            kind = "question",
            title = "Pick a color",
            body = "Pick a color",
            options = listOf(
                dev.supermux.proto.PromptRequestOption(id = "red", label = "Red"),
                dev.supermux.proto.PromptRequestOption(id = "blue", label = "Blue"),
            ),
        )
        setContent { SupermuxTheme { RequestCard(req, disabled = false, onRespond = { sent = it }) } }
        // The fallback title would repeat the prompt; the card says "Question" instead.
        onNodeWithText("Question").assertExists()
        onNodeWithTag("request-option:blue").performClick()
        onNodeWithTag("request-send").performClick()
        assertEquals("""{"optionId":"blue"}""", sent.toString())
    }
}
