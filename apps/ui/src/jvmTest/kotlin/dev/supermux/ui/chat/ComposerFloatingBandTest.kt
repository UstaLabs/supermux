package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * Since 2f7fbecf the composer is DOCKED under the transcript on every host — the floating glass
 * card and its strip no longer exist on any input — so the defect cannot recur by geometry: the
 * transcript's viewport ends where the dock begins. These now pin THAT on the touch tablet and the
 * phone (the arrangements that used to float), the way the pointer case always did. The history
 * below is kept because it says what a future "float it again on touch" change must not bring back.
 *
 * ── Original notes ──
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
 *
 * ⚠️ WHAT THESE CANNOT SEE: `runComposeUiTest` renders with ZERO window insets, so the ime/nav-bar
 * band under the strip has no height here and "flush to the bottom of the cluster" is trivially
 * satisfied whether the strip's background is painted inside or outside the inset padding. The
 * second half of the fix — background BEFORE `windowInsetsPadding`, so the opaque surface reaches
 * the real bottom of the window on a device that has a nav bar or a raised keyboard — is invisible
 * to this test and is pinned only by the comment at the call site. Do not read a green run here as
 * proof the inset band is opaque on a phone.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerFloatingBandTest {

    private val session =
        SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")

    private fun chatMessage(id: String, text: String) =
        LogEntry(id = id, ts = "2026-01-01T00:00:01Z", direction = "outbound", text = text)

    private fun close(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp) =
        abs((a - b).value) < 1f

    /** Touch tablet and phone share the docked layout: the composer dock spans the body's width,
     *  sits flush on its bottom, and the transcript's rows stay above it. */
    private fun androidx.compose.ui.test.ComposeUiTest.assertDockedUnderTheTranscript() {
        onNodeWithTag("composer_float_glass").assertDoesNotExist()
        onNodeWithTag("composer_float_strip").assertDoesNotExist()
        val body = onNodeWithTag("chat_body").getBoundsInRoot()
        val dock = onNodeWithTag("chat_composer_dock").getBoundsInRoot()
        val row = onNodeWithText("hello").getBoundsInRoot()
        assertTrue(close(dock.left, body.left), "dock.left ${dock.left} != body.left ${body.left}")
        assertTrue(close(dock.right, body.right), "dock.right ${dock.right} != body.right ${body.right}")
        assertTrue(close(dock.bottom, body.bottom), "dock.bottom ${dock.bottom} != body.bottom ${body.bottom}")
        assertTrue(dock.height > 0.dp, "dock has no height")
        // Docked, not floating: nothing of the transcript renders under the composer.
        assertTrue(row.bottom <= dock.top, "transcript row ${row.bottom} reaches under the dock ${dock.top}")
    }

    @Test fun on_a_touch_tablet_the_composer_is_docked_under_the_transcript() =
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
            assertDockedUnderTheTranscript()
        }

    /** A phone gets the same docked composer — no floating card, no strip. */
    @Test fun a_compact_window_docks_the_composer_too() = runComposeUiTest {
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
        assertDockedUnderTheTranscript()
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
