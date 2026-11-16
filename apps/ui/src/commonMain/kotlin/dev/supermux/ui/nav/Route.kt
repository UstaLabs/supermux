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
        /** #152: a pull / merge request of [projectId] to work on (#72's "Check Out Pull Request…"); 0 = none. */
        val prNumber: Int = 0,
    ) : Route

    @Serializable
    @SerialName("add_host")
    data object AddHost : Route

    /**
     * Settings. [section] null = "just Settings" (the phone's index; General on a wide window). [sub] is a
     * second-level page inside the section (a provider, the glossary, This computer's page); [anchor] a row to scroll
     * to and flash once. See `ui/settings/SettingsPlace.kt`.
     */
    @Serializable
    @SerialName("settings")
    data class Settings(
        val section: SettingsSection? = null,
        val sub: String? = null,
        val anchor: String? = null,
    ) : Route

    @Serializable
    @SerialName("usage")
    data object Usage : Route

    /** What's new (#90): [version]'s edition, a full-pane page on every width. */
    @Serializable
    @SerialName("whats_new")
    data class WhatsNew(val version: String) : Route

    /**
     * The machine page's Devices (#97 Step 2). Kept so callers, links and saved stacks that name it still land:
     * the shell pushes it as `Settings(Machine, anchor = "devices")` (`canonicalRoute`), never as itself.
     */
    @Serializable
    @SerialName("devices")
    data object Devices : Route

    @Serializable
    @SerialName("archived")
    data object Archived : Route

    /** The machine page's Shared links, as [Devices] is its Devices: pushed as `Settings(Machine, anchor = "links")`. */
    @Serializable
    @SerialName("proxies")
    data object Proxies : Route

    @Serializable
    @SerialName("displays")
    data object Displays : Route

    /** #117: All applets, a plain icon grid of the host picker's host's applets. */
    @Serializable
    @SerialName("applets")
    data object Applets : Route

    /** #117: one applet's Applet info, at [tab] (overview, data, jobs, secrets, settings; blank = overview). */
    @Serializable
    @SerialName("applet_info")
    data class AppletInfo(val name: String = "", val tab: String = "") : Route

    /**
     * #117: an applet itself, as a screen of the app (iPhone and iPad, whose one window has no room for an applet
     * window of its own): back and the edge swipe leave it, like every other full screen. [hostId] is the host
     * record whose broker serves it (a widget or Spotlight may name another host than the picker's).
     */
    @Serializable
    @SerialName("applet")
    data class Applet(val hostId: String = "", val name: String = "") : Route

    // Appearance and the updater's page are General's rows since #97 (`openSettings(General)`,
    // `ShellUiState.openAppUpdate`); a saved back stack naming them decodes to Home.
}
