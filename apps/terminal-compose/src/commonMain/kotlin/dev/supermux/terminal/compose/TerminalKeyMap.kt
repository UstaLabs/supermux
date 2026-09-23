package dev.supermux.terminal.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import dev.supermux.terminal.KeyAction
import dev.supermux.terminal.Modifiers
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalKeys

/**
 * Compose's physical [Key] to the package's layout-independent [TerminalKeys] code (a USB HID
 * keyboard usage ID), which the binding maps to Ghostty's `GhosttyKey` in C.
 *
 * It is a PHYSICAL map on purpose. Compose's `Key` is the platform's physical key code (AWT's
 * `VK_*`, Android's `KEYCODE_*`, a DOM `code`), so `Key.Semicolon` is "the key where `;` sits on a
 * US keyboard" whatever that key prints on this user's layout — which is exactly what
 * [TerminalKeys] means and exactly what a terminal needs, because Ctrl-<key> and the kitty keyboard
 * protocol are defined against the physical key while the CHARACTER comes from [TerminalKey.text].
 *
 * What is deliberately NOT here:
 * - **The keypad.** The package's key vocabulary has no keypad codes (neither [TerminalKeys] nor
 *   the wrapper's `ST_KEYS` table), so a keypad key would have to be reported as a fake main-row
 *   key — which would be wrong the moment a program turns application-keypad mode on. Keypad keys
 *   therefore arrive as TEXT only, which is what a terminal with application keypad off sends
 *   anyway. Adding them is an additive change to [TerminalKeys] + the C table, not a change here.
 * - **Bare modifier keys** (Ctrl, Shift, Alt, Meta). They carry no code and no text, so they are not
 *   forwarded; they are reported through the [Modifiers] of the key they modify. (The kitty
 *   protocol can report them on their own; that needs the same additive change.)
 */
internal val TERMINAL_KEY_CODES: Map<Key, Int> = buildMap {
    put(Key.A, TerminalKeys.A); put(Key.B, TerminalKeys.B); put(Key.C, TerminalKeys.C)
    put(Key.D, TerminalKeys.D); put(Key.E, TerminalKeys.E); put(Key.F, TerminalKeys.F)
    put(Key.G, TerminalKeys.G); put(Key.H, TerminalKeys.H); put(Key.I, TerminalKeys.I)
    put(Key.J, TerminalKeys.J); put(Key.K, TerminalKeys.K); put(Key.L, TerminalKeys.L)
    put(Key.M, TerminalKeys.M); put(Key.N, TerminalKeys.N); put(Key.O, TerminalKeys.O)
    put(Key.P, TerminalKeys.P); put(Key.Q, TerminalKeys.Q); put(Key.R, TerminalKeys.R)
    put(Key.S, TerminalKeys.S); put(Key.T, TerminalKeys.T); put(Key.U, TerminalKeys.U)
    put(Key.V, TerminalKeys.V); put(Key.W, TerminalKeys.W); put(Key.X, TerminalKeys.X)
    put(Key.Y, TerminalKeys.Y); put(Key.Z, TerminalKeys.Z)

    put(Key.One, TerminalKeys.DIGIT_1); put(Key.Two, TerminalKeys.DIGIT_2)
    put(Key.Three, TerminalKeys.DIGIT_3); put(Key.Four, TerminalKeys.DIGIT_4)
    put(Key.Five, TerminalKeys.DIGIT_5); put(Key.Six, TerminalKeys.DIGIT_6)
    put(Key.Seven, TerminalKeys.DIGIT_7); put(Key.Eight, TerminalKeys.DIGIT_8)
    put(Key.Nine, TerminalKeys.DIGIT_9); put(Key.Zero, TerminalKeys.DIGIT_0)

    put(Key.Enter, TerminalKeys.ENTER)
    put(Key.NumPadEnter, TerminalKeys.ENTER)
    put(Key.Escape, TerminalKeys.ESCAPE)
    put(Key.Backspace, TerminalKeys.BACKSPACE)
    put(Key.Tab, TerminalKeys.TAB)
    put(Key.Spacebar, TerminalKeys.SPACE)

    put(Key.Minus, TerminalKeys.MINUS)
    put(Key.Equals, TerminalKeys.EQUAL)
    put(Key.LeftBracket, TerminalKeys.BRACKET_LEFT)
    put(Key.RightBracket, TerminalKeys.BRACKET_RIGHT)
    put(Key.Backslash, TerminalKeys.BACKSLASH)
    put(Key.Semicolon, TerminalKeys.SEMICOLON)
    put(Key.Apostrophe, TerminalKeys.QUOTE)
    put(Key.Grave, TerminalKeys.BACKQUOTE)
    put(Key.Comma, TerminalKeys.COMMA)
    put(Key.Period, TerminalKeys.PERIOD)
    put(Key.Slash, TerminalKeys.SLASH)

    put(Key.F1, TerminalKeys.F1); put(Key.F2, TerminalKeys.F2); put(Key.F3, TerminalKeys.F3)
    put(Key.F4, TerminalKeys.F4); put(Key.F5, TerminalKeys.F5); put(Key.F6, TerminalKeys.F6)
    put(Key.F7, TerminalKeys.F7); put(Key.F8, TerminalKeys.F8); put(Key.F9, TerminalKeys.F9)
    put(Key.F10, TerminalKeys.F10); put(Key.F11, TerminalKeys.F11); put(Key.F12, TerminalKeys.F12)

    put(Key.Insert, TerminalKeys.INSERT)
    put(Key.MoveHome, TerminalKeys.HOME)
    put(Key.PageUp, TerminalKeys.PAGE_UP)
    put(Key.Delete, TerminalKeys.DELETE)
    put(Key.MoveEnd, TerminalKeys.END)
    put(Key.PageDown, TerminalKeys.PAGE_DOWN)
    put(Key.DirectionRight, TerminalKeys.ARROW_RIGHT)
    put(Key.DirectionLeft, TerminalKeys.ARROW_LEFT)
    put(Key.DirectionDown, TerminalKeys.ARROW_DOWN)
    put(Key.DirectionUp, TerminalKeys.ARROW_UP)
}

/** The physical code for [key], or [TerminalKeys.UNIDENTIFIED] when the package has no name for it. */
internal fun physicalCodeOf(key: Key): Int = TERMINAL_KEY_CODES[key] ?: TerminalKeys.UNIDENTIFIED

/** [Modifiers] of a Compose key event, plus whatever the accessory bar has [armed]. */
internal fun modifiersOf(event: KeyEvent, armed: Int = Modifiers.NONE): Int {
    var modifiers = armed
    if (event.isShiftPressed) modifiers = modifiers or Modifiers.SHIFT
    if (event.isCtrlPressed) modifiers = modifiers or Modifiers.CTRL
    if (event.isAltPressed) modifiers = modifiers or Modifiers.ALT
    if (event.isMetaPressed) modifiers = modifiers or Modifiers.SUPER
    return modifiers
}

/**
 * The layout text a key event committed, or "" when it committed none.
 *
 * [TerminalKey.text] is contractually "the layout text the key produced WITHOUT Ctrl/Meta applied,
 * never C0/DEL". Platforms disagree about what they put in `utf16CodePoint` for a modified key —
 * AWT reports `0x03` for Ctrl-C, Android reports `'c'` — so both are filtered here: a control
 * character never becomes text, and a Ctrl/Meta chord commits nothing at all. The physical code
 * carries those chords, and the wrapper derives the unshifted codepoint it needs from the US-layout
 * base character of that key.
 *
 * Alt is NOT filtered: `Alt-a` is `ESC a`, and the encoder builds it from the text.
 */
internal fun committedTextOf(event: KeyEvent): String {
    if (event.isCtrlPressed || event.isMetaPressed) return ""
    val codePoint = event.utf16CodePoint
    // 0 = no character; 0xFFFF = AWT's CHAR_UNDEFINED; C0 and DEL are never text.
    if (codePoint <= 0x1F || codePoint == 0x7F || codePoint == 0xFFFF) return ""
    if (codePoint > 0x10FFFF) return ""
    return buildString { appendCodePointCompat(codePoint) }
}

private fun StringBuilder.appendCodePointCompat(codePoint: Int) {
    if (codePoint < 0x10000) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - 0x10000
        append((0xD800 + (offset shr 10)).toChar())
        append((0xDC00 + (offset and 0x3FF)).toChar())
    }
}

/**
 * One character must reach the program exactly once, however many layers claim to have typed it.
 *
 * A soft keyboard and a hardware keyboard are not exclusive: on Android a physical key press can
 * arrive as a `KeyEvent` AND as an IME `commitText` for the same character, and on the desktop a
 * platform key-typed event follows the key-down of the same character. Both paths are legitimate —
 * an IME is the ONLY path for composed text, dictation and a soft keyboard's autocorrect — so the
 * fix is not to pick one but to make the second one notice.
 *
 * The rule: a hardware press records the text it submitted, and the next [commit] of exactly that
 * text is dropped as its echo. Anything else (a different character, composed text, a second copy
 * once the echo has been accounted for) passes.
 *
 * **Why a QUEUE and not one slot.** An echo is not synchronous: on Android the commit comes back
 * through the IME process, so it can land AFTER the next hardware key event. With a single slot,
 * two fast presses of the same character (`down a`, `down a`, `commit a`, `commit a`) drop one echo
 * and treat the other as new input — three characters for two keystrokes. The pending echoes are
 * therefore a FIFO, and each commit consumes its own.
 *
 * **Why the queue is BOUNDED.** Most platforms echo nothing at all (iOS, the browser, a desktop
 * without a key-typed event), so entries that no commit will ever claim are the normal case, not the
 * exception. An unbounded queue would grow for the life of the surface; at [MAX_PENDING] the oldest
 * is dropped, which is also the oldest that could still plausibly be in flight.
 *
 * **Which way it fails.** The two paths are genuinely indistinguishable in one case — a stale
 * pending echo and a character the user really typed — so this gate has to choose which mistake to
 * make. Swallowing a keystroke is worse than sending an extra one: an extra character is visible and
 * the user deletes it, whereas a swallowed one looks like broken hardware and silently corrupts what
 * a program reads. So suppression is deliberately conservative:
 *
 * - a commit is dropped ONLY when a pending echo matches it exactly;
 * - the match consumes that entry AND everything older than it — echoes keep the order of the
 *   presses that produced them, so an older one that has not arrived by now never will;
 * - a commit that matches NOTHING empties the queue: no outstanding echo explains it, so those
 *   entries are stale and keeping them could only swallow a later real keystroke;
 * - [composing] and [clear] empty it too (a live composition and a focus change are both boundaries
 *   a hardware key's echo cannot cross).
 *
 * What is left is the one unavoidable hole: a press whose echo never arrives, followed — with no
 * boundary in between — by the user genuinely typing that same character through an IME. That
 * character is suppressed once. It takes a platform that echoes some presses and not others, and it
 * costs one character; the alternative (suppressing on anything looser than an exact match) costs
 * characters the user really typed.
 */
internal class TerminalTextGate {
    private val pending = ArrayDeque<String>()

    /** A hardware key committed [text]; the matching IME echo is now expected. */
    fun submitted(text: String) {
        // A key that produced no text (an arrow, Ctrl-C, F5) neither creates an echo nor cancels
        // one: it is not a text event, and the presses before it may still be waiting for theirs.
        if (text.isEmpty()) return
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(text)
    }

    /** The text an IME committed, or null when it is the echo of a hardware key press. */
    fun commit(text: String): String? {
        if (text.isEmpty()) return null
        val matched = pending.indexOf(text)
        if (matched < 0) {
            pending.clear()
            return text
        }
        repeat(matched + 1) { pending.removeFirst() }
        return null
    }

    /**
     * An IME composition is live. Whatever it commits is the composition's, not a key press's, so
     * the presses still waiting for an echo have missed their chance.
     */
    fun composing() {
        pending.clear()
    }

    /** Focus changed, or the surface was rebound: forget what was in flight. */
    fun clear() {
        pending.clear()
    }

    private companion object {
        /** Echoes that may be outstanding at once. Eight is more presses than an echo ever lags. */
        const val MAX_PENDING = 8
    }
}

/**
 * Hardware key events to [TerminalKey]s: physical key, modifiers, press/repeat/release, and the
 * committed text exactly once.
 *
 * **Repeat.** No Compose platform reports "this is an auto-repeat" in common code, so a key that is
 * already held and goes down again IS the repeat — which is what the platform's own repeat rate
 * produces, and what [KeyAction.REPEAT] means. Holding the key therefore reaches the program as
 * press, repeat, repeat, …, release, which is what the kitty keyboard protocol needs in order to
 * report it correctly and what an ordinary terminal collapses to the same bytes over and over.
 *
 * **Encoding.** Nothing here writes a byte. The engine's pinned Ghostty encoder does, under the
 * modes the program negotiated (`setopt_from_terminal` before every encode): application cursor
 * keys, the kitty keyboard protocol, `modifyOtherKeys`, the backarrow mode. Re-deriving any of that
 * in Kotlin would be a second, divergent implementation of a protocol the engine already owns.
 */
internal class TerminalKeyRouter(
    private val send: (TerminalKey) -> Unit,
    private val armedModifiers: () -> Int = { Modifiers.NONE },
    private val onSubmitted: () -> Unit = {},
) {
    private val held = mutableSetOf<Long>()
    private val gate = TerminalTextGate()

    /**
     * Route one Compose key event; returns true when it was consumed (and must not reach focus
     * traversal, a parent shortcut handler or the platform).
     */
    fun handle(event: KeyEvent): Boolean {
        // KeyEventType.Unknown is a platform text event (AWT's KEY_TYPED) that repeats the
        // character its key-down already carried: the gate's job, done one layer earlier.
        val action = when (event.type) {
            KeyEventType.KeyDown -> if (!held.add(event.key.keyCode)) KeyAction.REPEAT else KeyAction.PRESS
            KeyEventType.KeyUp -> {
                held.remove(event.key.keyCode)
                KeyAction.RELEASE
            }
            else -> return false
        }
        val code = physicalCodeOf(event.key)
        // Text only on the way down: a release carries the key, never the character again.
        val text = if (action == KeyAction.RELEASE) "" else committedTextOf(event)
        if (code == TerminalKeys.UNIDENTIFIED && text.isEmpty()) {
            // A bare modifier, a media key, something this terminal has no name for: leave it for
            // whoever else is listening rather than swallowing it.
            return false
        }
        if (action != KeyAction.RELEASE) gate.submitted(text)
        send(TerminalKey(code, text, modifiersOf(event, armedModifiers()), action))
        if (action != KeyAction.RELEASE) onSubmitted()
        return true
    }

    /**
     * An IME composition is live ([TerminalImeState.marked] is not empty): a hardware key's echo
     * cannot be what comes out of it, so nothing that was waiting for one still is.
     */
    fun composing() {
        gate.composing()
    }

    /**
     * Text an IME committed, dropped when it is the echo of a hardware key that just ran. Returns
     * true when something was sent.
     */
    fun commitText(text: String): Boolean {
        val effective = gate.commit(text) ?: return false
        send(TerminalKey(TerminalKeys.UNIDENTIFIED, effective, armedModifiers(), KeyAction.PRESS))
        onSubmitted()
        return true
    }

    /**
     * Focus left (or the surface was rebound). Keys held at that moment will never produce a
     * key-up here, so forget them — otherwise the next press of one of them would be reported as a
     * repeat of a press the program never saw.
     */
    fun reset() {
        held.clear()
        gate.clear()
    }
}
