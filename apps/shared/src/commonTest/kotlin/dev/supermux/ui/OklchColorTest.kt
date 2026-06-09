package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class OklchColorTest {
    @Test fun white_and_black() {
        assertEquals(0xFFFFFFFF.toInt(), oklchToArgb(1.0, 0.0, 0.0))
        assertEquals(0xFF000000.toInt(), oklchToArgb(0.0, 0.0, 0.0))
    }
    @Test fun mid_gray_is_neutral() {
        val argb = oklchToArgb(0.5, 0.0, 0.0)
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        assertEquals(r, g); assertEquals(g, b)
    }
    @Test fun teal_primary_is_in_range_and_greenish_blue() {
        val argb = oklchToArgb(0.72, 0.105, 180.0)
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        assertEquals(0xFF, (argb shr 24) and 0xFF)
        kotlin.test.assertTrue(g > r && b > r, "expected teal, got r=$r g=$g b=$b")
    }
}
