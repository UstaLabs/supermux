package dev.supermux.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/** The sidebar's theme shortcut writes the opposite of what is PAINTED, never of what is stored (#97 spec §10.6). */
class AppearanceTest {
    @Test fun system_on_a_dark_system_flips_to_light() =
        assertEquals(AppearanceMode.LIGHT, nextAppearance(AppearanceMode.SYSTEM, AppearanceMode.SYSTEM, systemDark = true))

    @Test fun system_on_a_light_system_flips_to_dark() =
        assertEquals(AppearanceMode.DARK, nextAppearance(AppearanceMode.SYSTEM, AppearanceMode.SYSTEM, systemDark = false))

    @Test fun dark_flips_to_light() = assertEquals(AppearanceMode.LIGHT, nextAppearance(AppearanceMode.DARK, AppearanceMode.SYSTEM, systemDark = false))

    @Test fun light_flips_to_dark() = assertEquals(AppearanceMode.DARK, nextAppearance(AppearanceMode.LIGHT, AppearanceMode.SYSTEM, systemDark = true))

    @Test fun unset_on_desktop_paints_dark_so_it_flips_to_light() =
        assertEquals(AppearanceMode.LIGHT, nextAppearance(null, AppearanceMode.DARK, systemDark = false))

    @Test fun painted_resolves_system_with_the_platforms_dark_check() {
        assertEquals(AppearanceMode.DARK, paintedAppearance(AppearanceMode.SYSTEM, AppearanceMode.DARK, systemDark = true))
        assertEquals(AppearanceMode.LIGHT, paintedAppearance(null, AppearanceMode.SYSTEM, systemDark = false))
        assertEquals(AppearanceMode.LIGHT, paintedAppearance(AppearanceMode.LIGHT, AppearanceMode.DARK, systemDark = true))
    }
}
