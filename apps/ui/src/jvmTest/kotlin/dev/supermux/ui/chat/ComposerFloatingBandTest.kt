package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * D4 (from the H4 iPad pass): on a TOUCH tablet the chat transcript rendered through and BELOW the
 * composer, over the git strip.
 *
 * The layout branch is [dev.supermux.ui.adaptive.LocalPointerAvailable], not width, and the
 * `ComposerFooter` is gated on WIDTH — so a trackpad-less iPad (and an Android tablet) got the
 * combination nobody designed: the FLOATING composer, which overlaps the transcript, plus a
 * transparent ~50dp strip hanging below the glass card. Rows scrolling into that band looked like
 * messages sitting under the composer. An iPad WITH a trackpad never showed it, because a pointer
 * docks the composer, which cannot overlap anything.
 *
 * These pin the fix structurally rather than by pixel: the floating cluster is two parts, and the
 * lower one — the strip — is full-bleed and flush to the bottom of the cluster, so there is NO
 * transparent band under the glass card for the transcript to show through. (The strip's own
 * `background(surfaceContainerLow)`, the panel's colour, is what makes flush mean opaque; a
 * geometry gap is the part a future edit is most likely to reintroduce, and it is what the old
 * `padding(horizontal = 8.dp, vertical = 6.dp)` on the whole cluster produced.)
 */
@OptIn(ExperimentalTestApi::class)
class ComposerFloatingBandTest {

    private val session =
        SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")

    private fun chatMessage(id: String, text: String) =
        LogEntry(id = id, ts = "2026-01-01T00:00:01Z", direction = "outbound", text = text)

    private fun close(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp) =
        abs((a - b).value) < 1f

    @Test fun on_a_touch_tablet_the_strip_below_the_glass_composer_is_full_bleed_and_flush() =
        runComposeUiTest {
            setPlatformContent(
                pointer = false,
                widthClass = WindowWidthClass.Expanded,
                inputMode = InputMode.Touch,
            ) {
                ChatPanel(
                    session = session,
                    state = ChatState(messages = listOf(chatMessage("m1", "hello"))),
                    actions = ChatActions(),
                    draft = "",
                    onDraftChange = {},
                    showHeader = false,
                )
            }
            waitForIdle()

            val body = onNodeWithTag("chat_body").getBoundsInRoot()
            val glass = onNodeWithTag("composer_float_glass").getBoundsInRoot()
            val strip = onNodeWithTag("composer_float_strip").getBoundsInRoot()

            // Full-bleed: the strip spans the panel, so nothing shows past it on either side.
            assertTrue(close(strip.left, body.left), "strip.left ${strip.left} != body.left ${body.left}")
            assertTrue(close(strip.right, body.right), "strip.right ${strip.right} != body.right ${body.right}")
            // Flush to the bottom of the cluster: no transparent sliver under the git strip.
            assertTrue(close(strip.bottom, body.bottom), "strip.bottom ${strip.bottom} != body.bottom ${body.bottom}")
            // And it starts exactly where the glass card ends: no transparent gap between them.
            assertTrue(close(strip.top, glass.bottom), "strip.top ${strip.top} != glass.bottom ${glass.bottom}")
            assertTrue(strip.height > 0.dp, "strip has no height")
        }

    /** A phone keeps the footer-less floating composer it always had — no strip to be flush with. */
    @Test fun a_compact_window_has_no_strip_at_all() = runComposeUiTest {
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            ChatPanel(
                session = session,
                state = ChatState(messages = listOf(chatMessage("m1", "hello"))),
                actions = ChatActions(),
                draft = "",
                onDraftChange = {},
                showHeader = false,
            )
        }
        waitForIdle()
        onNodeWithTag("composer_float_glass").assertExists()
        onNodeWithTag("composer_float_strip").assertDoesNotExist()
    }

    /** A pointer host DOCKS the composer, so neither floating node exists — the arrangement that
     *  never had the defect must keep not having the nodes that fix it. */
    @Test fun a_pointer_host_still_docks_the_composer() = runComposeUiTest {
        setPlatformContent(pointer = true, widthClass = WindowWidthClass.Expanded) {
            ChatPanel(
                session = session,
                state = ChatState(messages = listOf(chatMessage("m1", "hello"))),
                actions = ChatActions(),
                draft = "",
                onDraftChange = {},
                showHeader = false,
            )
        }
        waitForIdle()
        onNodeWithTag("composer_float_glass").assertDoesNotExist()
        onNodeWithTag("composer_float_strip").assertDoesNotExist()
    }
}
