package dev.supermux.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.supermux.terminal.KeyAction
import dev.supermux.terminal.Modifiers
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalKeys

/**
 * The keys a phone keyboard does not have, and the text that did not come from a keyboard at all.
 *
 * A soft keyboard has no Ctrl, no Esc, no arrows and no Tab, so a mobile terminal grows a bar of
 * accessory buttons above it. Two kinds of button live there:
 *
 * - **Armed modifiers** ([ctrl], [alt], [shift]): sticky, one-shot. Tapping `Ctrl` arms it, the next
 *   key — from the bar or from a hardware keyboard — is sent with [Modifiers.CTRL] set, and it
 *   disarms itself. That is the sticky-keys behaviour every mobile terminal uses, and it is why the
 *   arming lives HERE rather than in the key router: it has to survive between two independent
 *   input events and be visible to the UI drawing the button's pressed state.
 * - **Direct keys** ([sendKey]): Esc, Tab, the arrows, F-keys — a physical [TerminalKeys] code with
 *   no character of its own.
 *
 * [paste] is here for the same reason: it is input that did not come from the keyboard. It goes
 * through the engine's paste API, never through synthesized key events — see its documentation.
 *
 * **Per terminal.** One state belongs to one [Terminal]: [Terminal] binds it while it is composed,
 * and CLEARS the armed modifiers whenever focus changes, because a `Ctrl` armed against a terminal
 * the user has left would otherwise fire into whatever they look at next. Before it is bound (and
 * after the terminal leaves the composition) [sendKey] and [paste] do nothing — there is nowhere to
 * send to. Passing one state to two terminals binds it to the last one composed; give each terminal
 * its own.
 *
 * Read and written on the composition's thread only.
 */
@Stable
class TerminalAccessoryState {

    /** Armed Ctrl: the next key carries [Modifiers.CTRL]. */
    var ctrl: Boolean by mutableStateOf(false)

    /** Armed Alt/Option: the next key carries [Modifiers.ALT]. */
    var alt: Boolean by mutableStateOf(false)

    /** Armed Shift: the next key carries [Modifiers.SHIFT]. */
    var shift: Boolean by mutableStateOf(false)

    private var sink: Sink? = null

    /** The [Modifiers] bit set the armed keys contribute to the next key event. */
    val armedModifiers: Int
        get() {
            var modifiers = Modifiers.NONE
            if (ctrl) modifiers = modifiers or Modifiers.CTRL
            if (alt) modifiers = modifiers or Modifiers.ALT
            if (shift) modifiers = modifiers or Modifiers.SHIFT
            return modifiers
        }

    /** True while any modifier is armed. */
    val armed: Boolean get() = ctrl || alt || shift

    /** What a bar's `Ctrl` button does: arm it if it is not, disarm it if it is. */
    fun toggleCtrl() { ctrl = !ctrl }

    fun toggleAlt() { alt = !alt }

    fun toggleShift() { shift = !shift }

    /** Disarm everything: the terminal calls this on every focus change. */
    fun clear() {
        ctrl = false
        alt = false
        shift = false
    }

    /**
     * Send one physical key — a [TerminalKeys] code, optionally with the character it produces —
     * with the armed modifiers applied, and disarm them.
     *
     * A press AND a release are sent, in that order: under the kitty keyboard protocol a program
     * that asked for release events would otherwise see a key that is still held.
     */
    fun sendKey(physicalCode: Int, text: String = "") {
        val target = sink ?: return
        val modifiers = armedModifiers
        clear()
        target.key(TerminalKey(physicalCode, text, modifiers, KeyAction.PRESS))
        target.key(TerminalKey(physicalCode, "", modifiers, KeyAction.RELEASE))
    }

    /**
     * Paste [text] through the engine's paste API.
     *
     * NOT a sequence of synthesized key presses: a paste is one operation the terminal knows about,
     * and only the engine can wrap it in bracketed-paste markers when (and exactly once when) the
     * program has mode 2004 on, convert its newlines the way a paste's newlines are converted — a
     * pasted `\n` is not the Enter key, and a program in bracketed-paste mode can tell them apart —
     * and refuse text that would inject a command. Key events would lose all three.
     *
     * Unsafe text (a newline outside bracketed paste, a stray end marker inside it) is NOT sent:
     * [onResult] is called with `false` so the host can confirm with the user and call again with
     * [allowUnsafe] `true`. [onResult] runs on the composition's dispatcher once the engine has
     * answered.
     */
    fun paste(text: String, allowUnsafe: Boolean = false, onResult: (Boolean) -> Unit = {}) {
        sink?.paste(text, allowUnsafe, onResult)
    }

    /** What [Terminal] installs while it is composed; see the class documentation. */
    internal interface Sink {
        fun key(key: TerminalKey)
        fun paste(text: String, allowUnsafe: Boolean, onResult: (Boolean) -> Unit)
    }

    internal fun bind(sink: Sink) {
        this.sink = sink
    }

    /** The terminal this state is bound to, if any. */
    internal fun boundSink(): Sink? = sink

    internal fun unbind(sink: Sink) {
        if (this.sink === sink) {
            this.sink = null
            clear()
        }
    }
}

/** A [TerminalAccessoryState] for one [Terminal]; hoist it to drive an accessory bar of your own. */
@Composable
fun rememberTerminalAccessories(): TerminalAccessoryState = remember { TerminalAccessoryState() }
