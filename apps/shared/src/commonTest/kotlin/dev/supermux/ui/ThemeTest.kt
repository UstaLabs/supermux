package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeTest {
    @Test fun dark_palette_resolves_all_tokens_opaque() {
        val c = supermuxDark()
        for (v in listOf(c.background, c.foreground, c.primary, c.chat, c.terminal)) {
            assertEquals(0xFF, (v shr 24) and 0xFF)
        }
        val r = (c.primary shr 16) and 0xFF; val g = (c.primary shr 8) and 0xFF; val b = c.primary and 0xFF
        assertTrue(g > r && b > r)
    }
}
