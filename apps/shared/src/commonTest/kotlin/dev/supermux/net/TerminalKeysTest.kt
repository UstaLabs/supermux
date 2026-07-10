package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors the web reference suite (src/web-app/src/lib/terminal-keys.test.ts) so
 * the native key-bar sequences stay in lockstep with the PWA's.
 *
 * Expected values are built from char codes (esc/ctl helpers) rather than raw
 * control bytes or \u escapes, so the source stays plain ASCII and readable.
 */
class TerminalKeysTest {
    private val none = Mods(ctrl = false, alt = false)
    private val ctrl = Mods(ctrl = true, alt = false)
    private val alt = Mods(ctrl = false, alt = true)
    private val both = Mods(ctrl = true, alt = true)

    /** ESC (0x1b) followed by the given tail — the CSI/SS3 introducer. */
    private fun esc(tail: String): String = 27.toChar() + tail

    /** A single control byte as a one-char string, e.g. ctl(3) == Ctrl-C. */
    private fun ctl(code: Int): String = code.toChar().toString()

    // --- Unmodified special keys, normal (non-application) cursor mode ---
    @Test fun arrows_send_CSI_sequences_in_normal_cursor_mode() {
        assertEquals(esc("[A"), specialKeySequence(SpecialKey.ArrowUp, none, false))
        assertEquals(esc("[B"), specialKeySequence(SpecialKey.ArrowDown, none, false))
        assertEquals(esc("[C"), specialKeySequence(SpecialKey.ArrowRight, none, false))
        assertEquals(esc("[D"), specialKeySequence(SpecialKey.ArrowLeft, none, false))
    }

    // --- Application cursor keys mode (DECCKM) — vim/tmux full-screen apps ---
    @Test fun arrows_send_SS3_sequences_in_application_cursor_mode() {
        assertEquals(esc("OA"), specialKeySequence(SpecialKey.ArrowUp, none, true))
        assertEquals(esc("OB"), specialKeySequence(SpecialKey.ArrowDown, none, true))
        assertEquals(esc("OC"), specialKeySequence(SpecialKey.ArrowRight, none, true))
        assertEquals(esc("OD"), specialKeySequence(SpecialKey.ArrowLeft, none, true))
    }

    @Test fun home_and_end_follow_cursor_keys_mode() {
        assertEquals(esc("[H"), specialKeySequence(SpecialKey.Home, none, false))
        assertEquals(esc("[F"), specialKeySequence(SpecialKey.End, none, false))
        assertEquals(esc("OH"), specialKeySequence(SpecialKey.Home, none, true))
        assertEquals(esc("OF"), specialKeySequence(SpecialKey.End, none, true))
    }

    @Test fun esc_tab_pageup_pagedown_are_fixed_regardless_of_mode() {
        assertEquals(ctl(27), specialKeySequence(SpecialKey.Escape, none, false))
        assertEquals(ctl(9), specialKeySequence(SpecialKey.Tab, none, false))
        assertEquals(esc("[5~"), specialKeySequence(SpecialKey.PageUp, none, false))
        assertEquals(esc("[6~"), specialKeySequence(SpecialKey.PageDown, none, false))
        assertEquals(esc("[5~"), specialKeySequence(SpecialKey.PageUp, none, true))
    }

    // --- Modified special keys use CSI parameterised form (modifier = 1 + alt*2 + ctrl*4) ---
    @Test fun modified_arrows_use_CSI_param_form_overriding_application_mode() {
        assertEquals(esc("[1;5A"), specialKeySequence(SpecialKey.ArrowUp, ctrl, false))
        assertEquals(esc("[1;3D"), specialKeySequence(SpecialKey.ArrowLeft, alt, false))
        assertEquals(esc("[1;7C"), specialKeySequence(SpecialKey.ArrowRight, both, false))
        assertEquals(esc("[1;5A"), specialKeySequence(SpecialKey.ArrowUp, ctrl, true))
    }

    @Test fun modified_home_end_use_CSI_param_form() {
        assertEquals(esc("[1;5H"), specialKeySequence(SpecialKey.Home, ctrl, false))
        assertEquals(esc("[1;3F"), specialKeySequence(SpecialKey.End, alt, false))
    }

    @Test fun modified_pageup_pagedown_use_CSI_num_param_tilde_form() {
        assertEquals(esc("[5;5~"), specialKeySequence(SpecialKey.PageUp, ctrl, false))
        assertEquals(esc("[6;3~"), specialKeySequence(SpecialKey.PageDown, alt, false))
    }

    // --- Printable characters ---
    @Test fun plain_printable_char_is_unchanged() {
        assertEquals("|", printableSequence('|', none))
        assertEquals("a", printableSequence('a', none))
    }

    @Test fun ctrl_plus_letter_produces_the_control_code() {
        assertEquals(ctl(3), printableSequence('c', ctrl)) // Ctrl-C
        assertEquals(ctl(3), printableSequence('C', ctrl)) // case-insensitive
        assertEquals(ctl(4), printableSequence('d', ctrl)) // Ctrl-D (EOF)
        assertEquals(ctl(1), printableSequence('a', ctrl)) // Ctrl-A
        assertEquals(ctl(26), printableSequence('z', ctrl)) // Ctrl-Z (suspend)
    }

    @Test fun ctrl_plus_punctuation_produces_classic_control_codes() {
        assertEquals(ctl(27), printableSequence('[', ctrl)) // Ctrl-[ == Esc
        assertEquals(ctl(28), printableSequence('\\', ctrl))
        assertEquals(ctl(29), printableSequence(']', ctrl))
        assertEquals(ctl(31), printableSequence('_', ctrl))
        assertEquals(ctl(127), printableSequence('?', ctrl)) // Ctrl-? == DEL
        assertEquals(ctl(0), printableSequence(' ', ctrl)) // Ctrl-Space == NUL
    }

    @Test fun alt_plus_printable_prefixes_esc() {
        assertEquals(esc("b"), printableSequence('b', alt)) // Alt-b == word back
        assertEquals(esc("|"), printableSequence('|', alt))
    }

    @Test fun ctrl_alt_plus_letter_is_esc_then_control_code() {
        assertEquals(esc(ctl(3)), printableSequence('c', both))
    }
}
