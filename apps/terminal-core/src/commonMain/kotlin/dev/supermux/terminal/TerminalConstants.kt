package dev.supermux.terminal

// Package-owned wire constants. These values are FROZEN (TerminalConstantsTest): they cross the
// st_* C ABI, the binary viewport codec and the wasm bridge, and each binding maps them to the
// pinned Ghostty enums in C (table: native/README.md, "Package-owned constant mapping"). Never pass
// a Ghostty ordinal through these fields, and never renumber — only append.

/**
 * Colour encoding for every `Long` colour in this package.
 *
 * ```
 * bit  63..33  32        31..24  23..16  15..8  7..0
 *      0       DEFAULT   R       G       B      A
 * ```
 * - An explicit colour is `0x0000_0000_RRGGBBAA` (unsigned RGBA in the low 32 bits, bit 32 clear).
 * - [DEFAULT] (`0x1_0000_0000`, bit 32 set, RGBA bits zero) means "the terminal's default
 *   foreground/background" — Ghostty reports this as no value; the renderer substitutes
 *   [TerminalColors.foreground]/[TerminalColors.background].
 * - Any value with bits 33..63 set is invalid.
 */
object TerminalColor {
    const val DEFAULT: Long = 0x1_0000_0000L
    const val PALETTE_SIZE: Int = 256

    fun rgba(red: Int, green: Int, blue: Int, alpha: Int = 0xFF): Long {
        require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255)
        return (red.toLong() shl 24) or (green.toLong() shl 16) or (blue.toLong() shl 8) or alpha.toLong()
    }

    /** Opaque colour from `0xRRGGBB`. */
    fun rgb(rgb: Int): Long {
        require(rgb in 0..0xFFFFFF)
        return (rgb.toLong() shl 8) or 0xFF
    }

    fun isDefault(color: Long): Boolean = color and DEFAULT != 0L
    fun isValid(color: Long): Boolean = color ushr 33 == 0L && (!isDefault(color) || color == DEFAULT)
    fun red(color: Long): Int = ((color ushr 24) and 0xFF).toInt()
    fun green(color: Long): Int = ((color ushr 16) and 0xFF).toInt()
    fun blue(color: Long): Int = ((color ushr 8) and 0xFF).toInt()
    fun alpha(color: Long): Int = (color and 0xFF).toInt()
}

/** [CellStyle.flags] bits. */
object CellFlags {
    const val NONE = 0
    const val BOLD = 1 shl 0
    const val ITALIC = 1 shl 1
    const val FAINT = 1 shl 2
    const val BLINK = 1 shl 3
    const val INVERSE = 1 shl 4
    const val INVISIBLE = 1 shl 5
    const val STRIKETHROUGH = 1 shl 6
    const val OVERLINE = 1 shl 7
}

/** [CellStyle.underline] kinds. */
object Underline {
    const val NONE = 0
    const val SINGLE = 1
    const val DOUBLE = 2
    const val CURLY = 3
    const val DOTTED = 4
    const val DASHED = 5
}

/** [TerminalCursor.shape]. */
object CursorShape {
    const val BLOCK = 0
    const val BAR = 1
    const val UNDERLINE = 2
    const val BLOCK_HOLLOW = 3
}

/** Modifier bit set for [TerminalKey.modifiers] and [TerminalMouse.modifiers]. */
object Modifiers {
    const val NONE = 0
    const val SHIFT = 1 shl 0
    const val CTRL = 1 shl 1
    const val ALT = 1 shl 2
    const val SUPER = 1 shl 3
    const val CAPS_LOCK = 1 shl 4
    const val NUM_LOCK = 1 shl 5
}

/** [TerminalKey.action]. */
object KeyAction {
    const val PRESS = 0
    const val RELEASE = 1
    const val REPEAT = 2
}

/** [TerminalMouse.action]. */
object MouseAction {
    const val PRESS = 0
    const val RELEASE = 1
    const val MOTION = 2
}

/** [TerminalMouse.button]. [NONE] = motion with no button held. Wheel "buttons" follow xterm. */
object MouseButton {
    const val NONE = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val MIDDLE = 3
    const val WHEEL_UP = 4
    const val WHEEL_DOWN = 5
    const val WHEEL_LEFT = 6
    const val WHEEL_RIGHT = 7
}

/**
 * [TerminalKey.physicalCode]: layout-independent physical keys, numbered with the USB HID
 * Keyboard/Keypad usage IDs (HID Usage Tables, page 0x07) — a published, stable numbering that the
 * W3C `KeyboardEvent.code` values (and Ghostty's GhosttyKey) are defined against. [UNIDENTIFIED]
 * (0) = a key with no physical code (e.g. IME/soft-keyboard text): only [TerminalKey.text] counts.
 */
object TerminalKeys {
    const val UNIDENTIFIED = 0

    const val A = 0x04; const val B = 0x05; const val C = 0x06; const val D = 0x07
    const val E = 0x08; const val F = 0x09; const val G = 0x0A; const val H = 0x0B
    const val I = 0x0C; const val J = 0x0D; const val K = 0x0E; const val L = 0x0F
    const val M = 0x10; const val N = 0x11; const val O = 0x12; const val P = 0x13
    const val Q = 0x14; const val R = 0x15; const val S = 0x16; const val T = 0x17
    const val U = 0x18; const val V = 0x19; const val W = 0x1A; const val X = 0x1B
    const val Y = 0x1C; const val Z = 0x1D

    const val DIGIT_1 = 0x1E; const val DIGIT_2 = 0x1F; const val DIGIT_3 = 0x20
    const val DIGIT_4 = 0x21; const val DIGIT_5 = 0x22; const val DIGIT_6 = 0x23
    const val DIGIT_7 = 0x24; const val DIGIT_8 = 0x25; const val DIGIT_9 = 0x26
    const val DIGIT_0 = 0x27

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C

    const val MINUS = 0x2D
    const val EQUAL = 0x2E
    const val BRACKET_LEFT = 0x2F
    const val BRACKET_RIGHT = 0x30
    const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33
    const val QUOTE = 0x34
    const val BACKQUOTE = 0x35
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38

    const val F1 = 0x3A; const val F2 = 0x3B; const val F3 = 0x3C; const val F4 = 0x3D
    const val F5 = 0x3E; const val F6 = 0x3F; const val F7 = 0x40; const val F8 = 0x41
    const val F9 = 0x42; const val F10 = 0x43; const val F11 = 0x44; const val F12 = 0x45

    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val ARROW_RIGHT = 0x4F
    const val ARROW_LEFT = 0x50
    const val ARROW_DOWN = 0x51
    const val ARROW_UP = 0x52
}
