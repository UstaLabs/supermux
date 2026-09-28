package dev.supermux.editor.core

/**
 * What a command runs against: the current state, and a way to ask for an update.
 *
 * A command hands over a [TransactionSpec], not a built transaction: the target (the view) builds
 * the transaction from its own current state, so it stays free to filter or amend it first.
 */
interface CommandTarget {
    val state: EditorState
    fun dispatch(spec: TransactionSpec)
}

/** An action. Returns true when it did something (so the key that triggered it is consumed). */
fun interface Command {
    fun run(target: CommandTarget): Boolean
}

/** A command with a stable id and a human title, so menus and a command palette can list it. */
data class NamedCommand(val id: String, val title: String, val command: Command)

/**
 * A key chord. Written like `Mod-s`, `Shift-Tab`, `Ctrl-Space`, `Alt-ArrowUp`, `Mod-Shift-z`.
 * `Mod` is Cmd on Apple platforms and Ctrl elsewhere; it is resolved by [parse].
 */
data class KeyChord(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    companion object {
        fun parse(spec: String, apple: Boolean): KeyChord {
            // The key itself may be "-" ("Mod--"), so split off modifiers from the left.
            val parts = ArrayList<String>()
            var rest = spec
            while (true) {
                val i = rest.indexOf('-')
                if (i <= 0 || i == rest.length - 1) break
                parts += rest.substring(0, i)
                rest = rest.substring(i + 1)
            }
            var ctrl = false; var alt = false; var shift = false; var meta = false
            for (m in parts) when (m) {
                "Mod" -> if (apple) meta = true else ctrl = true
                "Ctrl", "Control" -> ctrl = true
                "Alt", "Option" -> alt = true
                "Shift" -> shift = true
                "Meta", "Cmd" -> meta = true
                else -> throw IllegalArgumentException("unknown modifier '$m' in '$spec'")
            }
            require(rest.isNotEmpty()) { "no key in '$spec'" }
            val key = if (rest.length == 1) rest.lowercase() else rest
            return KeyChord(key, ctrl, alt, shift, meta)
        }
    }
}

/**
 * One keymap entry, as a plugin declares it.
 *
 * [key] is parsed here, once, for both platforms: a malformed spec throws
 * [IllegalArgumentException] when the binding is created (so at [keymapOf]), never on a keystroke,
 * and cannot break other bindings. [mac], when given, is the key on Apple platforms instead of
 * [key] (CM6's `mac:`): redo is `KeyBinding("Mod-y", redo, mac = "Mod-Shift-z")`.
 */
data class KeyBinding(val key: String, val command: Command, val mac: String? = null) {
    private val appleChord = KeyChord.parse(mac ?: key, apple = true)
    private val otherChord = KeyChord.parse(key, apple = false)

    /** The chord this binding matches on an Apple platform ([apple]) or elsewhere. */
    fun chord(apple: Boolean): KeyChord = if (apple) appleChord else otherChord
}

/** Every plugin's key bindings, highest precedence first. The surface tries them in order. */
val keymapFacet: Facet<List<KeyBinding>, List<KeyBinding>> = Facet.define("keymap") { it.flatten() }

/** Every plugin's named commands, highest precedence first. */
val commandsFacet: Facet<List<NamedCommand>, List<NamedCommand>> = Facet.define("commands") { it.flatten() }

fun keymapOf(vararg bindings: KeyBinding): Extension = keymapFacet.of(bindings.toList())

/**
 * Run the first binding for [chord] whose command returns true. This is the whole key-dispatch
 * rule; the surface only turns a platform key event into a [KeyChord].
 */
fun runKey(target: CommandTarget, chord: KeyChord, apple: Boolean): Boolean {
    for (b in target.state.facet(keymapFacet)) {
        if (b.chord(apple) == chord && b.command.run(target)) return true
    }
    return false
}
