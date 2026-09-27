package dev.supermux.terminal.compose

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.SemanticsMatcher
import dev.supermux.terminal.TerminalEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the terminal is to a screen reader, a keyboard and the clipboard.
 *
 * These are headless assertions on the semantics tree and on the actions in it: the tree is what
 * TalkBack and VoiceOver read, and an action that works here works without a pointer — which is the
 * property that matters, because a screen-reader user has no pointer. Running the actual screen
 * readers needs a device and an app to run on; the sample lands with Plan 2 Task 6 and the manual
 * TalkBack / VoiceOver pass is deferred to it and to Plan 4.
 */
class TerminalAccessibilityTest {

    private fun hasAction(key: androidx.compose.ui.semantics.SemanticsPropertyKey<*>) =
        SemanticsMatcher.keyIsDefined(key)

    @OptIn(ExperimentalTestApi::class)
    @Test fun theTerminalPublishesItsNameTheVisibleRowsAndItsActions() = terminalInputTest { fixture ->
        fixture.feed("first line\r\nsecond line")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.rowTextOrEmpty(1).isNotEmpty() }
        waitForIdle()

        val node = onNodeWithTag(INPUT_TAG)
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, TerminalSemantics.LABEL))
        node.assert(hasAction(SemanticsActions.CopyText))
        node.assert(hasAction(SemanticsActions.PasteText))
        node.assert(hasAction(SemanticsActions.ScrollBy))
        node.assert(hasAction(SemanticsActions.RequestFocus))

        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertTrue("first line" in text && "second line" in text, "the viewport is not readable: $text")
        // Bounded: the VISIBLE rows and no more, whatever the scrollback holds.
        assertEquals(fixture.rows, text.split("\n").size, "the whole scrollback leaked into semantics")

        // Labels are part of the contract: a learned gesture keeps doing the same thing.
        val config = node.fetchSemanticsNode().config
        assertEquals(TerminalSemantics.COPY, config[SemanticsActions.CopyText].label)
        assertEquals(TerminalSemantics.PASTE, config[SemanticsActions.PasteText].label)
        assertEquals(TerminalSemantics.SCROLL, config[SemanticsActions.ScrollBy].label)
        assertEquals(TerminalSemantics.FOCUS, config[SemanticsActions.RequestFocus].label)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aBuildLogIsNotAnnounced() = terminalInputTest { fixture ->
        fixture.feed((0 until 50).joinToString("") { "compiling module $it...\r\n" })
        waitForIdle()
        // No live region: a terminal that announced every line would read a build instead of the
        // user's own typing. The text is there to be read on demand, never pushed.
        val config = onNodeWithTag(INPUT_TAG).fetchSemanticsNode().config
        assertTrue(
            SemanticsProperties.LiveRegion !in config,
            "the terminal announces its output; a build log would talk over the user",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theSelectionIsReadableAsARangeOfTheVisibleText() = terminalInputTest { fixture ->
        fixture.feed("alpha beta gamma")
        waitForIdle()
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(6, 0))
            press()
            moveTo(fixture.centreOf(9, 0))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText() == "beta" }
        waitForIdle()

        val config = onNodeWithTag(INPUT_TAG).fetchSemanticsNode().config
        val range = config[SemanticsProperties.TextSelectionRange]
        val text = config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals("beta", text.substring(range.start, range.end))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun copyAndPasteWorkWithNoPointerAtAll() = terminalInputTest { fixture ->
        fixture.feed("copy-me")
        waitForIdle()
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(0, 0))
            press()
            moveTo(fixture.centreOf(6, 0))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText() == "copy-me" }
        waitForIdle()

        // The a11y action, not a Ctrl-C: this is the path a screen-reader user actually has.
        onNodeWithTag(INPUT_TAG).performSemanticsAction(SemanticsActions.CopyText) { it() }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.clipboard.writes.isNotEmpty() }
        waitForIdle()
        assertEquals(listOf("copy-me"), fixture.clipboard.writes)
        fixture.assertSilence("copying")

        // Paste goes through the ENGINE's paste API, so the program hears one paste, not seven keys.
        fixture.clipboard.content = "pasted"
        onNodeWithTag(INPUT_TAG).performSemanticsAction(SemanticsActions.PasteText) { it() }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().isNotEmpty() }
        waitForIdle()
        assertEquals("pasted", fixture.recorded())
        assertEquals(1, fixture.engine.pasteCalls.get(), "a paste became key events")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aScreenReaderCanWalkTheHistoryWithoutAPointer() = terminalInputTest { fixture ->
        fixture.feed((0 until 200).joinToString("") { "line-$it\r\n" })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.historyRows > 100 }
        waitForIdle()
        assertTrue(fixture.scroll.following)

        onNodeWithTag(INPUT_TAG).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -200f) }
        waitForIdle()
        assertTrue(!fixture.scroll.following, "the scroll action did not move the history")
        fixture.assertSilence("the accessibility scroll action")

        val range = onNodeWithTag(INPUT_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f, "the scroll range does not describe the history")
        assertTrue(range.value() < range.maxValue(), "the scroll position is not reported")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardTraversalReachesTheTerminalAndTheFocusActionTakesTheKeyboard() =
        terminalInputTest(focused = false) { fixture ->
            // One focus stop for one terminal: the invisible IME field, which is what an IME needs.
            onNode(hasSetTextAction()).assertExists()

            onNodeWithTag(INPUT_TAG).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()
            assertTrue(
                onNodeWithTag(INPUT_TAG).fetchSemanticsNode().config[SemanticsProperties.Focused],
                "the terminal does not report itself as focused",
            )

            // And focus is what makes the terminal take keys at all.
            fixture.recorder.clear()
            onNode(hasSetTextAction()).performTextInput("x")
            waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().isNotEmpty() }
            assertEquals("x", fixture.recorded())
        }

    // ----------------------------------------------------------------- remote clipboard ----

    @OptIn(ExperimentalTestApi::class)
    @Test fun aProgramsClipboardWriteIsReportedAndNeverHonoured() = terminalInputTest { fixture ->
        // OSC 52: "put aGk= (hi) on the clipboard". The surface reports it and does nothing else.
        fixture.feed("\u001b]52;c;aGk=\u0007")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.clipboardRequests.isNotEmpty() }
        waitForIdle()

        assertEquals(
            listOf(TerminalEffect.ClipboardRequest(write = true, text = "hi")),
            fixture.clipboardRequests.toList(),
        )
        assertTrue(fixture.clipboard.writes.isEmpty(), "the surface put a program's text on the clipboard")
        assertEquals(0, fixture.clipboard.reads.get(), "the surface read the clipboard for a program")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aProgramsClipboardReadIsDeniedByTheEngineAndReadsNothingHere() = terminalInputTest { fixture ->
        fixture.clipboard.content = "a secret the program must not get"
        // OSC 52 with "?": a read. The engine answers it itself, with an EMPTY clipboard.
        fixture.feed("\u001b]52;c;?\u0007")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.clipboardRequests.isNotEmpty() }
        waitForIdle()

        val request = assertNotNull(fixture.clipboardRequests.firstOrNull())
        assertEquals(false, request.write)
        assertEquals(null, request.text)
        assertEquals(0, fixture.clipboard.reads.get(), "the surface read the clipboard for a program")
        assertTrue(
            "secret" !in fixture.relay.toString(),
            "the clipboard's content must never be anywhere near a program's request",
        )
    }
}
