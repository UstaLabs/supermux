package dev.supermux.web.nav

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection

/** What a browser path means: a screen, or the chat of one session. */
sealed interface UrlTarget {
    data class Screen(val route: Route) : UrlTarget
    data class Session(val id: String) : UrlTarget
}

private val sectionSlugs = mapOf(
    SettingsSection.General to "general",
    SettingsSection.Providers to "providers",
    SettingsSection.Voice to "voice",
    SettingsSection.Coding to "coding",
    SettingsSection.Memory to "memory",
    SettingsSection.PrivacyLabs to "privacy",
    SettingsSection.Machine to "machine",
)
private val slugSections = sectionSlugs.entries.associate { (k, v) -> v to k }

/** Bookmarks from before #97 (and before 2026-10 for `agents`): where each old slug lives now. */
private val oldSettingsSlugs: Map<String, Route.Settings> = mapOf(
    "agents" to Route.Settings(SettingsSection.Providers),
    "accounts" to Route.Settings(SettingsSection.Providers),
    "curator" to Route.Settings(SettingsSection.Memory),
    "editor" to Route.Settings(SettingsSection.Coding, anchor = "code-intelligence"),
    "git-hosting" to Route.Settings(SettingsSection.Coding, anchor = "github"),
    "system" to Route.Settings(SettingsSection.Machine, sub = "status"),
    "devices" to Route.Settings(SettingsSection.Machine, sub = "devices"),
    "proxies" to Route.Settings(SettingsSection.Machine, sub = "links"),
    "worktrees" to Route.Settings(SettingsSection.Machine, sub = "worktrees"),
)

/**
 * The URL for a route — the Vue router's table, kept so old bookmarks and `sw.js` clicks still
 * land. Pure: no DOM, so it is unit-testable and callable from anywhere.
 *
 * [selectedId] only shows up on [Route.Home]: every other destination is a full-pane overlay whose
 * URL says nothing about which session is selected underneath it (closing it returns to `/s/<id>`).
 * [Route.Appearance]/[Route.AppUpdate] live under `/settings/` because that is where the Vue app
 * put them, even though they are their own routes here. A Settings sub-page is a third segment
 * (`/settings/machine/links`); its anchor is never in the URL — a flash is a one-off (#97).
 */
fun pathFor(route: Route, selectedId: String?): String = when (route) {
    Route.Home -> if (selectedId != null) "/s/$selectedId" else "/"
    is Route.NewSession -> if (route.draftId.isBlank()) "/new" else "/new?draft=${route.draftId}"
    Route.AddHost -> "/add-host"
    is Route.Settings -> listOfNotNull(
        "/settings",
        route.section?.let { sectionSlugs.getValue(it) },
        route.sub.takeIf { route.section != null },
    ).joinToString("/")
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
    // platform-ok: a URL's route
    val path = pathAndQuery.substringBefore('?').trimEnd('/').ifEmpty { "/" }
    val query = pathAndQuery.substringAfter('?', "")
    // platform-ok: a URL's route
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
        segs[0] == "settings" && segs.size == 1 -> UrlTarget.Screen(Route.Settings())
        segs[0] == "settings" && segs[1] == "appearance" -> UrlTarget.Screen(Route.Appearance)
        segs[0] == "settings" && segs[1] == "updates" -> UrlTarget.Screen(Route.AppUpdate)
        segs[0] == "settings" -> UrlTarget.Screen(
            slugSections[segs[1]]?.let { Route.Settings(it, sub = segs.getOrNull(2)) }
                ?: oldSettingsSlugs[segs[1]]
                ?: Route.Home,
        )
        else -> UrlTarget.Screen(Route.Home)
    }
}

private fun queryParam(query: String, key: String): String? =
    query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
