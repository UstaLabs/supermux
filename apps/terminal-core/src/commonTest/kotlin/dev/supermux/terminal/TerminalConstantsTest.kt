package dev.supermux.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Freezes the package-owned wire encoding. These numbers cross the st_* C ABI, the viewport codec
 * and the wasm bridge (mapping table: native/README.md). A failure here means a binding-breaking
 * change: append new constants instead of renumbering.
 */
class TerminalConstantsTest {
    @Test fun colorEncoding() {
        assertEquals(0x1_0000_0000L, TerminalColor.DEFAULT)
        assertEquals(256, TerminalColor.PALETTE_SIZE)
        assertEquals(0xCC6666FFL, TerminalColor.rgba(0xCC, 0x66, 0x66))
        assertEquals(0x11223344L, TerminalColor.rgba(0x11, 0x22, 0x33, 0x44))
        assertEquals(0xCC6666FFL, TerminalColor.rgb(0xCC6666))
    }

    @Test fun cellFlags() {
        assertEquals(
            listOf(0, 1, 2, 4, 8, 16, 32, 64, 128),
            listOf(
                CellFlags.NONE, CellFlags.BOLD, CellFlags.ITALIC, CellFlags.FAINT, CellFlags.BLINK,
                CellFlags.INVERSE, CellFlags.INVISIBLE, CellFlags.STRIKETHROUGH, CellFlags.OVERLINE,
            ),
        )
    }

    @Test fun underline() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            listOf(Underline.NONE, Underline.SINGLE, Underline.DOUBLE, Underline.CURLY, Underline.DOTTED, Underline.DASHED),
        )
    }

    @Test fun cursorShape() {
        assertEquals(
            listOf(0, 1, 2, 3),
            listOf(CursorShape.BLOCK, CursorShape.BAR, CursorShape.UNDERLINE, CursorShape.BLOCK_HOLLOW),
        )
    }

    @Test fun modifiers() {
        assertEquals(
            listOf(0, 1, 2, 4, 8, 16, 32),
            listOf(
                Modifiers.NONE, Modifiers.SHIFT, Modifiers.CTRL, Modifiers.ALT, Modifiers.SUPER,
                Modifiers.CAPS_LOCK, Modifiers.NUM_LOCK,
            ),
        )
    }

    @Test fun actionsAndButtons() {
        assertEquals(listOf(0, 1, 2), listOf(KeyAction.PRESS, KeyAction.RELEASE, KeyAction.REPEAT))
        assertEquals(listOf(0, 1, 2), listOf(MouseAction.PRESS, MouseAction.RELEASE, MouseAction.MOTION))
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7),
            listOf(
                MouseButton.NONE, MouseButton.LEFT, MouseButton.RIGHT, MouseButton.MIDDLE,
                MouseButton.WHEEL_UP, MouseButton.WHEEL_DOWN, MouseButton.WHEEL_LEFT, MouseButton.WHEEL_RIGHT,
            ),
        )
    }

    @Test fun keyCodesAreHidUsageIds() {
        val letters = with(TerminalKeys) {
            listOf(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z)
        }
        assertEquals((0x04..0x1D).toList(), letters)
        val digits = with(TerminalKeys) {
            listOf(DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9, DIGIT_0)
        }
        assertEquals((0x1E..0x27).toList(), digits)
        val functionKeys = with(TerminalKeys) { listOf(F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12) }
        assertEquals((0x3A..0x45).toList(), functionKeys)
        val named = with(TerminalKeys) {
            mapOf(
                "UNIDENTIFIED" to UNIDENTIFIED, "ENTER" to ENTER, "ESCAPE" to ESCAPE, "BACKSPACE" to BACKSPACE,
                "TAB" to TAB, "SPACE" to SPACE, "MINUS" to MINUS, "EQUAL" to EQUAL,
                "BRACKET_LEFT" to BRACKET_LEFT, "BRACKET_RIGHT" to BRACKET_RIGHT, "BACKSLASH" to BACKSLASH,
                "SEMICOLON" to SEMICOLON, "QUOTE" to QUOTE, "BACKQUOTE" to BACKQUOTE, "COMMA" to COMMA,
                "PERIOD" to PERIOD, "SLASH" to SLASH, "INSERT" to INSERT, "HOME" to HOME, "PAGE_UP" to PAGE_UP,
                "DELETE" to DELETE, "END" to END, "PAGE_DOWN" to PAGE_DOWN, "ARROW_RIGHT" to ARROW_RIGHT,
                "ARROW_LEFT" to ARROW_LEFT, "ARROW_DOWN" to ARROW_DOWN, "ARROW_UP" to ARROW_UP,
            )
        }
        assertEquals(
            mapOf(
                "UNIDENTIFIED" to 0x00, "ENTER" to 0x28, "ESCAPE" to 0x29, "BACKSPACE" to 0x2A,
                "TAB" to 0x2B, "SPACE" to 0x2C, "MINUS" to 0x2D, "EQUAL" to 0x2E,
                "BRACKET_LEFT" to 0x2F, "BRACKET_RIGHT" to 0x30, "BACKSLASH" to 0x31,
                "SEMICOLON" to 0x33, "QUOTE" to 0x34, "BACKQUOTE" to 0x35, "COMMA" to 0x36,
                "PERIOD" to 0x37, "SLASH" to 0x38, "INSERT" to 0x49, "HOME" to 0x4A, "PAGE_UP" to 0x4B,
                "DELETE" to 0x4C, "END" to 0x4D, "PAGE_DOWN" to 0x4E, "ARROW_RIGHT" to 0x4F,
                "ARROW_LEFT" to 0x50, "ARROW_DOWN" to 0x51, "ARROW_UP" to 0x52,
            ),
            named,
        )
    }
}
