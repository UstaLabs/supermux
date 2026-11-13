package dev.supermux.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.formatChatTime
import dev.supermux.chat.parseChatTs
import dev.supermux.proto.LimitWaitDto
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** #92: the wait for a usage-limit reset — the session row's line, the strip above the composer. */
@OptIn(ExperimentalTestApi::class)
class LimitWaitUiTest {
    private val resumeAt = "2026-11-08T18:13:00.000Z"
    private val now = parseChatTs("2026-11-08T16:49:00.000Z")!!
    private val hhmm = formatChatTime(parseChatTs(resumeAt)!!, now)
    private val wait = LimitWaitDto(resumeAt = resumeAt, resetsAt = "2026-11-08T18:10:00.000Z", account = "work", accountLabel = "Work Max")

    private fun ComposeUiTest.content(body: @Composable () -> Unit) =
        setPlatformContent(widthClass = WindowWidthClass.Expanded) { SupermuxTheme(appearance = AppearanceMode.DARK) { body() } }

    @Test fun the_row_and_the_strip_say_when_and_on_which_account() {
        assertEquals("Waiting · resumes $hhmm", limitWaitRowText(wait, now))
        assertEquals("Usage limit reached. Resumes automatically at $hhmm", limitWaitTitle(wait, now))
        assertEquals("in 1 h 24 min · on Work Max", limitWaitDetail(wait, now))
        assertEquals("in 3 min · on Work Max", limitWaitDetail(wait, now + 81 * 60_000L))
        assertEquals("any moment now · on Work Max", limitWaitDetail(wait, now + 90 * 60_000L))
    }

    @Test fun no_reset_time_known_says_it_checks_again() {
        val unknown = wait.copy(resetsAt = null)
        assertEquals("Usage limit reached. Checks again at $hhmm", limitWaitTitle(unknown, now))
        assertEquals("Waiting · checks again $hhmm", limitWaitRowText(unknown, now))
    }

    @Test fun the_strip_cancels_and_resumes_now() = runComposeUiTest {
        val calls = mutableListOf<String>()
        content { LimitWaitStrip(wait, nowMs = now, onCancel = { calls += "cancel" }, onResumeNow = { calls += "resume" }) }
        onNodeWithText("Usage limit reached. Resumes automatically at $hhmm").assertExists()
        onNodeWithTag("limit-wait-cancel").performClick()
        onNodeWithTag("limit-wait-resume").performClick()
        assertEquals(listOf("cancel", "resume"), calls)
    }

    // The setting itself is on the Providers & accounts page, under Switch accounts at a limit (#97): ProvidersPageTest.
}
