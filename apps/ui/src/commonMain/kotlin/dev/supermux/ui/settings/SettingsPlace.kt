// Where inside Settings a page is (#97): the section, an optional second-level page, an optional row to land on.
package dev.supermux.ui.settings

import androidx.compose.runtime.Immutable
import dev.supermux.ui.accounts.agentDisplayName
import dev.supermux.ui.accounts.ACCOUNT_AGENTS
import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.settings.kit.MACHINE_FALLBACK_NAME

@Immutable
data class SettingsPlace(val section: SettingsSection, val sub: String? = null, val anchor: String? = null)

/**
 * The machine page's sub-places: [COMPUTER] is the "This computer" card's page, whose hosting is this computer's;
 * [WORKTREES] is the machine's chat worktrees, opened from its Disk row (#97 Step 2).
 */
object MachineSub {
    const val COMPUTER = "computer"
    const val WORKTREES = "worktrees"

    val ALL = setOf(COMPUTER, WORKTREES)
}

/**
 * Step 1's machine screens (`Machine/status`, …) are parts of the machine page since Step 2 (#97): each old address
 * lands on the page at its part.
 */
private val OLD_MACHINE_SCREENS = mapOf(
    "status" to "updates",
    "devices" to "devices",
    "links" to "links",
)

/**
 * Where [route] really goes: the old Devices and Proxies destinations and Step 1's machine screens are the machine
 * page at their part (#97 Step 2), and the old Disk anchor `worktrees` is the Worktrees sub-page; every other route is
 * itself. The shell pushes and restores only these, so menus,
 * hooks, saved back stacks and web addresses that name the old places keep working.
 */
fun canonicalRoute(route: Route): Route = when (route) {
    Route.Devices -> Route.Settings(SettingsSection.Machine, anchor = "devices")
    Route.Proxies -> Route.Settings(SettingsSection.Machine, anchor = "links")
    is Route.Settings -> if (route.section == SettingsSection.Machine && route.sub == null && route.anchor == MachineSub.WORKTREES) {
        route.copy(sub = MachineSub.WORKTREES, anchor = null)
    } else {
        val part = route.sub?.takeIf { route.section == SettingsSection.Machine }?.let(OLD_MACHINE_SCREENS::get)
        if (part != null) route.copy(sub = null, anchor = route.anchor ?: part) else route
    }
    else -> route
}

/** Voice's word list. */
const val GLOSSARY_SUB = "glossary"

/**
 * [sub] when [section] has such a sub-page (a provider's agent, Voice's glossary, This computer's page), else null: an
 * address bar is user input, and a sub-page a page does not have would draw a blank page.
 */
fun settingsSubOrNull(section: SettingsSection, sub: String?): String? = sub?.takeIf {
    when (section) {
        SettingsSection.Providers -> it in ACCOUNT_AGENTS
        SettingsSection.Voice -> it == GLOSSARY_SUB
        SettingsSection.Machine -> it in MachineSub.ALL
        else -> false
    }
}

/**
 * A page's title: the phone's top bar and the wide page heading. [machineName] names the Machine page; [labs] is
 * false on a device without lab features, where Privacy & labs is just "Privacy".
 */
fun settingsTitle(place: SettingsPlace, machineName: String? = null, labs: Boolean = true): String {
    val sub = place.sub
    return when (place.section) {
        SettingsSection.Providers -> if (sub != null) providerTitle(sub) else place.section.label
        SettingsSection.Voice -> if (sub == GLOSSARY_SUB) "Words to spell your way" else place.section.label
        SettingsSection.PrivacyLabs -> if (labs) "Privacy & labs" else "Privacy"
        // The "This computer" card's page: no local host is paired, so it is not the current machine's.
        SettingsSection.Machine -> when (sub) {
            MachineSub.COMPUTER -> "This computer"
            MachineSub.WORKTREES -> "Chat worktrees"
            else -> machineName ?: MACHINE_FALLBACK_NAME
        }
        else -> place.section.label
    }
}

/** A provider page's name, as the prototype titles it: Claude's agent is "Claude Code"; the rest by their name. */
private fun providerTitle(agent: String): String = if (agent == "claude") "Claude Code" else agentDisplayName(agent)
