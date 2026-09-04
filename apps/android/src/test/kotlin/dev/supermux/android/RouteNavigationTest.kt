package dev.supermux.android

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared `Route` model lives in `:ui`, which does NOT have `androidx.navigation` on its
 * classpath — so this is the one test on the Android side that proves navigation-compose still
 * accepts those sealed-interface members as type-safe destinations.
 *
 * `generateRoutePattern` is exactly what `composable<Route.X>` and `navigate(Route.X(...))` call
 * under the hood: it walks the route's generated serializer, maps every argument to a `NavType`,
 * and throws if the type is not `@Serializable` or carries an argument navigation cannot map. It
 * is `internal` in Kotlin metadata (public in the bytecode), hence the one reflective hop — that
 * is the whole reason this assertion cannot live in `:ui`'s own tests. No `NavHostController`, no
 * Robolectric, no `android.os.Bundle` is involved.
 */
class RouteNavigationTest {

    private val generate = Class.forName("androidx.navigation.serialization.RouteSerializerKt")
        .getMethod("generateRoutePattern", KSerializer::class.java, Map::class.java, String::class.java)

    private fun pattern(serializer: KSerializer<*>): String =
        generate.invoke(null, serializer, emptyMap<Any, Any>(), null) as String

    @Test
    fun object_routes_generate_their_serial_name_as_the_route_pattern() {
        assertEquals("home", pattern(serializer<Route.Home>()))
        assertEquals("add_host", pattern(serializer<Route.AddHost>()))
        assertEquals("usage", pattern(serializer<Route.Usage>()))
        assertEquals("devices", pattern(serializer<Route.Devices>()))
        assertEquals("archived", pattern(serializer<Route.Archived>()))
        assertEquals("proxies", pattern(serializer<Route.Proxies>()))
        assertEquals("displays", pattern(serializer<Route.Displays>()))
        assertEquals("appearance", pattern(serializer<Route.Appearance>()))
        assertEquals("app_update", pattern(serializer<Route.AppUpdate>()))
    }

    @Test
    fun a_data_class_route_with_a_string_argument_becomes_a_query_pattern() {
        // Defaulted arguments are optional → query parameters, which is what makes
        // `navigate(Route.NewSession())` and `navigate(Route.NewSession("d1"))` both valid.
        assertEquals("new_session?draftId={draftId}", pattern(serializer<Route.NewSession>()))
    }

    @Test
    fun the_settings_route_maps_its_enum_section_to_a_nav_type() {
        // The enum argument is the one thing that could have failed the move to `:ui`:
        // navigation resolves it through its built-in enum NavType rather than throwing
        // "Cannot cast SettingsSection to a NavType".
        assertEquals("settings?section={section}", pattern(serializer<Route.Settings>()))
        assertEquals(SettingsSection.Agents, Route.Settings().section)
    }
}
