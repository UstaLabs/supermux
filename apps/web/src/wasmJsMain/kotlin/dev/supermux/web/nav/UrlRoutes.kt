package dev.supermux.web.nav

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.settings.canonicalRoute
import dev.supermux.ui.settings.settingsSubOrNull

/** What a browser path means: a screen, or the chat of one session. */
sealed interface UrlTarget {
    data class Screen(val route: Route) : UrlTarget
    data class Session(val id: String) : UrlTarget
}

private val sectionSlugs = mapOf(
    SettingsSection.General to "general",
    SettingsSection.KeyboardShortcuts to "shortcuts",
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
    "system" to Route.Settings(SettingsSection.Machine, anchor = "updates"),
    "devices" to Route.Settings(SettingsSection.Machine, anchor = "devices"),
    "proxies" to Route.Settings(SettingsSection.Machine, anchor = "links"),
    // The machine page's Worktrees sub-page (#97 Step 2).
    "worktrees" to Route.Settings(SettingsSection.Machine, sub = "worktrees"),
    "appearance" to Route.Settings(SettingsSection.General),
    "updates" to Route.Settings(SettingsSection.General, anchor = "app-version"),
)

/**
 * The URL for a route — the Vue router's table, kept so old bookmarks and `sw.js` clicks still
 * land. Pure: no DOM, so it is unit-testable and callable from anywhere.
 *
 * [selectedId] only shows up on [Route.Home]: every other destination is a full-pane overlay whose
 * URL says nothing about which session is selected underneath it (closing it returns to `/s/<id>`).
 * A Settings sub-page is a third segment
 * (`/settings/providers/claude`); its anchor is never in the URL — a flash is a one-off (#97). The old Devices and
 * Proxies pages are the machine page's parts (`canonicalRoute`), so they share its address.
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
    Route.Devices, Route.Proxies -> pathFor(canonicalRoute(route), selectedId)
    Route.Archived -> "/archived"
    Route.Displays -> "/displays"
    is Route.WhatsNew -> "/whats-new/${route.version}"
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
        // Old bookmarks of the standalone pages: the machine page at that part, so the canonical URL replaces them.
        segs[0] == "devices" -> UrlTarget.Screen(canonicalRoute(Route.Devices))
        segs[0] == "archived" -> UrlTarget.Screen(Route.Archived)
        segs[0] == "proxies" -> UrlTarget.Screen(canonicalRoute(Route.Proxies))
        segs[0] == "displays" -> UrlTarget.Screen(Route.Displays)
        segs[0] == "whats-new" && segs.size == 2 -> UrlTarget.Screen(Route.WhatsNew(segs[1]))
        segs[0] == "settings" && segs.size == 1 -> UrlTarget.Screen(Route.Settings())
        segs[0] == "settings" -> UrlTarget.Screen(
            // Step 1's machine screens land on the machine page at their part; any other sub-page the page does not
            // have (a typo, an old link) opens the page itself.
            slugSections[segs[1]]?.let { section ->
                (canonicalRoute(Route.Settings(section, sub = segs.getOrNull(2))) as Route.Settings)
                    .let { it.copy(sub = settingsSubOrNull(section, it.sub)) }
            }
                ?: oldSettingsSlugs[segs[1]]
                ?: Route.Home,
        )
        else -> UrlTarget.Screen(Route.Home)
    }
}

private fun queryParam(query: String, key: String): String? =
    query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
