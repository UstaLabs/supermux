package dev.supermux.ui.settings.search

import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.NO_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val desk = SettingsSearchContext(caps = NO_CAPS.copy(localBroker = true, assistantOverlay = true), phone = false, liveTranscript = false)
    private val entries = listOf(
        SettingsSearchEntry(SettingsSection.General, "theme", "Theme", "dark light mode appearance colour"),
        SettingsSearchEntry(SettingsSection.General, "text-size", "Text size", "font zoom scale"),
        SettingsSearchEntry(SettingsSection.Voice, "languages", "Languages", "language"),
        SettingsSearchEntry(SettingsSection.Voice, "dictation", "Turn speech into text", "dictation", shownWhen = { it.liveTranscript }),
        SettingsSearchEntry(SettingsSection.Coding, "terminal-font", "Terminal font size", "zoom"),
    )

    @Test fun every_word_must_match_in_any_case() {
        val hits = searchSettings("DARK mode", desk, entries).flatMap { it.hits }
        assertEquals(listOf("theme"), hits.map { it.anchor })
    }

    @Test fun the_page_title_counts_as_words_of_its_rows() {
        val hits = searchSettings("voice lang", desk, entries).flatMap { it.hits }
        assertEquals(listOf("languages"), hits.map { it.anchor })
    }

    @Test fun rows_this_device_does_not_show_are_not_found() {
        assertTrue(searchSettings("dictation", desk, entries).isEmpty())
        val phone = desk.copy(phone = true, liveTranscript = true)
        assertEquals(1, searchSettings("dictation", phone, entries).size)
    }

    @Test fun results_are_grouped_by_page_in_index_order() {
        val groups = searchSettings("zoom", desk, entries)
        assertEquals(listOf(SettingsSection.General, SettingsSection.Coding), groups.map { it.section })
    }

    @Test fun a_blank_query_finds_nothing() {
        assertTrue(searchSettings("   ", desk, entries).isEmpty())
    }

    @Test fun privacy_is_called_privacy_where_there_are_no_labs() {
        assertEquals("Privacy & labs", settingsPageTitle(SettingsSection.PrivacyLabs, desk))
        assertEquals("Privacy", settingsPageTitle(SettingsSection.PrivacyLabs, desk.copy(caps = NO_CAPS)))
    }

    @Test fun the_index_has_one_page_entry_per_settings_page() {
        val pages = SettingsSearchIndex.filter { it.anchor == null }.map { it.section }
        assertEquals(
            listOf(
                SettingsSection.General, SettingsSection.Providers, SettingsSection.Voice,
                SettingsSection.Coding, SettingsSection.Memory, SettingsSection.PrivacyLabs,
            ),
            pages,
        )
    }

    @Test fun anchors_are_unique_within_a_page() {
        val dupes = SettingsSearchIndex.filter { it.anchor != null }.groupBy { it.section to it.anchor }.filterValues { it.size > 1 }
        assertTrue(dupes.isEmpty(), "duplicate anchors: ${dupes.keys}")
    }

    @Test fun generals_rows_follow_its_page_entry_in_page_order() {
        val general = SettingsSearchIndex.filter { it.section == SettingsSection.General }.map { it.anchor }
        assertEquals(listOf(null, "theme", "text-size", "live-replies", "chat-detail", "terminal-colours", "app-version", "cache"), general)
        assertEquals(listOf(SettingsSection.General to "app-version"), searchSettings("upgrade", desk, SettingsSearchIndex).flatMap { it.hits }.map { it.section to it.anchor })
        assertEquals(listOf(SettingsSection.General to "cache"), searchSettings("disk media", desk, SettingsSearchIndex).flatMap { it.hits }.map { it.section to it.anchor })
    }

    @Test fun privacy_and_labs_rows_are_found_where_they_are_shown() {
        val stats = searchSettings("statistics", desk).flatMap { it.hits }
        assertEquals(listOf(SettingsSection.PrivacyLabs to "telemetry"), stats.map { it.section to it.anchor })
        val bubble = searchSettings("bubble", desk).flatMap { it.hits }
        assertEquals(listOf(SettingsSection.PrivacyLabs to "assistant-overlay"), bubble.map { it.section to it.anchor })
        val web = desk.copy(caps = NO_CAPS)
        assertTrue(searchSettings("bubble", web).isEmpty())
        assertEquals(1, searchSettings("analytics", web).flatMap { it.hits }.size)
    }
}
