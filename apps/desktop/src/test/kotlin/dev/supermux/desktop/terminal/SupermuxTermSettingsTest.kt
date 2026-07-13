package dev.supermux.desktop.terminal

import java.awt.Font
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec for [SupermuxTermSettings] — the themed JediTerm settings provider. All headless:
 * `Font.createFont` and color/typeahead getters need no display.
 */
class SupermuxTermSettingsTest {

    // ── typeahead ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun typeahead_is_disabled() {
        // getTypeAheadSettings() is the SettingsProvider member feeding TerminalTypeAheadManager;
        // JediTerm 3.73's DEFAULT is enabled=true — ours must be off (shared PredictionEngine is
        // the only prediction system).
        assertFalse(SupermuxTermSettings().typeAheadSettings.isEnabled)
    }

    // ── bell ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun audible_bell_is_off() {
        assertFalse(SupermuxTermSettings().audibleBell())
    }

    // ── colors ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun default_style_uses_constructor_colors() {
        val s = SupermuxTermSettings(background = 0xFF102030.toInt(), foreground = 0xFFA0B0C0.toInt())
        val bg = s.defaultStyle.background!!.toColor()
        val fg = s.defaultStyle.foreground!!.toColor()
        assertEquals(Triple(0x10, 0x20, 0x30), Triple(bg.red, bg.green, bg.blue))
        assertEquals(Triple(0xA0, 0xB0, 0xC0), Triple(fg.red, fg.green, fg.blue))
    }

    @Test
    fun default_colors_are_the_dark_terminal_tones() {
        // Defaults mirror shared Theme.kt supermuxDark: terminal 0xFF050605 / fg 0xFFD8DED3.
        val s = SupermuxTermSettings()
        val bg = s.defaultBackground.toColor()
        val fg = s.defaultForeground.toColor()
        assertEquals(Triple(0x05, 0x06, 0x05), Triple(bg.red, bg.green, bg.blue))
        assertEquals(Triple(0xD8, 0xDE, 0xD3), Triple(fg.red, fg.green, fg.blue))
    }

    @Test
    fun terminal_color_converts_argb_channels() {
        val c = SupermuxTermSettings.terminalColor(0xFF123456.toInt()).toColor()
        assertEquals(0x12, c.red)
        assertEquals(0x34, c.green)
        assertEquals(0x56, c.blue)
    }

    // ── font ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun terminal_font_is_bundled_geist_mono_at_size_13() {
        val f = SupermuxTermSettings().terminalFont
        assertTrue(f.family.contains("Geist Mono"), "expected Geist Mono, got '${f.family}'")
        assertEquals(13f, SupermuxTermSettings().terminalFontSize)
        assertEquals(13f, f.size2D)
    }

    @Test
    fun mono_font_falls_back_when_resource_missing() {
        val f = SupermuxTermSettings.loadMonoFont { null }
        assertEquals(Font.MONOSPACED, f.family)
    }

    @Test
    fun mono_font_falls_back_when_stream_is_not_a_font() {
        val f = SupermuxTermSettings.loadMonoFont { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
        assertEquals(Font.MONOSPACED, f.family)
    }

    @Test
    fun mono_font_falls_back_when_stream_open_throws() {
        val f = SupermuxTermSettings.loadMonoFont {
            object : InputStream() {
                override fun read(): Int = throw IOException("boom")
            }
        }
        assertEquals(Font.MONOSPACED, f.family)
    }
}
