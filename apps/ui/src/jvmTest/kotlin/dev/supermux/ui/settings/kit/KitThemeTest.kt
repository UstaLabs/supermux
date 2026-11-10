package dev.supermux.ui.settings.kit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class KitThemeTest {
    @Test fun tones_come_from_the_scheme_roles() = runComposeUiTest {
        val scheme = darkColorScheme()
        var colors: KitColors? = null
        setPlatformContent { MaterialTheme(colorScheme = scheme) { colors = kitColors() } }
        waitForIdle()
        val c = colors!!
        assertEquals(scheme.onSurface, c.text)
        assertEquals(scheme.onSurfaceVariant, c.dim)
        assertEquals(scheme.outlineVariant, c.line)
        assertEquals(scheme.primary, c.accent)
        assertEquals(scheme.background, c.pane)
        assertEquals(scheme.surfaceContainer, c.chrome)
        assertEquals(0.06f, c.wash.alpha, 0.01f)
    }

    @Test fun light_scheme_uses_the_lighter_wash() = runComposeUiTest {
        var colors: KitColors? = null
        setPlatformContent { MaterialTheme(colorScheme = lightColorScheme()) { colors = kitColors() } }
        waitForIdle()
        assertEquals(0.045f, colors!!.wash.alpha, 0.01f)
    }

    @Test fun phone_metrics_follow_the_compact_width() = runComposeUiTest {
        var m: KitMetrics? = null
        setPlatformContent(widthClass = WindowWidthClass.Compact) { m = kitMetrics() }
        waitForIdle()
        assertTrue(m!!.phone)
    }

    @Test fun the_device_is_a_computer_where_supermux_can_host() = runComposeUiTest {
        var noun = ""
        setPlatformContent(platform = FakePlatform(caps = NO_CAPS.copy(localBroker = true))) { noun = kitDeviceNoun() }
        waitForIdle()
        assertEquals("computer", noun)
    }

    @Test fun the_device_is_a_phone_on_a_compact_touch_screen() = runComposeUiTest {
        var noun = ""
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) { noun = kitDeviceNoun() }
        waitForIdle()
        assertEquals("phone", noun)
    }

    @Test fun every_glyph_builds_on_the_24_grid() {
        for (g in KitGlyphs.all) {
            assertEquals(24f, g.viewportWidth)
            assertTrue(g.root.size > 0, "glyph ${g.name} has no paths")
        }
    }
}
