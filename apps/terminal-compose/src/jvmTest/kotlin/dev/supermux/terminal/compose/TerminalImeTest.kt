package dev.supermux.terminal.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import dev.supermux.terminal.KeyAction
import dev.supermux.terminal.TerminalKeys
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

    // ------------------------------------------------- what a SOFT keyboard does instead ----
    //
    // A software keyboard does not press keys; it EDITS the focused field. Two of those edits are
    // keys a terminal cannot do without, and both were dead on iOS before this suite existed:
    // Return arrived as an inserted "\n" (or, with the field marked single-line, as an IME action
    // that did nothing at all), and Backspace arrived as `deleteBackward()` on an EMPTY buffer —
    // a no-op Compose discards before any observer can see it. [IME_SEED] and [ImeStep.backspaces]
    // are the answer to the second; [commitAsInput] is the answer to the first.

    /** The buffer as the FIELD holds it: the seed, then whatever the IME has put after it. */
    private fun seeded(after: String = "") = IME_SEED + after

    @Test fun aSoftKeyboardsBackspaceEatsTheSeedAndBecomesABackspaceKey() {
        val ime = TerminalImeState()
        assertEquals(0, ime.onBuffer(seeded(), null).backspaces, "a settled buffer reported a delete")

        // iOS's deleteBackward() on a buffer with nothing composing: one code point, off the seed.
        val once = ime.onBuffer(IME_SEED.dropLast(1), null)
        assertEquals(1, once.backspaces, "the delete key reached the buffer and nothing was sent")
        assertEquals("", once.commit, "a Backspace was sent as TEXT instead of as a key")
        assertTrue(once.clear, "the buffer was not re-seeded, so the next Backspace would be lost")

        // A held delete key, fast enough that several land between two conflated snapshots.
        val burst = TerminalImeState().onBuffer(IME_SEED.dropLast(3), null)
        assertEquals(3, burst.backspaces, "a burst of deletes was under-counted")
    }

    @Test fun deletingInsideACompositionIsNotABackspaceForTheProgram() {
        val ime = TerminalImeState()
        // "ab" is being composed, so the program has heard nothing at all yet.
        assertEquals("", ime.onBuffer(seeded("ab"), 4 until 6).commit)
        // Backspace now shortens the PREEDIT. Those bytes were never sent; taking them back is the
        // IME's business and a Backspace on the wire would delete the user's real shell line.
        val shorter = ime.onBuffer(seeded("a"), 4 until 5)
        assertEquals(0, shorter.backspaces, "deleting a preedit character sent a Backspace")
        assertEquals("a", shorter.marked)
        // Only once the composition is gone does a further delete reach the seed — and the terminal.
        assertEquals(0, ime.onBuffer(seeded(), null).backspaces)
        assertEquals(1, ime.onBuffer(IME_SEED.dropLast(1), null).backspaces)
    }

    @Test fun theSeedIsStrippedBeforeTheTerminalEverHearsTheBuffer() {
        val ime = TerminalImeState()
        // Everything the existing arithmetic does, one seed to the right: nothing about the
        // committed prefix, the composing span or the echo window changes.
        assertEquals("", ime.onBuffer(seeded("にほ"), 4 until 6).commit)
        assertEquals("にほ", ime.marked)
        assertEquals("日本", ime.onBuffer(seeded("日本"), null).commit)
        // And the zero-width spaces themselves are never committed, however the buffer moves.
        assertTrue(IME_SEED.none { it != '\u200B' }, "the seed stopped being invisible")
        assertEquals(IME_SEED.length, intactSeedOf(seeded("anything")))
        assertEquals(0, intactSeedOf(""))
    }

    @Test fun aReturnFromAnImeBecomesTheEnterKeyAndNeverALiteralNewline() {
        // What iOS hands over for the return key: an inserted "\n" in the editing buffer.
        assertEquals(listOf("ENTER"), pieces("\n"))
        assertEquals(listOf("ls", "ENTER"), pieces("ls\n"))
        assertEquals(listOf("a", "ENTER", "b"), pieces("a\nb"))
        // CRLF is ONE Return: a keyboard that wrote both is describing one key, not two.
        assertEquals(listOf("a", "ENTER", "b"), pieces("a\r\nb"))
        assertEquals(listOf("ENTER", "ENTER"), pieces("\n\n"))
        // Ordinary text is untouched and arrives in one piece, not one per character.
        assertEquals(listOf("héllo"), pieces("héllo"))
    }

    /** [commitAsInput]'s output as a readable list: text runs, with "ENTER" where a key goes. */
    private fun pieces(commit: String): List<String> = buildList {
        commitAsInput(commit, onText = { add(it) }, onEnter = { add("ENTER") })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun anImeReturnIsEncodedByTheEngineAndNotWrittenHere() = terminalInputTest { fixture ->
        val router = TerminalKeyRouter(send = { fixture.session.key(it) })
        router.imeKey(TerminalKeys.ENTER)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.engine.keyCalls.get() >= 2 }
        waitForIdle()
        // CR, not LF: a terminal's Return is 0x0D, and the "\n" the keyboard inserted would have
        // been 0x0A — a different character, and the wrong one for every line editor there is.
        assertEquals(listOf(0x0D.toByte()), fixture.recorder.bytes().toList())
        assertEquals(TerminalKeys.ENTER, fixture.engine.keys.first().physicalCode)
        assertEquals("", fixture.engine.keys.first().text, "the Return was sent as TEXT")

        // And under a mode the program negotiated — here the kitty keyboard protocol, which the
        // surface never sees being turned on — the IME's Return and the HARDWARE Return have to
        // come out as the same bytes, whatever the encoder decides those are. That is the whole
        // claim: one semantic key, one encoder, no second implementation in Kotlin.
        fixture.feed("\u001b[>1u")
        waitForIdle()
        fixture.recorder.clear()
        onNodeWithTag(INPUT_TAG).performKeyPress(keyEvent(Key.Enter, 0x0D))
        onNodeWithTag(INPUT_TAG).performKeyPress(keyEvent(Key.Enter, 0x0D, KeyEventType.KeyUp))
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().isNotEmpty() }
        waitForIdle()
        val hardware = fixture.recorded()

        fixture.recorder.clear()
        router.imeKey(TerminalKeys.ENTER)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().isNotEmpty() }
        waitForIdle()
        assertEquals(hardware, fixture.recorded(), "the IME's Return took a different path from the key")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun anImeBackspaceIsTheBackspaceKeyAndNotASeedCharacter() = terminalInputTest { fixture ->
        val router = TerminalKeyRouter(send = { fixture.session.key(it) })
        router.imeKey(TerminalKeys.BACKSPACE)
        // On the KEY CALLS, not the bytes: the release produces no byte of its own, so a test that
        // waited for bytes would race the half of this it is here to assert.
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.engine.keyCalls.get() >= 2 }
        waitForIdle()
        // DEL (0x7F), which is what a terminal's Backspace is — and never the zero-width space the
        // keyboard actually deleted.
        assertEquals(listOf(0x7F.toByte()), fixture.recorder.bytes().toList())
        assertEquals(TerminalKeys.BACKSPACE, fixture.engine.keys.first().physicalCode)
        assertEquals("", fixture.engine.keys.first().text, "a Backspace carried text")
        // Press AND release, in that order: a program running the kitty keyboard protocol is owed
        // both, and the hardware path sends both.
        assertEquals(KeyAction.PRESS, fixture.engine.keys[0].action)
        assertEquals(KeyAction.RELEASE, fixture.engine.keys[1].action)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aReturnTypedIntoTheRealFieldReachesTheProgramAsCarriageReturn() = terminalInputTest { fixture ->
        // THE REGRESSION TEST for "Enter does not work on iOS": the newline goes in through the
        // IME's own node — an EDIT of the text field, exactly as `insertText("\n")` produces — and
        // never as a key event. Before the fix this reached the program as nothing at all.
        onNode(hasSetTextAction()).performTextInput("ls\n")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().size >= 3 }
        waitForIdle()
        assertEquals("ls\r", fixture.recorded())
        // The seed the buffer carries for Backspace's sake is NOT part of it.
        assertEquals(false, fixture.recorded().contains('\u200B'), "the seed reached the program")
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
