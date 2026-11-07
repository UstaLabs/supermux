package dev.supermux.ui.keys

import androidx.compose.ui.input.key.Key
import dev.supermux.editor.core.KeyChord

/** A chord as this device writes it: `⌃⌥⇧⌘N` on Apple (Apple's modifier order), `Ctrl+Alt+Shift+N` elsewhere. */
fun formatChord(chord: KeyChord, os: KeyOs): String {
    val key = keyLabel(chord.key, os)
    if (os == KeyOs.Apple) {
        return buildString {
            if (chord.ctrl) append('⌃')
            if (chord.alt) append('⌥')
            if (chord.shift) append('⇧')
            if (chord.meta) append('⌘')
            append(key)
        }
    }
    return buildList {
        if (chord.ctrl) add("Ctrl")
        if (chord.alt) add("Alt")
        if (chord.shift) add("Shift")
        if (chord.meta) add("Super")
        add(key)
    }.joinToString("+")
}

private fun keyLabel(key: String, os: KeyOs): String = when (key) {
    "Enter" -> if (os == KeyOs.Apple) "↩" else "Enter"
    "Escape" -> if (os == KeyOs.Apple) "⎋" else "Esc"
    "Backspace" -> if (os == KeyOs.Apple) "⌫" else "Backspace"
    "Tab" -> if (os == KeyOs.Apple) "⇥" else "Tab"
    "ArrowUp" -> "↑"
    "ArrowDown" -> "↓"
    "ArrowLeft" -> "←"
    "ArrowRight" -> "→"
    else -> if (key.length == 1) key.uppercase() else key
}

/** The editor's key names ([KeyChord.key]) for the Compose keys a menu accelerator can carry. */
private val NAMED_KEYS: Map<String, Key> = buildMap {
    val letters = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z)
    letters.forEachIndexed { i, k -> put(('a' + i).toString(), k) }
    val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    digits.forEachIndexed { i, k -> put(i.toString(), k) }
    put(",", Key.Comma); put(".", Key.Period); put("/", Key.Slash); put("\\", Key.Backslash)
    put("[", Key.LeftBracket); put("]", Key.RightBracket); put("-", Key.Minus); put("=", Key.Equals)
    put(";", Key.Semicolon); put("'", Key.Apostrophe); put("`", Key.Grave)
    put("Enter", Key.Enter); put("Escape", Key.Escape); put("Tab", Key.Tab); put("Space", Key.Spacebar)
    // The terminal's Ctrl+Plus and Shift/Ctrl+Insert ([terminalKeeps]).
    put("+", Key.Plus); put("Insert", Key.Insert)
}
private val KEY_NAMES: Map<Key, String> = NAMED_KEYS.entries.associate { (name, key) -> key to name }

/** The Compose [Key] for a [KeyChord.key] name, or null when a menu cannot carry it. */
fun composeKeyOf(name: String): Key? = NAMED_KEYS[name]

/** The [KeyChord.key] name of a Compose [Key], or null. */
fun keyNameOf(key: Key): String? = KEY_NAMES[key]
