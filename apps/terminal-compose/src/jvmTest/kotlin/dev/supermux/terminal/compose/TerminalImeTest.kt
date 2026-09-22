package dev.supermux.terminal.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun keyEvent(key: Key, codePoint: Int) = androidx.compose.ui.input.key.KeyEvent(
        key = key,
        type = androidx.compose.ui.input.key.KeyEventType.KeyDown,
        codePoint = codePoint,
    )
}
