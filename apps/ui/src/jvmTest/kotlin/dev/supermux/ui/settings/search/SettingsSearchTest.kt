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
                SettingsSection.General, SettingsSection.KeyboardShortcuts, SettingsSection.Providers,
                SettingsSection.Voice, SettingsSection.Coding, SettingsSection.Memory, SettingsSection.PrivacyLabs,
            ),
            pages,
        )
    }

    @Test fun a_sub_page_result_names_a_sub_page_its_section_has() {
        val subs = SettingsSearchIndex.filter { it.sub != null }
        for (e in subs) assertEquals(e.sub, dev.supermux.ui.settings.settingsSubOrNull(e.section, e.sub), "${e.section}/${e.sub}")
        // The machine's worktrees are the Worktrees page now, not a row on the machine page (#97 Step 2).
        val hits = searchSettings("worktrees", desk).flatMap { it.hits }
        assertEquals(listOf(SettingsSection.Machine to dev.supermux.ui.settings.MachineSub.WORKTREES), hits.map { it.section to it.sub })
    }

    @Test fun anchors_are_unique_within_a_page() {
        val dupes = SettingsSearchIndex.filter { it.anchor != null }.groupBy { it.section to it.anchor }.filterValues { it.size > 1 }
        assertTrue(dupes.isEmpty(), "duplicate anchors: ${dupes.keys}")
    }

    @Test fun generals_rows_follow_its_page_entry_in_page_order() {
        val general = SettingsSearchIndex.filter { it.section == SettingsSection.General }.map { it.anchor }
        assertEquals(listOf(null, "theme", "language", "text-size", "live-replies", "chat-detail", "terminal-colours", "app-version", "cache"), general)
        assertEquals(listOf(SettingsSection.General to "app-version"), searchSettings("upgrade", desk, SettingsSearchIndex).flatMap { it.hits }.map { it.section to it.anchor })
        assertEquals(listOf(SettingsSection.General to "cache"), searchSettings("disk media", desk, SettingsSearchIndex).flatMap { it.hits }.map { it.section to it.anchor })
        assertEquals(listOf(SettingsSection.General to "language"), searchSettings("türkçe", desk, SettingsSearchIndex).flatMap { it.hits }.map { it.section to it.anchor })
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

    @Test fun memorys_rows_follow_its_page_entry_in_page_order() {
        val memory = SettingsSearchIndex.filter { it.section == SettingsSection.Memory }.map { it.anchor }
        assertEquals(listOf(null, "memory-nightly", "memory-time", "memory-next", "memory-agent"), memory)
        assertEquals(
            listOf(SettingsSection.Memory to "memory-next"),
            searchSettings("run now", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        assertEquals(
            listOf(SettingsSection.Memory to "memory-agent"),
            searchSettings("curator effort", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
    }

    @Test fun voices_rows_follow_its_page_entry_in_page_order() {
        val voice = SettingsSearchIndex.filter { it.section == SettingsSection.Voice }.map { it.anchor }
        assertEquals(
            listOf(null, "speech-engine", "languages", "glossary", "read-aloud", "tidy-up-engine", "tidy-up-model", "dictation"),
            voice,
        )
        assertEquals(
            listOf(SettingsSection.Voice to "glossary"),
            searchSettings("spelling", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        // "Turn speech into text" is only where this device has its own recogniser.
        assertTrue(searchSettings("offline", desk).flatMap { it.hits }.none { it.anchor == "dictation" })
        assertTrue(searchSettings("offline", desk.copy(liveTranscript = true)).flatMap { it.hits }.any { it.anchor == "dictation" })
    }

    @Test fun codings_rows_follow_its_page_entry_in_page_order() {
        val coding = SettingsSearchIndex.filter { it.section == SettingsSection.Coding }.map { it.anchor }
        assertEquals(
            listOf(
                null, "github", "wrap", "editor-font", "terminal-font", "side-by-side", "accessory-bar",
                "code-intelligence", "add-language-server",
            ),
            coding,
        )
        assertEquals(
            listOf(SettingsSection.Coding to "github"),
            searchSettings("token", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        assertEquals(
            listOf(SettingsSection.Coding to "terminal-font"),
            searchSettings("pinch", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        assertEquals(
            listOf(SettingsSection.Coding to "add-language-server"),
            searchSettings("custom lsp", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        // The keys above the keyboard are a phone's.
        assertTrue(searchSettings("accessory", desk).flatMap { it.hits }.none { it.anchor == "accessory-bar" })
        assertTrue(searchSettings("accessory", desk.copy(phone = true)).flatMap { it.hits }.any { it.anchor == "accessory-bar" })
    }

    @Test fun providers_rows_follow_its_page_entry_in_page_order() {
        val providers = SettingsSearchIndex.filter { it.section == SettingsSection.Providers }.map { it.anchor }
        assertEquals(
            listOf(
                null, "provider-claude", "provider-codex", "provider-opencode", "provider-cursor", "provider-grok",
                "provider-antigravity", "auto-switch", "limit-resume",
            ),
            providers,
        )
        assertEquals(
            listOf(SettingsSection.Providers to "provider-claude"),
            searchSettings("claude code", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        assertEquals(
            listOf(SettingsSection.Providers to "auto-switch"),
            searchSettings("auto switch", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        assertEquals(
            listOf(SettingsSection.Providers to "limit-resume"),
            searchSettings("resume", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        // Every provider is found by what people type for it.
        assertEquals(6, searchSettings("install", desk).flatMap { it.hits }.count { it.anchor?.startsWith("provider-") == true })
    }

    @Test fun the_shortcuts_page_and_every_command_are_found_with_a_hardware_keyboard() {
        val shortcuts = SettingsSearchIndex.filter { it.section == SettingsSection.KeyboardShortcuts }.map { it.anchor }
        assertEquals(listOf(null) + dev.supermux.ui.keys.AppCommands.all.map { "key-${it.id}" }, shortcuts)
        assertEquals(
            listOf(SettingsSection.KeyboardShortcuts to "key-files.goTo"),
            searchSettings("go to file", desk).flatMap { it.hits }.map { it.section to it.anchor },
        )
        assertTrue(searchSettings("hotkeys", desk).flatMap { it.hits }.any { it.section == SettingsSection.KeyboardShortcuts && it.anchor == null })
    }

    @Test fun without_a_hardware_keyboard_the_shortcuts_are_not_found() {
        val touch = desk.copy(hardwareKeyboard = false)
        assertTrue(searchSettings("go to file", touch).isEmpty())
        assertTrue(searchSettings("hotkeys", touch).isEmpty())
    }

    @Test fun the_cache_row_is_found_only_where_there_is_a_cache() {
        assertEquals(1, searchSettings("disk media", desk).flatMap { it.hits }.size)
        assertTrue(searchSettings("disk media", desk.copy(mediaCache = false)).isEmpty())
    }
}
