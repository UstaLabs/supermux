package dev.supermux.ui.nav

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The route model has to survive kotlinx-serialization because Android's type-safe
 * navigation-compose builds its route strings and argument types from the generated serializers.
 */
class RouteTest {

    private val json = Json

    private fun roundTrip(route: Route): Route =
        json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), route))

    @Test
    fun settings_round_trips_with_its_section() {
        val route = Route.Settings(SettingsSection.Voice)
        assertEquals(route, roundTrip(route))
        assertEquals("""{"type":"settings","section":"Voice"}""", json.encodeToString(Route.serializer(), route))
    }

    @Test
    fun settings_defaults_to_agents() {
        assertEquals(SettingsSection.Agents, Route.Settings().section)
        assertEquals(Route.Settings(SettingsSection.Agents), roundTrip(Route.Settings()))
    }

    @Test
    fun new_session_carries_its_draft_id() {
        val route = Route.NewSession("d1")
        assertEquals(route, roundTrip(route))
        assertEquals("d1", (roundTrip(route) as Route.NewSession).draftId)
        assertEquals("", Route.NewSession().draftId)
    }

    @Test
    fun every_object_route_round_trips_as_itself() {
        val objects = listOf(
            Route.Home, Route.AddHost, Route.Usage, Route.Devices,
            Route.Archived, Route.Proxies, Route.Displays, Route.Appearance, Route.AppUpdate,
        )
        for (route in objects) {
            assertEquals(route, roundTrip(route))
            // data objects are singletons: the decoded value must be the SAME instance.
            assertEquals(true, route === roundTrip(route))
        }
    }

    @Test
    fun serial_names_are_the_stable_route_ids_android_navigation_derives() {
        val names = listOf(
            Route.Home to "home",
            Route.AddHost to "add_host",
            Route.Usage to "usage",
            Route.Devices to "devices",
            Route.Archived to "archived",
            Route.Proxies to "proxies",
            Route.Displays to "displays",
            Route.Appearance to "appearance",
            Route.AppUpdate to "app_update",
        )
        for ((route, name) in names) {
            assertEquals("""{"type":"$name"}""", json.encodeToString(Route.serializer(), route))
        }
        assertEquals(
            "new_session",
            Route.NewSession.serializer().descriptor.serialName,
        )
        assertEquals("settings", Route.Settings.serializer().descriptor.serialName)
    }

    @Test
    fun the_union_covers_both_apps_destinations() {
        // Android's nav/Routes.kt + desktop's shell/DesktopRoute.kt, nothing lost.
        val android = setOf("home", "new_session", "add_host", "settings", "usage", "devices", "archived", "proxies", "displays", "appearance")
        val desktop = setOf("home", "settings", "archived", "app_update")
        val union = android + desktop
        assertEquals(11, union.size)
    }

    @Test
    fun settings_sections_keep_desktops_rail_order_and_labels() {
        assertEquals(
            listOf("Agents", "Devices", "System", "Git hosting", "Proxies", "Identity", "Curator", "Voice", "Editor / LSP", "Personal assistants"),
            SettingsSection.entries.map { it.label },
        )
    }
}
