package dev.supermux.net

/**
 * Byte sequences for the mobile terminal key-accessory bar (Esc/Tab/Ctrl/Alt/
 * arrows the soft keyboard lacks). Shared-Kotlin port of the web reference
 * (src/web-app/src/lib/terminal-keys.ts) so the Android native terminal drives
 * the SAME, tested logic as the PWA — mirroring how TerminalScroll.kt ports the
 * touch-scroll math.
 *
 * Pure logic (no Compose, no termlib): callers decide WHEN to build a sequence
 * (which button, which sticky modifier); this only answers WHAT bytes a key
 * produces. Encode the result with `.encodeToByteArray()` before sending it down
 * the pty — every character here is in the 7-bit ASCII range, so UTF-8 encodes
 * each as one byte.
 *
 * Sequences follow the xterm / DEC conventions the shell already speaks:
 *   - arrows/Home/End switch between CSI (`ESC[…`) and SS3 (`ESCO…`) form
 *     depending on DECCKM (application cursor keys). termlib doesn't expose that
 *     mode, so the Android caller passes appCursor=false; the CSI form works
 *     everywhere (including vim/tmux), it's just not the SS3 variant.
 *   - a Ctrl/Alt modifier forces the CSI *parameterised* form
 *     (`ESC[1;<mod><final>`), where mod = 1 + alt*2 + ctrl*4 (+ shift, unused).
 */

data class Mods(val ctrl: Boolean, val alt: Boolean)

enum class SpecialKey {
    Escape, Tab, ArrowUp, ArrowDown, ArrowRight, ArrowLeft, Home, End, PageUp, PageDown
}

// ESC (0x1b) as a Char, built from its code point so the source stays ASCII.
private val ESC: Char = 27.toChar()

// Final byte of the CSI/SS3 cursor-style sequences, per key.
private val CURSOR_FINAL: Map<SpecialKey, String> = mapOf(
    SpecialKey.ArrowUp to "A",
    SpecialKey.ArrowDown to "B",
    SpecialKey.ArrowRight to "C",
    SpecialKey.ArrowLeft to "D",
    SpecialKey.Home to "H",
    SpecialKey.End to "F",
)

// Keys whose parameterised form is `CSI <n> ; <mod> ~` rather than a final letter.
private val TILDE_NUM: Map<SpecialKey, String> = mapOf(
    SpecialKey.PageUp to "5",
    SpecialKey.PageDown to "6",
)

/** xterm modifier parameter: 1 + shift + alt*2 + ctrl*4. We never emit Shift. */
private fun modParam(mods: Mods): Int = 1 + (if (mods.alt) 2 else 0) + (if (mods.ctrl) 4 else 0)

/**
 * Bytes for a named special key given the active modifiers and whether the
 * terminal is in application-cursor-keys mode.
 */
fun specialKeySequence(key: SpecialKey, mods: Mods, appCursor: Boolean): String {
    val modified = mods.ctrl || mods.alt

    when (key) {
        SpecialKey.Escape -> return ESC.toString()
        SpecialKey.Tab -> return 9.toChar().toString()
        else -> {}
    }

    TILDE_NUM[key]?.let { n ->
        return if (modified) "$ESC[$n;${modParam(mods)}~" else "$ESC[$n~"
    }

    CURSOR_FINAL[key]?.let { final ->
        // A modifier always forces the parameterised CSI form; without one we honour
        // application-cursor mode (SS3) vs normal (CSI).
        if (modified) return "$ESC[1;${modParam(mods)}$final"
        return if (appCursor) "${ESC}O$final" else "$ESC[$final"
    }

    return ""
}

/** Turn a printable character into the control code Ctrl+<char> sends. */
private fun controlCode(ch: Char): String {
    if (ch == '?') return 127.toChar().toString() // Ctrl-? is DEL, not 0x3f & 0x1f
    return (ch.uppercaseChar().code and 0x1f).toChar().toString()
}

/**
 * Bytes for a single printable character under the active modifiers.
 * Ctrl maps letters/punctuation to control codes; Alt prefixes ESC.
 */
fun printableSequence(ch: Char, mods: Mods): String {
    val base = if (mods.ctrl) controlCode(ch) else ch.toString()
    return if (mods.alt) ESC + base else base
}
