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
 * Behaviour is Android's `TermlibTerminalView.onKeyboardInput` verbatim (itself the web `TerminalPane.vue`
 * rule): a modifier press cycles off → once → locked; any other key is encoded with the modifiers
 * currently held (`appCursor = false` — no client exposes DECCKM) and sent, after which a `once`
 * modifier is consumed and a `locked` one stays armed.
 */
@Stable
class TerminalKeySink(private val send: (ByteArray) -> Unit) {
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
            is TerminalKey.Special -> {
                val seq = specialKeySequence(key.key, mods, appCursor = false)
                if (seq.isNotEmpty()) send(seq.encodeToByteArray())
            }
            is TerminalKey.Printable -> send(printableSequence(key.ch, mods).encodeToByteArray())
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
