package dev.supermux.terminal.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Composing text, and the one rule that matters: a preedit byte never reaches the program.
 *
 * Every real IME shows the user characters before they are final — a Turkish dead key, a CJK
 * candidate list, an emoji picker, a software keyboard rewriting the word it is on. A terminal that
 * forwards those sends the user's typos to a shell. [TerminalImeState] is where that is decided, so
 * it is driven here with the exact event streams those IMEs produce (an editing buffer plus a
 * composing span) and asserted on what it says to send.
 *
 * The end-to-end half — a commit really becoming bytes exactly once, and a hardware key's echo NOT
 * becoming a second copy — runs through the REAL surface and the real engine, against the byte
 * recorder. Compose's test harness can commit text to a field but cannot set a composing region, so
 * the composition streams themselves are asserted at the state machine. Verifying a real IME on a
 * device (a Turkish keyboard, a Japanese candidate window, TalkBack's dictation) needs the sample
 * app and is deferred with it to Plan 2 Task 6 / Plan 4.
 */
class TerminalImeTest {

    /** Feeds one editing-buffer state in and returns what the terminal would be told. */
    private fun TerminalImeState.step(text: String, composing: IntRange?): ImeStep =
        onChange(text, composing)

    @Test fun aTurkishDeadKeyCommitsOnceWhenTheAccentLands() {
        val ime = TerminalImeState()
        // A dead-key layout composes "^" and then replaces the whole span with "î".
        val caret = ime.step("^", 0 until 1)
        assertEquals("", caret.commit, "a dead key's preedit was sent")
        assertEquals("^", caret.marked)

        val composed = ime.step("î", 0 until 1)
        assertEquals("", composed.commit, "the composed character was sent before it was committed")
        assertEquals("î", composed.marked)

        val done = ime.step("î", null)
        assertEquals("î", done.commit, "the committed character never arrived")
        assertEquals("", done.marked)
        assertTrue(done.clear)

        // And the diacritic that Turkish actually needs, typed as a single committed character.
        assertEquals("ş", TerminalImeState().step("ş", null).commit)
    }

    @Test fun aCjkCompositionSendsOnlyTheChosenCandidate() {
        val ime = TerminalImeState()
        // Romaji in, kana on screen, kanji chosen: three preedit states, one commit.
        assertEquals("", ime.step("n", 0 until 1).commit)
        assertEquals("", ime.step("に", 0 until 1).commit)
        assertEquals("にほん", ime.step("にほん", 0 until 3).marked)
        assertEquals("", ime.step("日本", 0 until 2).commit, "a candidate was sent before it was chosen")
        assertEquals("日本", ime.step("日本", null).commit)
        assertEquals("", ime.marked)
    }

    @Test fun aCancelledCompositionSendsNothingAtAll() {
        val ime = TerminalImeState()
        ime.step("にほ", 0 until 2)
        // Escape: the IME withdraws the span and the text with it.
        val cancelled = ime.step("", null)
        assertEquals("", cancelled.commit, "a cancelled composition still reached the program")
        assertEquals("", cancelled.marked)
        assertTrue(cancelled.clear)

        // And the next word starts from a clean slate rather than re-sending the abandoned one.
        assertEquals("ok", ime.step("ok", null).commit)
    }

    @Test fun anEmojiIsOneCommitEvenThoughItIsTwoCharsAndSeveralCodePoints() {
        // A picker commits directly, with no composition: a surrogate pair, then a ZWJ sequence.
        // One state per commit, because the surface empties the field's buffer between them.
        assertEquals("😀", TerminalImeState().step("😀", null).commit)
        val zwj = "👩‍💻"
        assertEquals(zwj, TerminalImeState().step(zwj, null).commit)

        // The buffer really being emptied is what lets the NEXT emoji through on one state.
        val ime = TerminalImeState()
        assertEquals("😀", ime.step("😀", null).commit)
        ime.step("", null)
        assertEquals(zwj, ime.step(zwj, null).commit)
    }

    @Test fun aSoftwareKeyboardReplacingItsComposingSpanCommitsTheWordOnce() {
        val ime = TerminalImeState()
        // The autocorrecting keyboard case: the span is rewritten letter by letter, then replaced
        // outright by the suggestion, and only then committed.
        assertEquals("", ime.step("t", 0 until 1).commit)
        assertEquals("", ime.step("te", 0 until 2).commit)
        assertEquals("", ime.step("tst", 0 until 3).commit)
        assertEquals("", ime.step("test", 0 until 4).commit, "a suggestion was sent as it was chosen")
        val committed = ime.step("test ", null)
        assertEquals("test ", committed.commit)

        // A second word composed AFTER committed text only sends the part that is new.
        val next = TerminalImeState()
        next.step("hello ", null)
        assertEquals("", next.step("hello w", 6 until 7).commit, "the committed prefix was sent twice")
        assertEquals("", next.step("hello world", 6 until 11).commit)
        // Only "world": "hello " was committed by the step above and the terminal already has it.
        assertEquals("world", next.step("hello world", null).commit)
    }

    @Test fun textCommittedInFrontOfALiveCompositionIsSentOnceAndOnlyOnce() {
        val ime = TerminalImeState()
        // Some IMEs commit the finished part while keeping composing on the rest.
        assertEquals("", ime.step("にほんご", 0 until 4).commit)
        assertEquals("日本", ime.step("日本ご", 2 until 3).commit, "the finished part was not sent")
        assertEquals("ご", ime.step("日本ご", 2 until 3).marked)
        // The same buffer again (a cursor move, a repaint) must not send it a second time.
        assertEquals("", ime.step("日本ご", 2 until 3).commit, "the same text was sent twice")
        assertEquals("語", ime.step("日本語", null).commit)
    }

    @Test fun anEmptiedBufferNeverResendsWhatTheTerminalAlreadyHeard() {
        val ime = TerminalImeState()
        assertEquals("abc", ime.step("abc", null).commit)
        // The surface empties the buffer after every commit. That emptied buffer comes back through
        // the same path, and it must commit NOTHING — the terminal already has those characters.
        assertEquals("", ime.step("", null).commit, "the emptied buffer re-sent its own text")
        assertEquals("d", ime.step("d", null).commit)

        // The race the emptying loses: the IME starts the next word before the clear lands, so the
        // buffer still carries the committed prefix. Nothing before the composing span is new.
        val racing = TerminalImeState()
        assertEquals("hello ", racing.step("hello ", null).commit)
        assertEquals("", racing.step("hello w", 6 until 7).commit, "the committed prefix was sent twice")
        assertEquals("world", racing.step("hello world", null).commit)
    }

    @Test fun aRevisionAfterTheCommitIsAppendedBecauseBytesCannotBeUnsent() {
        // PINS A KNOWN LIMITATION, so that changing it is a deliberate act. See [TerminalImeState].
        val ime = TerminalImeState()
        assertEquals("teh ", ime.step("teh ", null).commit)
        // The surface empties the buffer after the commit, which is what the next step sees.
        assertEquals("", ime.step("", null).commit)

        // The keyboard now revises the word it already committed. The buffer is empty, so this is
        // indistinguishable from the user typing "the" for the first time — and the original bytes
        // are already past the pty and cannot be taken back.
        assertEquals("", ime.step("the", 0 until 3).commit)
        assertEquals(
            "the",
            ime.step("the", null).commit,
            "the revision stopped being appended — intended? then update TerminalImeState's KDoc, " +
                "terminal-compose/README.md §6 and this test together",
        )
        // What the user sees on the wire is "teh the": the original, then the correction.
    }

    // ----------------------------------------------------------------- the echo gate ----

    @Test fun twoPressesOfTheSameCharacterDropTwoEchoesAndNotOne() {
        val gate = TerminalTextGate()
        // An echo comes back through the IME process, so both presses can land before either echo.
        gate.submitted("a")
        gate.submitted("a")
        assertNull(gate.commit("a"), "the first echo was typed a second time")
        assertNull(gate.commit("a"), "the second press's echo was treated as new input")
        // A third copy has no press behind it: someone really typed it.
        assertEquals("a", gate.commit("a"), "a character the user typed was swallowed")
    }

    @Test fun anEchoThatBeatsTheNextPressIsStillMatchedToItsOwn() {
        val gate = TerminalTextGate()
        gate.submitted("a")
        assertNull(gate.commit("a"))
        gate.submitted("a")
        assertNull(gate.commit("a"), "the second press's echo was treated as new input")
        assertEquals("a", gate.commit("a"))
    }

    @Test fun anEchoThatNeverArrivesIsRetiredByTheNextOneThatDoes() {
        val gate = TerminalTextGate()
        // A platform that echoes some presses and not others leaves 'a' pending for ever.
        gate.submitted("a")
        gate.submitted("b")
        // Echoes keep the order of their presses, so 'a''s can no longer be in flight behind 'b''s.
        assertNull(gate.commit("b"))
        assertEquals("a", gate.commit("a"), "a character the user typed was swallowed as a stale echo")
    }

    @Test fun aCommitThatMatchesNothingRetiresTheEchoesBehindIt() {
        val gate = TerminalTextGate()
        gate.submitted("a")
        // Composed text is nobody's echo — and it means the pending ones are stale.
        assertEquals("ş", gate.commit("ş"))
        assertEquals("a", gate.commit("a"), "a character the user typed was swallowed as a stale echo")
    }

    @Test fun aLiveCompositionClosesTheEchoWindow() {
        val gate = TerminalTextGate()
        gate.submitted("a")
        gate.composing()
        assertEquals("a", gate.commit("a"), "a composed character was swallowed as a key's echo")

        // And so does focus leaving the surface.
        val refocused = TerminalTextGate()
        refocused.submitted("a")
        refocused.clear()
        assertEquals("a", refocused.commit("a"))
    }

    @Test fun thePendingEchoQueueIsBounded() {
        // Most platforms echo nothing at all, so unclaimed entries are the normal case: the queue
        // has to forget the oldest rather than grow for the life of the surface.
        val overflowing = TerminalTextGate()
        for (c in 'a'..'i') overflowing.submitted(c.toString())
        assertEquals("a", overflowing.commit("a"), "the echo queue grew past its bound")

        // The bound is not tighter than it claims: eight presses deep, the oldest still matches.
        val full = TerminalTextGate()
        for (c in 'a'..'h') full.submitted(c.toString())
        assertNull(full.commit("a"), "an echo within the bound was treated as new input")
    }

    @Test fun aKeyThatProducedNoTextNeitherEchoesNorCancelsOne() {
        val gate = TerminalTextGate()
        gate.submitted("a")
        // An arrow key, Ctrl-C, F5: not a text event, and no reason to forget 'a''s echo.
        gate.submitted("")
        assertNull(gate.commit("a"), "an arrow key between a press and its echo let the echo through")
    }

    // ----------------------------------------------------------------- the wire ----

    @OptIn(ExperimentalTestApi::class)
    @Test fun committedTextReachesTheProgramExactlyOnce() = terminalInputTest { fixture ->
        // The IME's own node: invisible, but it is the thing with a text-input action.
        onNode(hasSetTextAction()).performTextInput("héllo")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().size >= 6 }
        waitForIdle()

        // Six bytes, not twelve: "héllo" is 6 UTF-8 bytes and the surface sent it once.
        assertEquals("héllo", fixture.recorded())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aHardwareKeyAndItsImeEchoAreOneCharacter() = terminalInputTest { fixture ->
        // The Android/desktop case: the same character arrives as a key event AND as a commit.
        val router = TerminalKeyRouter(send = { fixture.session.key(it) })
        router.handle(keyEvent(Key.A, 'a'.code))
        assertEquals(false, router.commitText("a"), "the IME echo of a hardware key was sent again")
        // Anything else passes: an IME commit is the only path for composed text.
        assertEquals(true, router.commitText("ş"))
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().size >= 3 }
        waitForIdle()
        assertEquals("aş", fixture.recorded())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun twoFastPressesAndTwoLateEchoesTypeTwoCharacters() = terminalInputTest { fixture ->
        // A soft keyboard fast enough that both presses land before either echo does. Three 'a' on
        // the wire for two keystrokes is what a one-deep echo record produces here.
        val router = TerminalKeyRouter(send = { fixture.session.key(it) })
        router.handle(keyEvent(Key.A, 'a'.code))
        router.handle(keyEvent(Key.A, 'a'.code, androidx.compose.ui.input.key.KeyEventType.KeyUp))
        router.handle(keyEvent(Key.A, 'a'.code))
        router.handle(keyEvent(Key.A, 'a'.code, androidx.compose.ui.input.key.KeyEventType.KeyUp))
        assertEquals(false, router.commitText("a"), "the first press's echo was typed again")
        assertEquals(false, router.commitText("a"), "the second press's echo was typed again")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().size >= 2 }
        waitForIdle()
        assertEquals("aa", fixture.recorded())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aPressWhoseEchoNeverArrivesDoesNotSwallowATypedCharacter() = terminalInputTest { fixture ->
        val router = TerminalKeyRouter(send = { fixture.session.key(it) })
        // A hardware key on a platform that does not echo: nothing ever claims this pending echo.
        router.handle(keyEvent(Key.A, 'a'.code))
        // The user then types the SAME character on the soft keyboard, which composes first.
        router.composing()
        assertEquals(true, router.commitText("a"), "a character the user typed was swallowed")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().size >= 2 }
        waitForIdle()
        // Both reach the program: the key the user pressed and the character the user typed.
        assertEquals("aa", fixture.recorded())
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun keyEvent(
        key: Key,
        codePoint: Int,
        type: androidx.compose.ui.input.key.KeyEventType =
            androidx.compose.ui.input.key.KeyEventType.KeyDown,
    ) = androidx.compose.ui.input.key.KeyEvent(
        key = key,
        type = type,
        codePoint = codePoint,
    )
}
