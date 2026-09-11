package dev.supermux.web.nav

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection

/** What a browser path means: a screen, or the chat of one session. */
sealed interface UrlTarget {
    data class Screen(val route: Route) : UrlTarget
    data class Session(val id: String) : UrlTarget
}

private val sectionSlugs = mapOf(
    SettingsSection.Agents to "agents",
    SettingsSection.Devices to "devices",
    SettingsSection.System to "system",
    SettingsSection.GitHosting to "git-hosting",
    SettingsSection.Proxies to "proxies",
    SettingsSection.Assistant to "assistant",
    SettingsSection.Curator to "curator",
    SettingsSection.Voice to "voice",
    SettingsSection.EditorLsp to "editor",
)
private val slugSections = sectionSlugs.entries.associate { (k, v) -> v to k }

/**
 * The URL for a route — the Vue router's table, kept so old bookmarks and `sw.js` clicks still
 * land. Pure: no DOM, so it is unit-testable and callable from anywhere.
 *
 * [selectedId] only shows up on [Route.Home]: every other destination is a full-pane overlay whose
 * URL says nothing about which session is selected underneath it (closing it returns to `/s/<id>`).
 * [SettingsSection.PersonalAssistants] keeps its own top-level path because that is where the Vue
 * app put it, and [Route.Appearance]/[Route.AppUpdate] live under `/settings/` for the same reason
 * even though they are their own routes here.
 */
fun pathFor(route: Route, selectedId: String?): String = when (route) {
    Route.Home -> if (selectedId != null) "/s/$selectedId" else "/"
    is Route.NewSession -> if (route.draftId.isBlank()) "/new" else "/new?draft=${route.draftId}"
    Route.AddHost -> "/add-host"
    is Route.Settings ->
        if (route.section == SettingsSection.PersonalAssistants) "/personal-assistants"
        else "/settings/${sectionSlugs.getValue(route.section)}"
    Route.Usage -> "/usage"
    Route.Devices -> "/devices"
    Route.Archived -> "/archived"
    Route.Proxies -> "/proxies"
    Route.Displays -> "/displays"
    Route.Appearance -> "/settings/appearance"
    Route.AppUpdate -> "/settings/updates"
}

/**
 * The inverse of [pathFor]. Anything unrecognised — a typo, a Vue page this app dropped, the setup
 * wizard that arrives in a later plan — resolves to Home rather than erroring: the address bar is
 * user input, and the worst it may do is open the wrong screen.
 */
fun parsePath(pathAndQuery: String): UrlTarget {
    val path = pathAndQuery.substringBefore('?').trimEnd('/').ifEmpty { "/" }
    val query = pathAndQuery.substringAfter('?', "")
    val segs = path.trimStart('/').split('/').filter { it.isNotEmpty() }
    return when {
        segs.isEmpty() -> UrlTarget.Screen(Route.Home)
        segs[0] == "s" && segs.size == 2 -> UrlTarget.Session(segs[1])
        segs[0] == "new" -> UrlTarget.Screen(Route.NewSession(queryParam(query, "draft") ?: ""))
        segs[0] == "add-host" -> UrlTarget.Screen(Route.AddHost)
        segs[0] == "usage" -> UrlTarget.Screen(Route.Usage)
        segs[0] == "devices" -> UrlTarget.Screen(Route.Devices)
        segs[0] == "archived" -> UrlTarget.Screen(Route.Archived)
        segs[0] == "proxies" -> UrlTarget.Screen(Route.Proxies)
        segs[0] == "displays" -> UrlTarget.Screen(Route.Displays)
        segs[0] == "personal-assistants" -> UrlTarget.Screen(Route.Settings(SettingsSection.PersonalAssistants))
        segs[0] == "settings" && segs.size == 1 -> UrlTarget.Screen(Route.Settings(SettingsSection.Agents))
        segs[0] == "settings" && segs[1] == "appearance" -> UrlTarget.Screen(Route.Appearance)
        segs[0] == "settings" && segs[1] == "updates" -> UrlTarget.Screen(Route.AppUpdate)
        segs[0] == "settings" ->
            slugSections[segs[1]]?.let { UrlTarget.Screen(Route.Settings(it)) } ?: UrlTarget.Screen(Route.Home)
        else -> UrlTarget.Screen(Route.Home)
    }
}

private fun queryParam(query: String, key: String): String? =
    query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
