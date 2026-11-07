package dev.supermux.webview.engine.linux

import java.awt.event.KeyEvent

/** A key as WPE takes it: an X keysym ([keyval]) and an XKB keycode ([keycode], evdev + 8; 0 when unknown). */
internal data class WpeKey(val keyval: Int, val keycode: Int)

/**
 * AWT keys in WPE's terms (`WPEEvent.h`, `WPEKeysyms.h`). A printable character gives the keyval, so the layout and
 * Shift count; the virtual key gives the keycode (its position on a US keyboard); named keys come from a table.
 */
internal object KeyMapping {
    // WPEModifiers.
    const val CONTROL: Int = 1
    const val SHIFT: Int = 1 shl 1
    const val ALT: Int = 1 shl 2
    const val META: Int = 1 shl 3
    const val CAPS_LOCK: Int = 1 shl 4
    const val BUTTON1: Int = 1 shl 8
    const val BUTTON2: Int = 1 shl 9
    const val BUTTON3: Int = 1 shl 10

    // WPE_BUTTON_PRIMARY, _MIDDLE, _SECONDARY.
    const val BUTTON_PRIMARY: Int = 1
    const val BUTTON_MIDDLE: Int = 2
    const val BUTTON_SECONDARY: Int = 3

    /** Named keys: AWT virtual key → X keysym and XKB keycode. */
    private val named: Map<Int, WpeKey> = mapOf(
        KeyEvent.VK_ENTER to WpeKey(0xff0d, 36),
        KeyEvent.VK_BACK_SPACE to WpeKey(0xff08, 22),
        KeyEvent.VK_TAB to WpeKey(0xff09, 23),
        KeyEvent.VK_ESCAPE to WpeKey(0xff1b, 9),
        KeyEvent.VK_DELETE to WpeKey(0xffff, 119),
        KeyEvent.VK_INSERT to WpeKey(0xff63, 118),
        KeyEvent.VK_HOME to WpeKey(0xff50, 110),
        KeyEvent.VK_END to WpeKey(0xff57, 115),
        KeyEvent.VK_PAGE_UP to WpeKey(0xff55, 112),
        KeyEvent.VK_PAGE_DOWN to WpeKey(0xff56, 117),
        KeyEvent.VK_LEFT to WpeKey(0xff51, 113),
        KeyEvent.VK_UP to WpeKey(0xff52, 111),
        KeyEvent.VK_RIGHT to WpeKey(0xff53, 114),
        KeyEvent.VK_DOWN to WpeKey(0xff54, 116),
        KeyEvent.VK_SHIFT to WpeKey(0xffe1, 50),
        KeyEvent.VK_CONTROL to WpeKey(0xffe3, 37),
        KeyEvent.VK_ALT to WpeKey(0xffe9, 64),
        KeyEvent.VK_META to WpeKey(0xffeb, 133),
        KeyEvent.VK_WINDOWS to WpeKey(0xffeb, 133),
        KeyEvent.VK_CAPS_LOCK to WpeKey(0xffe5, 66),
        KeyEvent.VK_CONTEXT_MENU to WpeKey(0xff67, 135),
        KeyEvent.VK_SPACE to WpeKey(0x20, 65),
    ) + (0 until 12).associate { i -> (KeyEvent.VK_F1 + i) to WpeKey(0xffbe + i, if (i < 10) 67 + i else 95 + (i - 10)) }

    /** Where printable keys sit on a US keyboard: AWT virtual key → XKB keycode. VK_A..VK_Z and VK_0..VK_9 equal the characters' codes. */
    private val positions: Map<Int, Int> = buildMap {
        "QWERTYUIOP".forEachIndexed { i, c -> put(c.code, 24 + i) }
        "ASDFGHJKL".forEachIndexed { i, c -> put(c.code, 38 + i) }
        "ZXCVBNM".forEachIndexed { i, c -> put(c.code, 52 + i) }
        "1234567890".forEachIndexed { i, c -> put(c.code, 10 + i) }
        put(KeyEvent.VK_MINUS, 20)
        put(KeyEvent.VK_EQUALS, 21)
        put(KeyEvent.VK_OPEN_BRACKET, 34)
        put(KeyEvent.VK_CLOSE_BRACKET, 35)
        put(KeyEvent.VK_SEMICOLON, 47)
        put(KeyEvent.VK_QUOTE, 48)
        put(KeyEvent.VK_BACK_QUOTE, 49)
        put(KeyEvent.VK_BACK_SLASH, 51)
        put(KeyEvent.VK_COMMA, 59)
        put(KeyEvent.VK_PERIOD, 60)
        put(KeyEvent.VK_SLASH, 61)
    }

    /** The keysym of a printable key with no Shift: its letter in lowercase, digit or US punctuation. */
    private val keysyms: Map<Int, Int> = buildMap {
        for (c in 'A'..'Z') put(c.code, c.lowercaseChar().code)
        for (c in '0'..'9') put(c.code, c.code)
        put(KeyEvent.VK_MINUS, '-'.code)
        put(KeyEvent.VK_EQUALS, '='.code)
        put(KeyEvent.VK_OPEN_BRACKET, '['.code)
        put(KeyEvent.VK_CLOSE_BRACKET, ']'.code)
        put(KeyEvent.VK_SEMICOLON, ';'.code)
        put(KeyEvent.VK_QUOTE, '\''.code)
        put(KeyEvent.VK_BACK_QUOTE, '`'.code)
        put(KeyEvent.VK_BACK_SLASH, '\\'.code)
        put(KeyEvent.VK_COMMA, ','.code)
        put(KeyEvent.VK_PERIOD, '.'.code)
        put(KeyEvent.VK_SLASH, '/'.code)
    }

    /** What AWT's key character means: null for none (`CHAR_UNDEFINED`, NUL), else the character. */
    fun codePointOf(keyChar: Int): Int? = keyChar.takeIf { it != 0 && it != KeyEvent.CHAR_UNDEFINED.code }

    /** The X keysym of a character: Latin-1 is itself, anything else a Unicode keysym (0x01000000 + code point). */
    fun keysymOf(codePoint: Int): Int =
        if (codePoint in 0x20..0x7e || codePoint in 0xa0..0xff) codePoint else 0x01000000 or codePoint

    /**
     * The WPE key for an AWT key: a printable [codePoint] gives the keyval and the key's position the keycode; a named
     * key gives both. A letter, digit or punctuation key that types no printable character (with Control held, AWT on
     * X11 reports Ctrl+C as 0x03) is its own keysym, a capital with [shift], as X11 gives it: WebKit's shortcuts (copy,
     * paste, select all, undo) and the page's own want the letter. Null for a key WPE has no name for and that types
     * nothing (it isn't forwarded).
     */
    fun keyFor(virtualKey: Int, codePoint: Int?, shift: Boolean = false): WpeKey? {
        if (codePoint != null && codePoint >= 0x20 && codePoint != 0x7f && codePoint !in 0xd800..0xdfff) {
            return WpeKey(keysymOf(codePoint), positions[virtualKey] ?: named[virtualKey]?.keycode ?: 0)
        }
        named[virtualKey]?.let { return it }
        val keysym = keysyms[virtualKey] ?: return null
        val capital = shift && keysym in 'a'.code..'z'.code
        return WpeKey(if (capital) keysym - 0x20 else keysym, positions[virtualKey] ?: 0)
    }

    fun modifiers(shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean, capsLock: Boolean = false): Int =
        (if (ctrl) CONTROL else 0) or (if (shift) SHIFT else 0) or (if (alt) ALT else 0) or
            (if (meta) META else 0) or (if (capsLock) CAPS_LOCK else 0)

    fun buttons(primary: Boolean, middle: Boolean, secondary: Boolean): Int =
        (if (primary) BUTTON1 else 0) or (if (middle) BUTTON2 else 0) or (if (secondary) BUTTON3 else 0)
}
