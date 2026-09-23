package dev.supermux.terminal.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
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

    /** Focus left, the surface was rebound, or the field was emptied: forget everything in flight. */
    fun reset() {
        sent = 0
        marked = ""
    }
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
 */
@Composable
internal fun TerminalImeField(
    state: TerminalImeState,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onComposing: () -> Unit = {},
) {
    val field = rememberTextFieldState()
    LaunchedEffect(state, field, enabled) {
        if (!enabled) {
            state.reset()
            return@LaunchedEffect
        }
        snapshotFlow { field.text.toString() to field.composition }.collect { (text, composition) ->
            val step = state.onChange(text, composition?.toIntRange())
            // A live preedit sends nothing, but it IS the end of any hardware key's echo window.
            if (step.marked.isNotEmpty()) onComposing()
            if (step.commit.isNotEmpty()) onCommit(step.commit)
            // Only ever emptied between compositions: an IME whose buffer is pulled away mid-word
            // re-sends the whole word, which is the classic "hello" -> "hhehelhellhello" bug.
            if (step.clear && text.isNotEmpty()) field.edit { replace(0, length, "") }
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
        lineLimits = TextFieldLineLimits.SingleLine,
        // Never calls innerTextField: the field exists for the IME, not for the screen.
        decorator = { Box(Modifier.size(1.dp)) },
    )
}

/** Compose's [TextRange] as the half-open span this package passes around, or null when empty. */
private fun TextRange.toIntRange(): IntRange? = if (collapsed) null else min until max
