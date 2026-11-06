// The desktop overlay's hold-to-talk key (#62): which one, from a short list per OS that works and clashes
// with nothing the OS owns.
package dev.supermux.desktop.overlay

// macOS: Carbon's modifier bits and the space bar's key code.
private const val MAC_CMD = 0x100
private const val MAC_SHIFT = 0x200
private const val MAC_OPT = 0x800
private const val MAC_CTRL = 0x1000
private const val MAC_SPACE = 49
// Windows: RegisterHotKey's modifier bits and the space bar's virtual key.
private const val WIN_ALT = 0x1
private const val WIN_CTRL = 0x2
private const val WIN_SHIFT = 0x4
private const val WIN_WINDOWS = 0x8
private const val WIN_SPACE = 0x20
// X11: the core modifier masks and the space bar's keysym.
private const val X_SHIFT = 0x1
private const val X_CTRL = 0x4
private const val X_ALT = 0x8 // Mod1
private const val X_SUPER = 0x40 // Mod4
private const val X_SPACE = 0x20 // XK_space

/** The desktop OSes, as `os.name` tells them apart. */
enum class DesktopOs { MAC, WINDOWS, LINUX }

fun desktopOsOf(osName: String): DesktopOs = when {
    osName.startsWith("Mac") -> DesktopOs.MAC
    osName.startsWith("Windows") -> DesktopOs.WINDOWS
    else -> DesktopOs.LINUX
}

/** A key combination on one OS: how it reads there, and the codes its hot-key API takes. */
data class KeyCombo(val label: String, val modifiers: Int, val key: Int)

/**
 * How you hold to talk from any app. The combos are system-wide hot keys, which need no permission: Carbon
 * RegisterEventHotKey on a Mac (as Spotlight's and Raycast's), RegisterHotKey on Windows, a passive key grab on
 * X11 (Wayland only through its GlobalShortcuts portal). ⌥Space (Alt+Space),
 * two keys under one hand, was checked on macOS 26 (press and release, no prompt); the rest are for when
 * another app has it. ⌥ stays with Space only: ⌥ with a letter types a character, and macOS 15+ may hold
 * such hot keys back. [RIGHT_OPTION] reads the right ⌥ key held on its own, which takes a key tap and so
 * macOS's Input Monitoring: opt-in only. Each OS offers only what it has room for ([offeredOn]).
 */
enum class TalkKey(private val mac: KeyCombo?, private val windows: KeyCombo?, private val linux: KeyCombo?) {
    // On Linux the window manager may hold it (the window menu on GNOME, KDE and Xfce): X says so.
    OPTION_SPACE(KeyCombo("⌥Space", MAC_OPT, MAC_SPACE), KeyCombo("Alt+Space", WIN_ALT, WIN_SPACE), KeyCombo("Alt+Space", X_ALT, X_SPACE)),
    // Not on Windows: Win+Shift+Space switches the keyboard layout back; not on Linux: Super+Shift+Space on GNOME.
    COMMAND_SHIFT_SPACE(KeyCombo("⌘⇧Space", MAC_CMD or MAC_SHIFT, MAC_SPACE), null, null),
    CONTROL_SHIFT_SPACE(
        KeyCombo("⌃⇧Space", MAC_CTRL or MAC_SHIFT, MAC_SPACE),
        KeyCombo("Ctrl+Shift+Space", WIN_CTRL or WIN_SHIFT, WIN_SPACE),
        KeyCombo("Ctrl+Shift+Space", X_CTRL or X_SHIFT, X_SPACE),
    ),
    CONTROL_OPTION_COMMAND_SPACE(KeyCombo("⌃⌥⌘Space", MAC_CTRL or MAC_OPT or MAC_CMD, MAC_SPACE), null, null),
    // Not on a Mac: ⌃⌥Space switches input sources there. Linux's default: its Alt+Space is the window menu.
    CONTROL_ALT_SPACE(null, KeyCombo("Ctrl+Alt+Space", WIN_CTRL or WIN_ALT, WIN_SPACE), KeyCombo("Ctrl+Alt+Space", X_CTRL or X_ALT, X_SPACE)),
    RIGHT_OPTION(KeyCombo("Right ⌥ alone", 0, 0), null, null),
    ;

    /** This key on [os]; null where it isn't offered. */
    fun on(os: DesktopOs): KeyCombo? = when (os) {
        DesktopOs.MAC -> mac
        DesktopOs.WINDOWS -> windows
        DesktopOs.LINUX -> linux
    }

    /** A key combination (a hot key), not a key held on its own. */
    val combo: Boolean get() = this != RIGHT_OPTION

    /** macOS asks for Input Monitoring before this one works. */
    val needsInputMonitoring: Boolean get() = this == RIGHT_OPTION

    companion object {
        const val MAC_COMMAND = MAC_CMD
        const val MAC_OPTION = MAC_OPT
        const val MAC_CONTROL = MAC_CTRL
        const val MAC_SPACE_KEY = MAC_SPACE
        const val WIN_ALT_MOD = WIN_ALT
        const val WIN_CTRL_MOD = WIN_CTRL
        const val WIN_WINDOWS_MOD = WIN_WINDOWS
        const val WIN_SPACE_KEY = WIN_SPACE
        const val X_SUPER_MOD = X_SUPER

        val DEFAULT = OPTION_SPACE

        /** ⌥Space / Alt+Space, but Ctrl+Alt+Space on Linux, whose window managers hold Alt+Space. */
        fun defaultOn(os: DesktopOs): TalkKey = if (os == DesktopOs.LINUX) CONTROL_ALT_SPACE else DEFAULT

        /** The keys [os] offers, in the menu's order. */
        fun offeredOn(os: DesktopOs): List<TalkKey> = entries.filter { it.on(os) != null }

        /** The stored choice; the default when there is none, it is unknown, or [os] doesn't offer it. */
        fun parse(stored: String?, os: DesktopOs): TalkKey = entries.firstOrNull { it.name == stored && it.on(os) != null } ?: defaultOn(os)
    }
}
