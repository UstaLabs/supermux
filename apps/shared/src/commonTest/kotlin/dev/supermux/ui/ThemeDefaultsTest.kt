package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeDefaultsTest {
    @Test
    fun fresh_install_is_brand_first_dynamic_color_off() {
        // A fresh install (no stored preference) must show the supermux brand,
        // not a wallpaper-tinted Material You scheme.
        assertFalse(ThemeDefaults.dynamicColorEnabled(stored = null))
    }

    @Test
    fun user_opt_in_to_material_you_is_respected() {
        assertTrue(ThemeDefaults.dynamicColorEnabled(stored = true))
    }

    @Test
    fun user_opt_out_is_respected() {
        assertFalse(ThemeDefaults.dynamicColorEnabled(stored = false))
    }
}
