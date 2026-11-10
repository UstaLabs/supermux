// Search over Settings (#97 spec §2.5, §10.9, §11): one declared entry per searchable row, so the index is data a
// test checks against the pages (SettingsSearchAnchorsTest, Task 18) instead of text scraped off the screen. Each
// page task adds its rows here, in page order.
package dev.supermux.ui.settings.search

import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.settingsTitle

/** What this device shows, for rows that exist only on some platforms. */
data class SettingsSearchContext(val caps: Caps, val phone: Boolean, val liveTranscript: Boolean)

/** One searchable row; [anchor] null is the page itself. [keywords] are other words people type for it. */
data class SettingsSearchEntry(
    val section: SettingsSection,
    val anchor: String?,
    val label: String,
    val keywords: String = "",
    val shownWhen: (SettingsSearchContext) -> Boolean = { true },
)

data class SettingsSearchGroup(val section: SettingsSection, val hits: List<SettingsSearchEntry>)

/** A page's name in search and in the rail: Privacy & labs is "Privacy" on a device without labs. */
fun settingsPageTitle(section: SettingsSection, ctx: SettingsSearchContext): String =
    settingsTitle(SettingsPlace(section), labs = ctx.caps.assistantOverlay)

/** Every word of [query] must appear, in any case, in an entry's label, keywords or page title. */
fun searchSettings(
    query: String,
    ctx: SettingsSearchContext,
    entries: List<SettingsSearchEntry> = SettingsSearchIndex,
    limit: Int = 40,
): List<SettingsSearchGroup> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return entries.asSequence()
        .filter { it.shownWhen(ctx) }
        .filter { e ->
            val hay = "${e.label} ${e.keywords} ${settingsPageTitle(e.section, ctx)}".lowercase()
            words.all { it in hay }
        }
        .take(limit)
        .toList()
        .groupBy { it.section }
        .map { (section, hits) -> SettingsSearchGroup(section, hits) }
}

private fun page(section: SettingsSection, keywords: String = "") = SettingsSearchEntry(section, null, section.label, keywords)

/** The index. Page entries first in each page's block, then its rows in page order (page tasks append). */
val SettingsSearchIndex: List<SettingsSearchEntry> = listOf(
    page(SettingsSection.General, "appearance preferences"),
    page(SettingsSection.Providers, "agents accounts logins sign in"),
    page(SettingsSection.Voice, "dictation speech"),
    page(SettingsSection.Coding, "editor git"),
    page(SettingsSection.Memory, "curator"),
    page(SettingsSection.PrivacyLabs, "telemetry experimental"),
)
