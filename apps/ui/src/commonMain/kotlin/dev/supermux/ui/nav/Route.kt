// The one destination model for both apps.
//
// It is the UNION of Android's `nav/Routes.kt` (type-safe navigation-compose destinations) and
// desktop's `shell/DesktopRoute.kt` (Navigation 3 keys in `ShellUiState.backStack`). Each app still
// renders its own way — Android keeps `NavHost`/`composable<…>`, desktop keeps the Nav3 back stack
// (the Nav3 render swap on Android is cluster G) — but they now agree on WHAT the destinations are.
//
// Every member is `@Serializable` with an explicit `@SerialName`, because Android's type-safe
// navigation derives its route string from the serial descriptor's name: `composable<Route.Home>`
// registers "home", `navigate(Route.NewSession("d1"))` fills the `draftId` argument. Objects are
// `data object`s so they serialize as (and compare like) singletons.
package dev.supermux.ui.nav

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A full-screen destination.
 *
 * Android: [Home] is the keep-alive list↔chat destination; the active session id is hoisted state
 * in `MainActivity`, not a nav argument.
 *
 * Desktop: [Home] is the persistent workspace shell and always the back-stack root; the others are
 * full-pane overlays pushed on top of it so chat/editor/terminal composition under Home is not
 * disposed. The New-Session launcher and the Usage card are deliberately NOT desktop routes
 * (`ShellUiState.launcherOpen` / `usageOpen`) even though Android has [NewSession] and [Usage].
 */
@Serializable
sealed interface Route {

    @Serializable
    @SerialName("home")
    data object Home : Route

    /**
     * [draftId] non-empty → reopen that draft session in the launcher (web `/new?draft=`).
     * [projectId] non-empty → preselect that persistent project of host [projectHostId] (the
     * sidebar's "+" on a project). Empty strings, not nulls, for navigation-compose arguments.
     */
    @Serializable
    @SerialName("new_session")
    data class NewSession(
        val draftId: String = "",
        val projectHostId: String = "",
        val projectId: String = "",
    ) : Route

    @Serializable
    @SerialName("add_host")
    data object AddHost : Route

    /** [section] is the rail selection inside the hub; desktop reads/writes it, Android defaults it. */
    @Serializable
    @SerialName("settings")
    data class Settings(val section: SettingsSection = SettingsSection.Agents) : Route

    @Serializable
    @SerialName("usage")
    data object Usage : Route

    @Serializable
    @SerialName("devices")
    data object Devices : Route

    @Serializable
    @SerialName("archived")
    data object Archived : Route

    @Serializable
    @SerialName("proxies")
    data object Proxies : Route

    @Serializable
    @SerialName("displays")
    data object Displays : Route

    @Serializable
    @SerialName("appearance")
    data object Appearance : Route

    /** Desktop-only today: the in-app updater's full-pane screen. */
    @Serializable
    @SerialName("app_update")
    data object AppUpdate : Route
}
