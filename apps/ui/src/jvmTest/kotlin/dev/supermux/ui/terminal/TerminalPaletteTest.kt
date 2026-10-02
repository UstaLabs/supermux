package dev.supermux.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.prefs.FakeSettingsStore
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TerminalPaletteTest {

    @Test fun the_setting_against_the_app_mode() {
        assertTrue(TerminalAppearance.FOLLOW_APP.isDark(appDark = true))
        assertFalse(TerminalAppearance.FOLLOW_APP.isDark(appDark = false))
        assertTrue(TerminalAppearance.DARK.isDark(appDark = false))
        assertFalse(TerminalAppearance.LIGHT.isDark(appDark = true))
    }

    @Test fun the_light_palette_reads_as_dark_ink_on_paper() {
        val p = LightTerminalPalette
        assertTrue(p.background.luminance() > 0.9f, "background ${p.background}")
        assertTrue(p.foreground.luminance() < 0.1f, "foreground ${p.foreground}")
        // Every ANSI colour has to be legible as TEXT on the light background (WCAG 3:1 at least).
        p.ansi.forEachIndexed { i, c ->
            val contrast = (p.background.luminance() + 0.05f) / (c.luminance() + 0.05f)
            assertTrue(contrast >= 3f, "ansi $i ${c} contrast $contrast")
        }
        assertEquals(16, p.ansi.size)
    }

    private fun resolve(app: AppearanceMode, stored: TerminalAppearance?): TerminalPalette {
        var seen: TerminalPalette? = null
        runComposeUiTest {
            val store = FakeSettingsStore()
            stored?.let { store.map.value = mapOf(dev.supermux.state.SettingsKeys.TERMINAL_APPEARANCE to it.name) }
            setContent {
                CompositionLocalProvider(LocalUiPrefsOrNull provides UiPrefs(store)) {
                    SupermuxTheme(appearance = app) { Probe { seen = it } }
                }
            }
            waitForIdle()
        }
        return seen!!
    }

    @Composable private fun Probe(onPalette: (TerminalPalette) -> Unit) = onPalette(terminalPalette())

    @Test fun nothing_stored_follows_the_app() {
        assertTrue(resolve(AppearanceMode.DARK, null).dark)
        assertEquals(LightTerminalPalette, resolve(AppearanceMode.LIGHT, null))
    }

    @Test fun a_pinned_terminal_ignores_the_app() {
        assertTrue(resolve(AppearanceMode.LIGHT, TerminalAppearance.DARK).dark)
        assertEquals(LightTerminalPalette, resolve(AppearanceMode.DARK, TerminalAppearance.LIGHT))
    }
}
