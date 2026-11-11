package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Card #121 round 4: the transcript's one scroll rule, one test per row of the audit table. The reader is either
 * FOLLOWING (the end on screen) or READING (the row he reads held in place); content, heights and the viewport
 * changing never move him out of it, and never with a visible scroll.
 */
@OptIn(ExperimentalTestApi::class)
class ChatScrollRulesTest {
    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")
    private fun ts(n: Int) = "2026-01-01T00:%02d:%02dZ".format(n / 60, n % 60)
    private fun msg(n: Int, text: String = "message number $n of the chat") = LogEntry(id = "m$n", ts = ts(n), direction = "outbound", text = text)

    private var state by mutableStateOf(ChatState())
    private var height by mutableStateOf(600)
    private var draft by mutableStateOf("")

    private fun ComposeUiTest.open() {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                Box(Modifier.requiredSize(width = 400.dp, height = height.dp)) {
                    ChatPanel(session = session, state = state, actions = ChatActions(), draft = draft, onDraftChange = { draft = it }, showHeader = false)
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.bodyTop() = onNodeWithTag("chat_body").fetchSemanticsNode().boundsInRoot.top
    private fun ComposeUiTest.topOf(text: String): Float? =
        onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull()?.let { it.positionInRoot.y - bodyTop() }
    private fun ComposeUiTest.visibleHeight(): Float {
        val body = onNodeWithTag("chat_body").fetchSemanticsNode().boundsInRoot
        val dock = onNodeWithTag("chat_composer_dock").fetchSemanticsNode().boundsInRoot
        return (if (dock.height > 0f) dock.top else body.bottom) - body.top
    }
    private fun ComposeUiTest.onScreen(text: String): Boolean {
        val top = topOf(text) ?: return false
        return top >= 0f && top < visibleHeight()
    }

    /** Runs [change] with the clock held and counts the frames in which [text] is off screen. */
    private fun ComposeUiTest.framesOff(text: String, frames: Int = 40, change: () -> Unit): Int {
        mainClock.autoAdvance = false
        change()
        var off = 0
        repeat(frames) {
            mainClock.advanceTimeByFrame()
            if (!onScreen(text)) off++
        }
        mainClock.autoAdvance = true
        waitForIdle()
        println("ROW-METRIC frames-off-end=$off ($text)")
        return off
    }

    /** Runs [change] and returns how far (px) the row showing [text] moved at most across the frames after it. */
    private fun ComposeUiTest.moved(text: String, frames: Int = 40, change: () -> Unit): Float {
        val start = topOf(text) ?: error("$text is not laid out")
        mainClock.autoAdvance = false
        change()
        var most = 0f
        repeat(frames) {
            mainClock.advanceTimeByFrame()
            topOf(text)?.let { most = maxOf(most, abs(it - start)) }
        }
        mainClock.autoAdvance = true
        waitForIdle()
        println("ROW-METRIC anchor-moved-px=$most ($text)")
        return most
    }

    private fun ComposeUiTest.readUp() {
        repeat(2) { onNodeWithTag("chat_body").performTouchInput { swipeDown() }; waitForIdle() }
    }

    private fun firstOnScreen(test: ComposeUiTest, range: IntRange): Int =
        range.first { with(test) { onScreen("message number $it of") } }

    // Ahmet's report (iPhone, round 3): "it starts at the bottom, then it goes to the TOP, then it scrolls back to
    // the bottom". The saved rows that came in live carry their stream (their row key); the history page's copies of
    // the same messages do not, so every key changed and the list kept only its index, near the top of the longer list.
    private fun live(n: Int) = msg(n).copy(stream = "stream-$n")

    @Test fun history_replacing_the_saved_rows_never_visits_the_top() = runComposeUiTest {
        state = ChatState(messages = (31..60).map(::live), historyLoaded = false)
        open()
        var framesOffEnd = 0
        var topSeen = false
        mainClock.autoAdvance = false
        state = ChatState(messages = (1..60).map { msg(it) }, historyLoaded = true)
        repeat(40) {
            mainClock.advanceTimeByFrame()
            if (!onScreen("message number 60 of")) framesOffEnd++
            if ((1..30).any { onScreen("message number $it of") }) topSeen = true
        }
        mainClock.autoAdvance = true
        waitForIdle()
        println("ROW-METRIC history-replace topSeen=$topSeen frames-off-end=$framesOffEnd")
        assertTrue(!topSeen, "the transcript visited the older rows (the top)")
        assertTrue(framesOffEnd == 0, "the end was off screen for $framesOffEnd frames")
    }

    @Test fun reading_history_replacing_the_saved_rows_keeps_the_row_he_reads() = runComposeUiTest {
        state = ChatState(messages = (31..60).map(::live), historyLoaded = false)
        open()
        readUp()
        val row = firstOnScreen(this, 31..60) + 1
        val d = moved("message number $row of") { state = ChatState(messages = (1..60).map { msg(it) }, historyLoaded = true) }
        assertTrue(d < 1f, "the row he reads moved $d px when the history page replaced the saved rows")
    }

    // Row 7: a new message while following.
    @Test fun following_a_new_message_is_on_screen_at_once() = runComposeUiTest {
        state = ChatState(messages = (1..40).map { msg(it) })
        open()
        val off = framesOff("message number 41 of") { state = state.copy(messages = state.messages + msg(41)) }
        assertTrue(off == 0, "the new message was off screen for $off frames: an animated follow")
    }

    // Row 7: a new message while reading.
    @Test fun reading_a_new_message_moves_nothing() = runComposeUiTest {
        state = ChatState(messages = (1..40).map { msg(it) })
        open()
        readUp()
        val row = firstOnScreen(this, 1..40) + 1
        val d = moved("message number $row of") { state = state.copy(messages = state.messages + msg(41)) }
        assertTrue(d < 1f, "the row he reads moved $d px")
    }

    // Row 5: the history page lands while reading.
    @Test fun reading_the_history_landing_moves_nothing() = runComposeUiTest {
        state = ChatState(messages = (31..60).map { msg(it) }, historyLoaded = false)
        open()
        readUp()
        val row = firstOnScreen(this, 31..60) + 1
        val d = moved("message number $row of") { state = ChatState(messages = (1..60).map { msg(it) }, historyLoaded = true) }
        assertTrue(d < 1f, "the row he reads moved $d px")
    }

    // Row 9: the keyboard opens while following (the viewport shrinks).
    @Test fun following_the_keyboard_opening_keeps_the_end_on_screen() = runComposeUiTest {
        state = ChatState(messages = (1..40).map { msg(it) })
        open()
        val off = framesOff("message number 40 of") { height = 350 }
        assertTrue(off == 0, "the last message was hidden for $off frames when the viewport shrank")
    }

    // Row 9: the keyboard opens while reading.
    @Test fun reading_the_keyboard_opening_keeps_the_row_he_reads() = runComposeUiTest {
        state = ChatState(messages = (1..40).map { msg(it) })
        open()
        readUp()
        val row = firstOnScreen(this, 1..40) + 1
        val d = moved("message number $row of") { height = 450 }
        assertTrue(d < 1f, "the row he reads moved $d px")
    }

    // Row 10: the composer grows (several lines typed) while following.
    @Test fun following_the_composer_growing_keeps_the_end_on_screen() = runComposeUiTest {
        state = ChatState(messages = (1..40).map { msg(it) })
        open()
        val off = framesOff("message number 40 of") { draft = (1..6).joinToString("\n") { "line $it" } }
        assertTrue(off == 0, "the last message was hidden for $off frames when the composer grew")
    }

    // Row 12: the last row measures taller later (a card, an image) while following.
    @Test fun following_the_last_row_growing_keeps_its_end_on_screen() = runComposeUiTest {
        state = ChatState(messages = (1..39).map { msg(it) } + msg(40, "short reply"))
        open()
        val grown = (1..15).joinToString("\n\n") { "paragraph $it of the grown reply" } + "\n\nthe end of the grown reply"
        val off = framesOff("the end of the grown reply") {
            state = state.copy(messages = (1..39).map { msg(it) } + msg(40, grown))
        }
        assertTrue(off == 0, "the end was off screen for $off frames")
    }

    // Row 12: rows measuring taller while reading: one above the screen, and the top row he reads.
    @Test fun reading_rows_growing_keep_the_row_he_reads() = runComposeUiTest {
        state = ChatState(messages = (1..40).map { msg(it) })
        open()
        readUp()
        val top = firstOnScreen(this, 1..40) + 1
        val grown = (1..6).joinToString("\n\n") { "paragraph $it of the grown row" }
        fun growing(id: Int) = state.copy(messages = state.messages.map { if (it.id == "m$id") it.copy(text = grown) else it })
        val above = moved("message number $top of") { state = growing(top - 3) }
        assertTrue(above < 1f, "the row he reads moved $above px when a row above the screen grew")
        val itself = moved("message number $top of") { state = growing(top) }
        assertTrue(itself < 1f, "the row he reads moved $itself px when it grew itself")
    }
}
