package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scroll-to-hide chrome: on a chat view as narrow as a phone, scrolling down through the transcript
 * tucks the header and the composer away, scrolling back up brings them back. The trigger is the
 * view's WIDTH, so a wide view (a desktop window) never hides anything.
 */
@OptIn(ExperimentalTestApi::class)
class ChatChromeAutoHideTest {

    private val session =
        SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")

    private val messages = (1..80).map {
        LogEntry(id = "m$it", ts = "2026-01-01T00:%02d:%02dZ".format(it / 60, it % 60), direction = "outbound", text = "message number $it")
    }

    private var hiddenReports = mutableListOf<Boolean>()

    private fun androidx.compose.ui.test.ComposeUiTest.panel(width: Dp) {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            // requiredSize: the test window is narrower than the wide case.
            Box(Modifier.requiredSize(width = width, height = 600.dp)) {
                ChatPanel(
                    session = session,
                    state = ChatState(messages = messages),
                    actions = ChatActions(),
                    draft = "",
                    onDraftChange = {},
                    showHeader = true,
                    onChromeHiddenChange = { hiddenReports.add(it) },
                )
            }
        }
        waitForIdle()
    }

    /** A slow finger drag (no fling): positive [dy] moves the finger down = scrolls UP the transcript. */
    private fun SemanticsNodeInteraction.drag(dy: Float) = performTouchInput {
        down(center)
        repeat(20) { moveBy(Offset(0f, dy / 20)) }
        up()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.dockHeight(): Dp =
        onNodeWithTag("chat_composer_dock").getBoundsInRoot().height

    @Test fun a_narrow_view_hides_the_chrome_on_scroll_down_and_shows_it_on_scroll_up() = runComposeUiTest {
        panel(400.dp)
        val full = dockHeight()
        assertTrue(full > 20.dp, "composer is docked at full height: $full")

        // Start from the bottom (autoscroll put us there), go well up, then read downward — stopping
        // short of the end, which would bring the chrome back.
        onNodeWithTag("chat_body").drag(+600f)
        waitForIdle()
        onNodeWithTag("chat_body").drag(-300f)
        waitForIdle()
        assertEquals(0.dp, dockHeight(), "scrolling down tucks the composer away")
        assertEquals(true, hiddenReports.last())

        onNodeWithTag("chat_body").drag(+300f)
        waitForIdle()
        assertEquals(full, dockHeight(), "scrolling up brings it back")
        assertEquals(false, hiddenReports.last())
    }

    @Test fun nearing_the_end_of_the_transcript_brings_the_chrome_back_early() = runComposeUiTest {
        panel(400.dp)
        val full = dockHeight()
        onNodeWithTag("chat_body").drag(+600f)
        waitForIdle()
        onNodeWithTag("chat_body").drag(-300f)
        waitForIdle()
        assertEquals(0.dp, dockHeight())

        // Creep toward the end until the chrome comes back…
        var steps = 0
        while (dockHeight() == 0.dp && steps++ < 30) {
            onNodeWithTag("chat_body").drag(-40f)
            waitForIdle()
        }
        assertEquals(full, dockHeight(), "nearing the end shows the composer again")
        // …and it came back EARLY: the transcript can still scroll further down.
        val anchor = "message number 75" // on screen once the chrome is back
        val before = onNodeWithText(anchor, useUnmergedTree = true).getBoundsInRoot().top
        onNodeWithTag("chat_body").drag(-100f)
        waitForIdle()
        val after = onNodeWithText(anchor, useUnmergedTree = true).getBoundsInRoot().top
        assertTrue(after < before, "the chrome was back before the end ($before -> $after)")
        assertEquals(full, dockHeight(), "and it stays while you finish scrolling down to the end")
        assertEquals(false, hiddenReports.last())
    }

    @Test fun a_wide_view_never_hides_the_chrome() = runComposeUiTest {
        panel(900.dp)
        val full = dockHeight()
        onNodeWithTag("chat_body").drag(+300f)
        waitForIdle()
        onNodeWithTag("chat_body").drag(-300f)
        waitForIdle()
        assertEquals(full, dockHeight())
        assertTrue(hiddenReports.none { it })
    }
}
