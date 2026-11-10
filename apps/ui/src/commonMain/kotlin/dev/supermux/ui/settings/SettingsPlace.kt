// Where inside Settings a page is (#97): the section, an optional second-level page, an optional row to land on.
package dev.supermux.ui.settings

import androidx.compose.runtime.Immutable
import dev.supermux.ui.accounts.agentDisplayName
import dev.supermux.ui.accounts.ACCOUNT_AGENTS
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.settings.kit.MACHINE_FALLBACK_NAME

@Immutable
data class SettingsPlace(val section: SettingsSection, val sub: String? = null, val anchor: String? = null)

/** The interim machine page's screens (Step 1). */
object MachineSub {
    const val STATUS = "status"
    const val COMPUTER = "computer"
    const val DEVICES = "devices"
    const val LINKS = "links"
    const val WORKTREES = "worktrees"

    val ALL = setOf(STATUS, COMPUTER, DEVICES, LINKS, WORKTREES)
}

/** Voice's word list. */
const val GLOSSARY_SUB = "glossary"

/**
 * [sub] when [section] has such a sub-page (a provider's agent, Voice's glossary, a machine screen), else null: an
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
        SettingsSection.Machine -> when (sub) {
            MachineSub.STATUS -> "Status & updates"
            MachineSub.COMPUTER -> "This computer"
            MachineSub.DEVICES -> "Devices"
            MachineSub.LINKS -> "Shared links"
            MachineSub.WORKTREES -> "Worktrees"
            else -> machineName ?: MACHINE_FALLBACK_NAME
        }
        else -> place.section.label
    }
}

/** A provider page's name, as the prototype titles it: Claude's agent is "Claude Code"; the rest by their name. */
private fun providerTitle(agent: String): String = if (agent == "claude") "Claude Code" else agentDisplayName(agent)
