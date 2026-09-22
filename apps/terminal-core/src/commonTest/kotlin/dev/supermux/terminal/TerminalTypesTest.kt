package dev.supermux.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pure-Kotlin checks of the shared model (no engine needed; green before the bindings land). */
class TerminalTypesTest {
    @Test fun sizeRejectsNonPositive() {
        TerminalSize(1, 1, 1, 1)
        assertFailsWith<IllegalArgumentException> { TerminalSize(0, 24, 8, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(80, 0, 8, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(80, 24, 0, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(80, 24, 8, -1) }
    }

    @Test fun limitsDefaultsAndValidation() {
        assertEquals(TerminalLimits(50_000, 32L * 1024 * 1024), TerminalLimits())
        TerminalLimits(0, 0)
        assertFailsWith<IllegalArgumentException> { TerminalLimits(-1, 0) }
        assertFailsWith<IllegalArgumentException> { TerminalLimits(0, -1) }
    }

    @Test fun sizeIsCappedAtMaxCells() {
        TerminalSize(1000, 100, 8, 16) // exactly MAX_CELLS
        TerminalSize(4096, 24, 8, 16)
        TerminalSize(1, TerminalSize.MAX_DIMENSION, 8, 16)
        assertEquals(100_000, TerminalSize.MAX_CELLS)
        assertFailsWith<IllegalArgumentException> { TerminalSize(1000, 101, 8, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(4096, 25, 8, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(4097, 1, 8, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(0, 1, 8, 16) }
    }

    @Test fun cellWidthIsZeroOneOrTwo() {
        val style = CellStyle(TerminalColor.DEFAULT, TerminalColor.DEFAULT, CellFlags.NONE, Underline.NONE)
        for (w in 0..2) TerminalCell("", w, style)
        assertFailsWith<IllegalArgumentException> { TerminalCell("x", 3, style) }
        assertFailsWith<IllegalArgumentException> { TerminalCell("x", -1, style) }
    }

    @Test fun colorsNeedFullPalette() {
        TestFixtures.fixtureColors()
        assertFailsWith<IllegalArgumentException> {
            TerminalColors(TerminalColor.rgb(0), TerminalColor.rgb(0), TerminalColor.rgb(0), List(16) { 0L })
        }
    }

    @Test fun colorHelpers() {
        val c = TerminalColor.rgba(0x12, 0x34, 0x56, 0x78)
        assertEquals(listOf(0x12, 0x34, 0x56, 0x78), listOf(TerminalColor.red(c), TerminalColor.green(c), TerminalColor.blue(c), TerminalColor.alpha(c)))
        assertFalse(TerminalColor.isDefault(c))
        assertTrue(TerminalColor.isValid(c))
        assertTrue(TerminalColor.isDefault(TerminalColor.DEFAULT))
        assertTrue(TerminalColor.isValid(TerminalColor.DEFAULT))
        assertFalse(TerminalColor.isValid(TerminalColor.DEFAULT or 0xFF))
        assertFalse(TerminalColor.isValid(1L shl 33))
        assertTrue(TerminalColor.isValid(TerminalColor.rgba(255, 255, 255, 255)))
        assertEquals(0xFFFFFFFFL, TerminalColor.rgba(255, 255, 255, 255))
        assertFailsWith<IllegalArgumentException> { TerminalColor.rgba(256, 0, 0) }
        assertFailsWith<IllegalArgumentException> { TerminalColor.rgb(0x1000000) }
    }

    @Test fun byteEffectsCompareByContent() {
        assertEquals(TerminalEffect.Response(byteArrayOf(1, 2)), TerminalEffect.Response(byteArrayOf(1, 2)))
        assertEquals(TerminalEffect.Response(byteArrayOf(1, 2)).hashCode(), TerminalEffect.Response(byteArrayOf(1, 2)).hashCode())
        assertNotEquals(TerminalEffect.Response(byteArrayOf(1, 2)), TerminalEffect.Response(byteArrayOf(2, 1)))
        assertEquals(TerminalEffect.Input(byteArrayOf(3)), TerminalEffect.Input(byteArrayOf(3)))
        assertEquals(TerminalEffect.Input(byteArrayOf(3)).hashCode(), TerminalEffect.Input(byteArrayOf(3)).hashCode())
        assertNotEquals<TerminalEffect>(TerminalEffect.Input(byteArrayOf(3)), TerminalEffect.Response(byteArrayOf(3)))
        assertEquals("Response([27, 91])", TerminalEffect.Response(byteArrayOf(27, 91)).toString())
    }

    @Test fun unavailableEngineIsTyped() {
        val e = TerminalEngineUnavailableException()
        assertEquals("native engine not linked yet", e.message)
        assertTrue(e is IllegalStateException)
    }
}
