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
}
