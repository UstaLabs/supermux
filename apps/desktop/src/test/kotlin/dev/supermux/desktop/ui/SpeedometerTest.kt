package dev.supermux.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedometerTest {
    @Test fun progress_oneBased_value_maps_low_to_high() {
        assertEquals(0f, speedometerProgress(levels = 5, value = 1))
        assertEquals(0.5f, speedometerProgress(levels = 5, value = 3))
        assertEquals(1f, speedometerProgress(levels = 5, value = 5))
    }

    @Test fun progress_clamps_out_of_range() {
        assertEquals(0f, speedometerProgress(levels = 4, value = 0))
        assertEquals(1f, speedometerProgress(levels = 4, value = 99))
        assertEquals(1f, speedometerProgress(levels = 1, value = 1))
        assertEquals(1f, speedometerProgress(levels = 0, value = 1)) // levels coerced to 1
    }
}
