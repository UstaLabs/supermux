package dev.supermux.terminal.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.supermux.terminal.TerminalKeys

/**
 * The seed the hidden editing buffer always carries, and the reason a soft Backspace works at all.
 *
 * **The problem it solves.** A software keyboard does not press keys; it EDITS the focused field.
 * iOS's delete key calls `deleteBackward()`, which Compose turns into "delete one code point before
 * the cursor" — and on an EMPTY buffer that deletes nothing. Compose's own change tracker then
 * throws the edit away as a no-op (`ChangeTracker.trackChange` returns early when `preStart ==
 * preEnd && postLength == 0`), so it never reaches an `InputTransformation`, never changes
 * `TextFieldState.text`, and never produces a snapshot this surface could observe. The keystroke is
 * INVISIBLE, at every seam Compose offers. That is exactly what "delete does not work on iOS" was.
 *
 * **The fix.** The buffer is never empty: between compositions it holds [IME_SEED], a short run of
 * zero-width spaces with the cursor after them. A delete now always has something to eat, the buffer
 * really shrinks, and the surface can tell the terminal that a Backspace happened — then re-seed.
 *
 * Zero-width space, not a space: the IME is asked for no autocorrect and no capitalization, but a
 * keyboard that ignored those hints must still not see a word it can "correct". Nothing draws it —
 * this field paints nothing — and it is stripped before the arithmetic in [TerminalImeState] ever
 * sees the buffer, so the terminal never hears about it.
 *
 * **Why FOUR.** One would do if every edit were observed, but `snapshotFlow` is conflated: two
 * deletes that land between two collector runs are seen as one buffer. Each seed character is one
 * Backspace of burst headroom, and four covers a held delete key on any repeat rate a keyboard
 * produces. The cost is the one case that can eat the whole seed at once — a select-all + delete —
 * which would be reported as four Backspaces; nothing on a one-pixel field with a permanently
 * collapsed cursor can produce that gesture.
 */
internal const val IME_SEED: String = "​​​​"

/**
 * What one IME change means for the terminal: what to send, what is still being composed, and
 * whether the editing buffer may now be emptied.
 */
internal data class ImeStep(
    /** Text the user finished typing. Send it; "" means nothing was committed by this change. */
    val commit: String,
    /** The text the IME is still composing. Draw it at the cursor; NEVER send it. */
    val marked: String,
    /**
     * True when the editing buffer may be cleared: nothing is being composed, so emptying it cannot
     * pull the ground out from under an in-flight composition.
     */
    val clear: Boolean,
    /**
     * Backspaces the IME asked for by eating into [IME_SEED] — a delete the user meant for the
     * TERMINAL, not for a composition. Sent as [TerminalKeys.BACKSPACE] presses, never as bytes.
     */
    val backspaces: Int = 0,
)

/**
 * The composing text, kept strictly apart from what the terminal has been told.
 *
 * **The problem.** An IME is the only path for Turkish dead keys, CJK composition, emoji pickers,
 * dictation and a software keyboard's autocorrect — and on every one of those the user's characters
 * exist, visibly, BEFORE they are final. A terminal that forwards them as they appear sends
 * `s`, then backspaces, then `ş`; a program reading a password or driving a full-screen UI sees
 * garbage. So preedit bytes are never sent. The marked text is drawn by this surface, at the cursor,
 * and the program hears exactly one thing: the committed string.
 *
 * **The rule.** The editing buffer is `committed-prefix + composing-span`. The composing span is the
 * IME's; everything before it is finished text. This class tracks how much of that prefix it has
 * already sent ([sent]) and emits only the part it has not:
 *
 * - a change that grows the prefix commits the new part, once;
 * - a change that only moves the composing span commits nothing — this is the dead-key and the CJK
 *   candidate case, and it is where a naive implementation sends the preedit;
 * - a change that ENDS the composition (`composition == null`) commits the tail and then says the
 *   buffer may be cleared, which is how the next word starts from zero without the IME ever seeing
 *   its buffer emptied mid-composition;
 * - a cancelled composition (the IME clears the span and the text with it) commits nothing at all:
 *   [commit] is empty, [marked] becomes empty, and the terminal never heard about it.
 *
 * **Double submit.** A hardware key press on Android and on the desktop can arrive BOTH as a key
 * event and as an IME commit of the same character. That is not this class's job: the committed
 * string goes through [TerminalKeyRouter.commitText], and [TerminalTextGate] drops the echo of the
 * key press that just ran. Here the two paths stay separate on purpose — an IME commit that is NOT
 * an echo (a soft keyboard, a candidate, dictation) has to pass.
 *
 * **Known limitation: a revision AFTER the commit.** Once a change ends a composition, the committed
 * text is on the pty and the caller empties the field's buffer — so a soft keyboard that later comes
 * back to "revise the word I already gave you" (autocorrect landing a word late, a swipe or
 * dictation rewrite, a candidate re-picked after the fact) arrives at an EMPTY buffer and is
 * indistinguishable from the user typing that word for the first time. It is therefore committed
 * again, and the user sees the original followed by the correction — `teh the`, not `the`.
 *
 * This is not fixable here, and not a bug in this class. A terminal cannot un-send bytes: the
 * original is already past the pty, quite possibly already consumed by a program on the far end of a
 * network. "Correcting" it would mean synthesizing backspaces, which is wrong for everything that is
 * not a line editor — a full-screen UI, a password prompt, a program in raw mode — and would turn a
 * cosmetic duplicate into corrupted input. Suppressing a commit that happens to equal the last one
 * would swallow the user really typing the same word twice, which is worse than the duplicate.
 *
 * What keeps it rare: the field asks for no autocorrect, no capitalization and no suggestions
 * ([TerminalImeField]'s [KeyboardOptions]), so a keyboard that honours those hints never revises at
 * all. `TerminalImeTest.aRevisionAfterTheCommitIsAppendedBecauseBytesCannotBeUnsent` pins the
 * behaviour so that any future change to it is a deliberate one.
 *
 * Read and written on the composition's thread only.
 */
@Stable
internal class TerminalImeState {
    /** The text the IME is still composing, for the painter to draw at the cursor. */
    var marked: String by mutableStateOf("")
        private set

    /** How many characters of the buffer's committed prefix have already been sent. */
    private var sent = 0

    /**
     * Fold one editing-buffer change in. [composition] is the IME's composing span, or null when
     * nothing is being composed.
     */
    fun onChange(text: String, composition: IntRange?): ImeStep {
        val length = text.length
        val start = composition?.first?.coerceIn(0, length) ?: length
        val end = composition?.last?.plus(1)?.coerceIn(start, length) ?: length
        val committed = text.substring(0, start)
        // A buffer that SHRANK below what was already sent is a cancelled or rewritten composition,
        // not a retraction: the terminal cannot un-hear bytes, so the counter follows the buffer
        // down and only genuinely new text is ever sent again.
        if (committed.length < sent) sent = committed.length
        val commit = committed.substring(sent)
        sent = committed.length
        marked = text.substring(start, end)
        val clear = composition == null
        if (clear) marked = ""
        // `sent` is NOT reset here. The buffer is emptied by the caller, and the emptied buffer
        // comes back through this same method as a shrink — which is what resets it. Resetting on
        // the commit itself would re-send the whole prefix whenever the IME starts the next word
        // before that clear lands, which is exactly what a fast soft keyboard does.
        return ImeStep(commit = commit, marked = marked, clear = clear)
    }

    /**
     * Fold one RAW field buffer in: [IME_SEED] and all.
     *
     * This is the entry point [TerminalImeField] uses, and the only one that knows about the seed.
     * It splits the buffer into the part the terminal may never hear about (the surviving seed) and
     * the part [onChange] has always reasoned about (`committed-prefix + composing-span`):
     *
     * - seed intact → nothing was deleted; this is an ordinary change.
     * - seed SHORT → the IME deleted past the end of everything it was composing and ate into the
     *   seed, which is the only shape a "the user pressed Backspace at the terminal" edit has. Each
     *   missing seed character is one [TerminalKeys.BACKSPACE].
     *
     * Deleting inside a COMPOSITION never reaches the seed, which is exactly right: those characters
     * were never sent, so taking them back is the IME's business and the program must not hear a
     * Backspace for them.
     */
    fun onBuffer(buffer: String, composition: IntRange?): ImeStep {
        val seed = intactSeedOf(buffer)
        val step = onChange(buffer.substring(seed), composition?.shiftedBy(seed))
        return step.copy(backspaces = IME_SEED.length - seed)
    }

    /** Focus left, the surface was rebound, or the field was emptied: forget everything in flight. */
    fun reset() {
        sent = 0
        marked = ""
    }
}

/** How much of [IME_SEED] is still at the front of [buffer]: [IME_SEED].length when nothing ate it. */
internal fun intactSeedOf(buffer: String): Int {
    var kept = 0
    while (kept < IME_SEED.length && kept < buffer.length && buffer[kept] == IME_SEED[kept]) kept++
    return kept
}

/** The composing span in the seed-stripped buffer's coordinates, or null when it falls away. */
private fun IntRange.shiftedBy(seed: Int): IntRange? {
    val from = (first - seed).coerceAtLeast(0)
    val until = last + 1 - seed
    return if (until > from) from until until else null
}

/**
 * The committed string as the ORDERED input it stands for: text runs with Returns between them.
 *
 * A soft keyboard's Return is not a key press anywhere the platform does not send key events for
 * it — on iOS it is `insertText("\n")`, which lands in the editing buffer as ordinary text. Sending
 * that text would put a literal `0x0A` on the pty, and `0x0A` is not what Return means: a terminal's
 * Return is `CR`, or `CRLF` under newline mode (LNM), or a kitty-keyboard report if the program
 * asked for one. Only the engine's encoder knows which, so the newline is turned back into the KEY
 * it came from ([TerminalKeys.ENTER]) and the encoder decides the bytes.
 *
 * `CRLF` counts once: a keyboard (or a paste) that wrote both is describing one Return.
 */
internal inline fun commitAsInput(commit: String, onText: (String) -> Unit, onEnter: () -> Unit) {
    var start = 0
    var at = 0
    while (at < commit.length) {
        val char = commit[at]
        if (char == '\n' || char == '\r') {
            if (at > start) onText(commit.substring(start, at))
            onEnter()
            if (char == '\r' && at + 1 < commit.length && commit[at + 1] == '\n') at++
            start = at + 1
        }
        at++
    }
    if (start < commit.length) onText(commit.substring(start))
}

/**
 * The surface's text-input seam: an invisible Compose text field that owns the IME session.
 *
 * It is a real [BasicTextField] — Compose's supported text-input primitive — because that is what
 * carries an IME on all four targets: an `InputConnection` on Android, the AWT input method on the
 * desktop, `UITextInput` on iOS and a hidden input element in the browser. Re-implementing any of
 * those per platform would be four ways to get dead keys wrong; the low-level alternative
 * (`PlatformTextInputModifierNode` + `PlatformTextInputMethodRequest`) has no common request type,
 * so it would need one `actual` per target for no behaviour this surface does not already get.
 *
 * It draws NOTHING. The decorator never places `innerTextField`, so no glyph, no cursor and no
 * selection handle of Compose's own is ever painted — the terminal paints its own, including the
 * marked text ([TerminalImeState.marked]), on its own canvas. The field is one pixel so it still has
 * a position for the platform to anchor a candidate window to.
 *
 * It is also the surface's FOCUS target: an IME only runs for a focused field. The terminal's Box is
 * a focus group around it, so `focusRequester.requestFocus()` on the group lands here, hardware keys
 * are still seen first by the Box's `onPreviewKeyEvent` (a preview runs from the root down to the
 * focused node) and the field itself never gets to insert anything the terminal already handled.
 *
 * **Return and Backspace, which a soft keyboard does not press.** A hardware keyboard produces key
 * events, and those are the Box's ([TerminalKeyRouter]). A SOFTWARE keyboard produces EDITS, and two
 * of them are keys a terminal cannot do without:
 *
 * - **Return.** iOS calls `insertText("\n")`. Compose intercepts that for a field whose
 *   `imeOptions.singleLine` is set and runs the IME ACTION instead — and with [ImeAction.None] the
 *   action does nothing at all, so the newline is dropped on the floor and Return is dead. The field
 *   is therefore NOT [TextFieldLineLimits.SingleLine]: the newline reaches the buffer as text, and
 *   [commitAsInput] turns it back into a [TerminalKeys.ENTER] press for the engine's encoder. The
 *   line limit costs nothing — this field never draws a line.
 * - **Backspace.** iOS calls `deleteBackward()`, which on an empty buffer deletes nothing and is
 *   discarded by Compose before any observer sees it. [IME_SEED] is what gives it something to eat;
 *   see that constant.
 *
 * Neither can double-send on a platform that DOES deliver these as key events: the Box's preview
 * consumes the key before the field can act on it, so the buffer never sees the edit at all.
 */
@Composable
internal fun TerminalImeField(
    state: TerminalImeState,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onKey: (Int) -> Unit = {},
    onComposing: () -> Unit = {},
) {
    val field = rememberTextFieldState(IME_SEED, TextRange(IME_SEED.length))
    LaunchedEffect(state, field, enabled) {
        if (!enabled) {
            // A pane that went to the background keeps its buffer, and a buffer that still held
            // committed text would be re-sent the moment the pane came back (the counter this
            // resets is the only record of what the terminal already heard). Re-seeding it here is
            // safe precisely because the field is disabled: no IME session is running on it.
            state.reset()
            field.edit { replace(0, length, IME_SEED); placeCursorAtEnd() }
            return@LaunchedEffect
        }
        snapshotFlow { field.text.toString() to field.composition }.collect { (text, composition) ->
            val step = state.onBuffer(text, composition?.toIntRange())
            // A live preedit sends nothing, but it IS the end of any hardware key's echo window.
            if (step.marked.isNotEmpty()) onComposing()
            // Deletions first: they describe the text that was on the screen BEFORE this change.
            repeat(step.backspaces) { onKey(TerminalKeys.BACKSPACE) }
            if (step.commit.isNotEmpty()) commitAsInput(step.commit, onCommit) { onKey(TerminalKeys.ENTER) }
            // Only ever re-seeded between compositions: an IME whose buffer is pulled away mid-word
            // re-sends the whole word, which is the classic "hello" -> "hhehelhellhello" bug. And
            // only when the buffer is not ALREADY the bare seed, or this write would feed itself.
            if (step.clear && text != IME_SEED) {
                field.edit { replace(0, length, IME_SEED); placeCursorAtEnd() }
            }
        }
    }
    BasicTextField(
        state = field,
        modifier = modifier.size(1.dp).focusRequester(focusRequester),
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            // A terminal is not prose: nothing may be capitalized, corrected or completed on its way
            // in, and Enter is a key the program reads, not an action that submits a form.
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.None,
        ),
        // MultiLine, not SingleLine, and the whole of Return on iOS turns on it — see the KDoc
        // above. One line, because a field that draws nothing has no use for a second.
        lineLimits = TextFieldLineLimits.MultiLine(1, 1),
        // Never calls innerTextField: the field exists for the IME, not for the screen.
        decorator = { Box(Modifier.size(1.dp)) },
    )
}

/** Compose's [TextRange] as the half-open span this package passes around, or null when empty. */
private fun TextRange.toIntRange(): IntRange? = if (collapsed) null else min until max
