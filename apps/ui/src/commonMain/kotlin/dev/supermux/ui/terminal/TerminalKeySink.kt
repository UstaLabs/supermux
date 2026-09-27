// Cluster G1: the key-accessory half of the terminal seam. The whole sticky-modifier state machine
// is PURE (`:shared` builds every byte sequence), so it lives here rather than in either host — a
// shared key bar (cluster G3's `TerminalKeyBar` under Touch) drives the active surface through it.
package dev.supermux.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.supermux.net.Mods
import dev.supermux.net.SpecialKey
import dev.supermux.net.printableSequence
import dev.supermux.net.specialKeySequence

/** Tri-state of a sticky bar modifier (like iOS Shift): off → armed-for-one-key → locked. */
enum class TerminalModState { OFF, ONCE, LOCKED }

enum class TerminalModKey { CTRL, ALT }

/** One key press reported by a key bar. (`Mod`, not `Modifier` — that name is Compose's.) */
sealed interface TerminalKey {
    data class Mod(val key: TerminalModKey) : TerminalKey
    data class Special(val key: SpecialKey) : TerminalKey
    data class Printable(val ch: Char) : TerminalKey
}

/**
 * The keys a terminal surface accepts from outside its own grid — the accessory bar's route into
 * the pty, and the modifier state that bar renders.
 *
 * Ownership matters: the sink belongs to the SURFACE (it is reached through
 * [TerminalSurface.keys]), so a bar drawn anywhere in the shell — including outside the surface's
 * own subtree, which is what a shared bar pinned above the IME needs — always types into the pane
 * it was handed, and a background pane's armed Ctrl cannot leak into the foreground one.
 *
 * Behaviour is the rule the retired Android renderer's `onKeyboardInput` had (itself the web
 * `TerminalPane.vue` rule), lifted here unchanged when it stopped being Android's alone:
 * a modifier press cycles off → once → locked; any other key is encoded with the modifiers
 * currently held (`appCursor = false` — no client exposes DECCKM) and sent, after which a `once`
 * modifier is consumed and a `locked` one stays armed.
 *
 * WHO ENCODES. [semantic] is the difference between a host whose emulator takes BYTES and one whose
 * emulator takes KEYS. The shared Ghostty renderer is the second kind: it owns application-cursor
 * mode, the kitty keyboard protocol, `modifyOtherKeys` and the backarrow mode, and re-deriving any
 * of that here would be a second implementation of a protocol it already negotiated — and, worse,
 * a SECOND transformation on top of its own, which is how an armed Ctrl arrived as a control code
 * the emulator then treated as a fresh keystroke. When [semantic] is set this sink stops encoding
 * entirely and hands the press over whole; the bar's tri-state stays exactly as it was, because a
 * sticky Ctrl is a supermux affordance and not something any emulator models.
 */
@Stable
class TerminalKeySink(
    // `semantic` and `onHideKeyboard` both come BEFORE `send` so `send` stays the trailing
    // parameter: every byte-sink call site in the tree is `TerminalKeySink { bytes -> … }`, and a
    // trailing lambda binds to the LAST parameter. Putting either at the end would have silently
    // rebound those call sites to it — the compiler only catches that when the lambda shapes
    // differ, and `() -> Unit` vs `(ByteArray) -> Unit` do, but nothing guarantees the next one
    // will.
    private val semantic: ((TerminalKey, Mods) -> Unit)? = null,
    // The bar's "hide keyboard" button. No-op by default: most sinks (a raw byte sink, a test
    // fake) have never needed it, and hiding the IME is not something that belongs in `semantic`
    // — it is not a keystroke the emulator encodes, and it must never touch the armed modifiers.
    private val onHideKeyboard: () -> Unit = {},
    private val send: (ByteArray) -> Unit,
) {
    var ctrl: TerminalModState by mutableStateOf(TerminalModState.OFF)
        private set

    var alt: TerminalModState by mutableStateOf(TerminalModState.OFF)
        private set

    /** The modifiers a keystroke should carry right now. */
    val mods: Mods get() = Mods(ctrl != TerminalModState.OFF, alt != TerminalModState.OFF)

    /** True when a bar modifier is armed, so the REAL keyboard's next keystroke carries it too. */
    val armed: Boolean get() = ctrl != TerminalModState.OFF || alt != TerminalModState.OFF

    fun press(key: TerminalKey) {
        when (key) {
            is TerminalKey.Mod -> {
                fun next(s: TerminalModState) = when (s) {
                    TerminalModState.OFF -> TerminalModState.ONCE
                    TerminalModState.ONCE -> TerminalModState.LOCKED
                    TerminalModState.LOCKED -> TerminalModState.OFF
                }
                if (key.key == TerminalModKey.CTRL) ctrl = next(ctrl) else alt = next(alt)
                return
            }
            else -> {
                val route = semantic
                if (route != null) {
                    // The emulator encodes it, ONCE. Nothing here touches the bytes.
                    route(key, mods)
                    consumeOnce()
                    return
                }
                when (key) {
                    is TerminalKey.Special -> {
                        val seq = specialKeySequence(key.key, mods, appCursor = false)
                        if (seq.isNotEmpty()) send(seq.encodeToByteArray())
                    }
                    is TerminalKey.Printable -> send(printableSequence(key.ch, mods).encodeToByteArray())
                    is TerminalKey.Mod -> return // unreachable: handled above
                }
            }
        }
        consumeOnce()
    }

    /**
     * Drop any `once` modifier after it has modified a keystroke this sink did not send itself —
     * the surface calls this when the REAL keyboard consumed the armed modifier. `locked` stays.
     */
    fun consumeOnce() {
        if (ctrl == TerminalModState.ONCE) ctrl = TerminalModState.OFF
        if (alt == TerminalModState.ONCE) alt = TerminalModState.OFF
    }

    /**
     * Disarm everything, `locked` included — the pane stopped being the one the user is typing at.
     *
     * [consumeOnce] is "a keystroke used it up"; this is "there is no next keystroke here". A
     * background tab that kept a locked Ctrl would fire it into whatever the user came back to,
     * and a lock the user can no longer SEE (the bar is only drawn for the active pane) is a
     * modifier they have no way to turn off.
     */
    fun clearArmed() {
        ctrl = TerminalModState.OFF
        alt = TerminalModState.OFF
    }

    /**
     * Re-encode a REAL keystroke with whatever bar modifier is armed — the other half of the sink,
     * and the one without which a bar Ctrl is decorative.
     *
     * The bar itself only carries Esc/Tab/Ctrl/Alt/arrows: there is no `Ctrl` and no `C` on it, so
     * "Ctrl-C" is always the bar arming Ctrl and the SOFT KEYBOARD typing `c`. That keystroke does
     * not come through [press] — it comes from the grid — so every surface has to route it here.
     *
     * Returns the bytes to send INSTEAD of [data] (consuming a `once` as it goes), or null when the
     * keystroke passes through untouched. Null also means "predict this one": a control code is not
     * printable, so a predicted local echo of it would paint a glyph the server never sends (web
     * parity — `TerminalPane.vue` skips the echo on the same condition).
     *
     * Only a SINGLE printable ASCII char (0x20–0x7e) is transformed. Control keys, Enter,
     * multi-byte UTF-8 and IME composition pass through: a modifier has no defined meaning for them
     * and mangling them would break every non-ASCII keyboard.
     */
    fun applyArmedModifiers(data: ByteArray): ByteArray? {
        if (!armed) return null
        val ch = singlePrintableChar(data) ?: return null
        val bytes = printableSequence(ch, mods).encodeToByteArray()
        consumeOnce()
        return bytes
    }

    /**
     * Hide the soft keyboard, without touching [ctrl]/[alt] or moving focus away from the pane —
     * see [TerminalKeyBar]'s "hide keyboard" button and `TerminalInputController.hideKeyboard`,
     * which is what a semantic sink's [onHideKeyboard] ultimately reaches.
     */
    fun hideKeyboard() = onHideKeyboard()
}

/**
 * A single printable ASCII char (0x20–0x7e) from a keystroke's bytes, or null.
 *
 * Was private to Android's own terminal view; lifted here in H5 when iOS needed the identical
 * rule, so no two hosts can drift on which keystrokes an armed bar modifier may transform. Both
 * of those views are gone — there is one renderer now — and the rule stayed.
 */
fun singlePrintableChar(data: ByteArray): Char? {
    if (data.size != 1) return null
    val b = data[0].toInt() and 0xff
    return if (b in 0x20..0x7e) b.toChar() else null
}

/**
 * A sink bound to [send] (a terminal client's input queue), remembered for the composition.
 *
 * Deliberately NOT keyed on [send]: a call site passing a fresh lambda each recomposition would
 * otherwise get a fresh sink and lose the armed modifier mid-chord. The latest lambda is reached
 * through [rememberUpdatedState] instead.
 */
@Composable
fun rememberTerminalKeySink(send: (ByteArray) -> Unit): TerminalKeySink {
    val current by rememberUpdatedState(send)
    return remember { TerminalKeySink { bytes -> current(bytes) } }
}

/**
 * A sink whose presses are handed to [press] as KEYS, not bytes — for a host whose emulator does
 * its own encoding. Same non-keying rule as [rememberTerminalKeySink], for the same reason.
 *
 * [hideKeyboard] wires the bar's "hide keyboard" button through to whatever the host's real IME
 * controller is; the default no-ops for a caller that never draws that button.
 */
@Composable
fun rememberSemanticTerminalKeySink(
    hideKeyboard: () -> Unit = {},
    press: (TerminalKey, Mods) -> Unit,
): TerminalKeySink {
    val current by rememberUpdatedState(press)
    val currentHideKeyboard by rememberUpdatedState(hideKeyboard)
    return remember {
        TerminalKeySink(
            send = { },
            semantic = { key, mods -> current(key, mods) },
            onHideKeyboard = { currentHideKeyboard() },
        )
    }
}
