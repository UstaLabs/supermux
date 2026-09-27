package dev.supermux.ui.terminal

import dev.supermux.net.Mods
import dev.supermux.net.printableSequence
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [TerminalKeySink.applyArmedModifiers] — the half of the sink that makes a bar Ctrl mean anything.
 *
 * The bar carries Esc/Tab/Ctrl/Alt/arrows and no letters, so "Ctrl-C" is ALWAYS the bar arming Ctrl
 * and the keyboard typing `c`. That keystroke arrives from the grid, not from [TerminalKeySink.press],
 * so every surface has to route it back through the sink. The rule was private to Android's termlib
 * view until H5, when iOS needed the identical behaviour; these pin it in the shared place so the
 * two hosts cannot drift.
 */
class TerminalKeySinkModifiersTest {

    private fun sink(sent: MutableList<ByteArray> = mutableListOf()) = TerminalKeySink { sent.add(it) }

    @Test fun nothing_armed_passes_the_keystroke_through_untouched() {
        assertNull(sink().applyArmedModifiers(byteArrayOf('c'.code.toByte())))
    }

    @Test fun an_armed_ctrl_re_encodes_a_printable_and_consumes_the_once() {
        val s = sink()
        s.press(TerminalKey.Mod(TerminalModKey.CTRL))
        assertEquals(TerminalModState.ONCE, s.ctrl)

        val out = s.applyArmedModifiers(byteArrayOf('c'.code.toByte()))
        // 0x03 — the same bytes `:shared` builds for a bar-driven press, so the two routes agree.
        assertContentEquals(printableSequence('c', Mods(ctrl = true, alt = false)).encodeToByteArray(), out)
        // `once` is spent by the keystroke it modified.
        assertEquals(TerminalModState.OFF, s.ctrl)
    }

    @Test fun a_locked_ctrl_keeps_modifying_every_following_keystroke() {
        val s = sink()
        s.press(TerminalKey.Mod(TerminalModKey.CTRL))
        s.press(TerminalKey.Mod(TerminalModKey.CTRL))
        assertEquals(TerminalModState.LOCKED, s.ctrl)

        val expected = printableSequence('c', Mods(ctrl = true, alt = false)).encodeToByteArray()
        assertContentEquals(expected, s.applyArmedModifiers(byteArrayOf('c'.code.toByte())))
        assertContentEquals(expected, s.applyArmedModifiers(byteArrayOf('c'.code.toByte())))
        assertEquals(TerminalModState.LOCKED, s.ctrl)
    }

    /**
     * The keystrokes an armed modifier must NOT touch. A modifier has no defined meaning for any of
     * them, and re-encoding would break Enter and every non-ASCII keyboard. Note the sink stays
     * armed: it did not modify anything, so it has nothing to consume.
     */
    @Test fun control_codes_enter_and_multi_byte_input_pass_through_while_armed() {
        val s = sink()
        s.press(TerminalKey.Mod(TerminalModKey.CTRL))

        assertNull(s.applyArmedModifiers(byteArrayOf(0x0d)), "Enter")
        assertNull(s.applyArmedModifiers(byteArrayOf(0x1b)), "Esc")
        assertNull(s.applyArmedModifiers("ç".encodeToByteArray()), "multi-byte UTF-8")
        assertNull(s.applyArmedModifiers("ab".encodeToByteArray()), "a paste / IME commit")
        assertNull(s.applyArmedModifiers(byteArrayOf()), "empty")
        assertEquals(TerminalModState.ONCE, s.ctrl, "an untouched keystroke must not spend the arm")
    }

    @Test fun alt_arms_the_same_way_and_yields_the_meta_prefix() {
        val s = sink()
        s.press(TerminalKey.Mod(TerminalModKey.ALT))
        assertContentEquals(
            printableSequence('b', Mods(ctrl = false, alt = true)).encodeToByteArray(),
            s.applyArmedModifiers(byteArrayOf('b'.code.toByte())),
        )
        assertEquals(TerminalModState.OFF, s.alt)
    }

    /**
     * The accessory bar's "hide keyboard" button reaches whatever [rememberSemanticTerminalKeySink]
     * (or a raw byte sink) was built with — not `semantic`, and not `send`: dismissing the IME is
     * neither a [TerminalKey] the emulator encodes nor bytes for the pty.
     */
    @Test fun hideKeyboard_invokes_whatever_the_sink_was_wired_with() {
        var hidden = 0
        val s = TerminalKeySink(onHideKeyboard = { hidden++ }) { }
        s.hideKeyboard()
        s.hideKeyboard()
        assertEquals(2, hidden)
    }

    @Test fun hideKeyboard_is_a_silent_no_op_when_nothing_was_wired() {
        // A sink that never wires it (a raw byte sink, most test fakes) must not throw.
        sink().hideKeyboard()
    }

    @Test fun singlePrintableChar_covers_exactly_the_printable_ascii_range() {
        assertEquals(' ', singlePrintableChar(byteArrayOf(0x20)))
        assertEquals('~', singlePrintableChar(byteArrayOf(0x7e)))
        assertNull(singlePrintableChar(byteArrayOf(0x1f)))
        assertNull(singlePrintableChar(byteArrayOf(0x7f)))
        // 0x80+ is a UTF-8 continuation/lead byte, never a single printable char.
        assertNull(singlePrintableChar(byteArrayOf(0xc3.toByte())))
    }
}
