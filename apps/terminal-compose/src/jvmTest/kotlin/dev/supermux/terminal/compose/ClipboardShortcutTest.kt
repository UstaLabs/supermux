package dev.supermux.terminal.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import dev.supermux.terminal.TerminalPoint
import dev.supermux.terminal.TerminalSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Copy and paste the way a user reaches them: the keyboard shortcuts, the right-click menu, the
 * touch bubble, multi-click selection — and what the PROGRAM sees of each (nothing, or exactly the
 * paste).
 */
@OptIn(ExperimentalTestApi::class)
class ClipboardShortcutTest {

    @OptIn(InternalComposeUiApi::class)
    private fun key(key: Key, type: KeyEventType, ctrl: Boolean, shift: Boolean, meta: Boolean) = KeyEvent(
        key = key,
        type = type,
        codePoint = 0,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isAltPressed = false,
        isShiftPressed = shift,
    )

    /** A chord's press and release; nothing is awaited, the caller waits for what it expects. */
    private fun ComposeUiTest.chord(key: Key, ctrl: Boolean = false, shift: Boolean = false, meta: Boolean = false) {
        onNodeWithTag(INPUT_TAG).performKeyPress(key(key, KeyEventType.KeyDown, ctrl, shift, meta))
        onNodeWithTag(INPUT_TAG).performKeyPress(key(key, KeyEventType.KeyUp, ctrl, shift, meta))
        waitForIdle()
    }

    private fun ComposeUiTest.select(fixture: InputFixture, selection: TerminalSelection) {
        fixture.session.select(selection)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selection() != null }
        waitForIdle()
    }

    @Test fun cmdVPastesTheClipboardAsOnePasteAndTheChordNeverReachesTheProgram() = terminalInputTest { fixture ->
        fixture.clipboard.content = "echo hi"
        chord(Key.V, meta = true)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.engine.pasteCalls.get() == 1 }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorded() == "echo hi" }
        assertEquals(0, fixture.engine.keyCalls.get(), "Cmd+V (or its release) was sent as a key")
    }

    @Test fun ctrlShiftVPastesToo() = terminalInputTest { fixture ->
        fixture.clipboard.content = "ls"
        chord(Key.V, ctrl = true, shift = true)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorded() == "ls" }
        assertEquals(0, fixture.engine.keyCalls.get())
    }

    @Test fun anUnsafeClipboardPasteAsksAndGoesOnlyOnceConfirmed() = terminalInputTest { fixture ->
        fixture.clipboard.content = "ls\nrm x"
        chord(Key.V, meta = true)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) {
            onAllNodes(androidx.compose.ui.test.hasTestTag(TerminalMenuTags.PASTE_CONFIRM)).fetchSemanticsNodes().isNotEmpty()
        }
        fixture.assertSilence("a paste waiting for confirmation")

        onNodeWithTag(TerminalMenuTags.PASTE_OK).performClick()
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorded().isNotEmpty() }
        waitForIdle()
        assertEquals("ls\rrm x", fixture.recorded())
    }

    @Test fun aCancelledUnsafePasteSendsNothing() = terminalInputTest { fixture ->
        fixture.clipboard.content = "ls\nrm x"
        chord(Key.V, meta = true)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) {
            onAllNodes(androidx.compose.ui.test.hasTestTag(TerminalMenuTags.PASTE_CANCEL)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(TerminalMenuTags.PASTE_CANCEL).performClick()
        waitForIdle()
        fixture.assertSilence("a cancelled paste")
    }

    @Test fun cmdCCopiesTheSelectionAndSendsNothing() = terminalInputTest { fixture ->
        fixture.feed("alpha beta")
        waitForIdle()
        select(fixture, TerminalSelection(TerminalPoint(0, 0), TerminalPoint(0, 4)))

        chord(Key.C, meta = true)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.clipboard.writes.isNotEmpty() }
        assertEquals(listOf("alpha"), fixture.clipboard.writes)
        assertEquals(0, fixture.engine.keyCalls.get())
        fixture.assertSilence("a copy")
    }

    @Test fun ctrlCCopiesWhileSomethingIsSelectedAndInterruptsOnceItIsNot() = terminalInputTest { fixture ->
        fixture.feed("alpha beta")
        waitForIdle()
        select(fixture, TerminalSelection(TerminalPoint(0, 6), TerminalPoint(0, 9)))

        chord(Key.C, ctrl = true)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.clipboard.writes.isNotEmpty() }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selection() == null }
        assertEquals(listOf("beta"), fixture.clipboard.writes)
        fixture.assertSilence("Ctrl+C over a selection")

        // Nothing selected any more: the same chord is the program's interrupt again.
        chord(Key.C, ctrl = true)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorded().isNotEmpty() }
        assertEquals(listOf(0x03.toByte()), fixture.recorder.bytes().toList())
    }

    @Test fun cmdASelectsEverything() = terminalInputTest { fixture ->
        fixture.feed("one\r\ntwo")
        waitForIdle()
        chord(Key.A, meta = true)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selection() != null }
        assertEquals("one\ntwo", fixture.selectedText())
        fixture.assertSilence("select all")
    }

    @Test fun aDoubleClickSelectsTheWordAndATripleClickTheRow() = terminalInputTest { fixture ->
        fixture.feed("alpha beta gamma")
        waitForIdle()
        onNodeWithTag(INPUT_TAG).performMouseInput {
            click(fixture.centreOf(7, 0))
            advanceEventTime(50)
            click(fixture.centreOf(7, 0))
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText() == "beta" }

        onNodeWithTag(INPUT_TAG).performMouseInput {
            advanceEventTime(50)
            click(fixture.centreOf(7, 0))
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText() == "alpha beta gamma" }
        fixture.assertSilence("multi-click selection")
    }

    @Test fun aRightClickOpensTheMenuWhoseCopyCopiesTheSelection() = terminalInputTest { fixture ->
        fixture.feed("alpha beta")
        waitForIdle()
        select(fixture, TerminalSelection(TerminalPoint(0, 0), TerminalPoint(0, 4)))

        onNodeWithTag(INPUT_TAG).performMouseInput { rightClick(fixture.centreOf(2, 0)) }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) {
            onAllNodes(androidx.compose.ui.test.hasTestTag(TerminalMenuTags.MENU)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(TerminalMenuTags.item(TerminalMenuAction.COPY)).performClick()
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.clipboard.writes.isNotEmpty() }
        assertEquals(listOf("alpha"), fixture.clipboard.writes)
        assertEquals(0, fixture.engine.mouseCalls.get(), "the right click reached the program")
        fixture.assertSilence("the context menu")
    }

    @Test fun aLongPressOffersPasteAndThePasteGoesThroughTheEngine() = terminalInputTest { fixture ->
        fixture.feed("alpha beta")
        waitForIdle()
        fixture.clipboard.content = "pasted"
        onNodeWithTag(INPUT_TAG).performTouchInput {
            down(fixture.centreOf(7, 0))
            advanceEventTime(900)
            move()
            up()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) {
            onAllNodes(androidx.compose.ui.test.hasTestTag(TerminalMenuTags.MENU)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(TerminalMenuTags.item(TerminalMenuAction.PASTE)).performClick()
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorded() == "pasted" }
        assertEquals(1, fixture.engine.pasteCalls.get())
        // Typing (the paste) ended the selection and put the menu away.
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selection() == null }
        assertEquals(
            0,
            onAllNodes(androidx.compose.ui.test.hasTestTag(TerminalMenuTags.MENU)).fetchSemanticsNodes().size,
        )
    }

    @Test fun theKeyBarsPasteButtonReadsTheClipboard() = terminalInputTest { fixture ->
        fixture.clipboard.content = "from the bar"
        fixture.accessories.pasteClipboard()
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorded() == "from the bar" }
        assertNull(fixture.selection())
    }
}
