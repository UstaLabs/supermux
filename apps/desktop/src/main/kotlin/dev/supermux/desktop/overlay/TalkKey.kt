// The desktop overlay's hold-to-talk key (#62): which one, from a short list that works and clashes with
// nothing macOS owns.
package dev.supermux.desktop.overlay

// Carbon's modifier bits and the space bar's key code.
private const val MOD_CMD = 0x100
private const val MOD_SHIFT = 0x200
private const val MOD_OPT = 0x800
private const val MOD_CTRL = 0x1000
private const val KEY_SPACE = 49

/**
 * How you hold to talk from any app. The combos are system-wide hot keys (Carbon RegisterEventHotKey on
 * a Mac), which need no permission, as Spotlight's and Raycast's don't; each holds ⌘ or ⌃, because since
 * macOS 15 a hot key held with ⌥ alone is never delivered. [RIGHT_OPTION] reads the right ⌥ key held on
 * its own, which takes a key tap and so macOS's Input Monitoring: opt-in only.
 */
enum class TalkKey(val label: String, val carbonModifiers: Int, val carbonKey: Int) {
    COMMAND_SHIFT_SPACE("⌘⇧Space", MOD_CMD or MOD_SHIFT, KEY_SPACE),
    CONTROL_SHIFT_SPACE("⌃⇧Space", MOD_CTRL or MOD_SHIFT, KEY_SPACE),
    CONTROL_OPTION_COMMAND_SPACE("⌃⌥⌘Space", MOD_CTRL or MOD_OPT or MOD_CMD, KEY_SPACE),
    RIGHT_OPTION("Right ⌥ alone", 0, 0),
    ;

    /** A key combination (a hot key), not a key held on its own. */
    val combo: Boolean get() = this != RIGHT_OPTION

    /** macOS asks for Input Monitoring before this one works. */
    val needsInputMonitoring: Boolean get() = this == RIGHT_OPTION

    companion object {
        const val CMD = MOD_CMD
        const val SHIFT = MOD_SHIFT
        const val OPT = MOD_OPT
        const val CTRL = MOD_CTRL
        const val SPACE = KEY_SPACE

        val DEFAULT = COMMAND_SHIFT_SPACE

        /** The stored choice; the default when there is none or it is unknown. */
        fun parse(stored: String?): TalkKey = entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}
