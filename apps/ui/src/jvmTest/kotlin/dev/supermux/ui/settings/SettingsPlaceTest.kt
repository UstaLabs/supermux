package dev.supermux.ui.settings

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.shell.ShellUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsPlaceTest {
    @Test fun titles() {
        assertEquals("Claude Code", settingsTitle(SettingsPlace(SettingsSection.Providers, sub = "claude")))
        assertEquals("Words to spell your way", settingsTitle(SettingsPlace(SettingsSection.Voice, sub = GLOSSARY_SUB)))
        assertEquals("Shared links", settingsTitle(SettingsPlace(SettingsSection.Machine, sub = MachineSub.LINKS)))
        assertEquals("studio-mini", settingsTitle(SettingsPlace(SettingsSection.Machine), machineName = "studio-mini"))
        assertEquals("Privacy", settingsTitle(SettingsPlace(SettingsSection.PrivacyLabs), labs = false))
    }

    @Test fun a_sub_page_is_kept_only_where_its_page_has_it() {
        assertEquals("codex", settingsSubOrNull(SettingsSection.Providers, "codex"))
        assertNull(settingsSubOrNull(SettingsSection.Providers, "glossary"))
        assertEquals(GLOSSARY_SUB, settingsSubOrNull(SettingsSection.Voice, GLOSSARY_SUB))
        for (sub in listOf("status", "computer", "devices", "links", "worktrees")) assertEquals(sub, settingsSubOrNull(SettingsSection.Machine, sub))
        assertNull(settingsSubOrNull(SettingsSection.Machine, "nope"))
        assertNull(settingsSubOrNull(SettingsSection.General, "theme"))
        assertNull(settingsSubOrNull(SettingsSection.Voice, null))
    }

    @Test fun the_page_title_in_search_and_the_rail_is_the_page_title() {
        val ctx = dev.supermux.ui.settings.search.SettingsSearchContext(dev.supermux.ui.platform.NO_CAPS, phone = false, liveTranscript = false)
        assertEquals("Privacy", dev.supermux.ui.settings.search.settingsPageTitle(SettingsSection.PrivacyLabs, ctx))
        assertEquals("This machine", dev.supermux.ui.settings.search.settingsPageTitle(SettingsSection.Machine, ctx))
    }

    @Test fun plain_settings_is_no_section() {
        val ui = ShellUiState()
        ui.openSettings()
        assertNull((ui.currentRoute as Route.Settings).section)
    }

    @Test fun lsp_opens_coding_at_code_intelligence() {
        val ui = ShellUiState()
        ui.openLspSettings()
        assertEquals(Route.Settings(SettingsSection.Coding, anchor = "code-intelligence"), ui.currentRoute)
        assertEquals(true, ui.lspSettingsOpen)
    }

    @Test fun a_stack_saved_by_an_older_build_restores_with_unknown_sections_as_plain_settings() {
        val ui = ShellUiState()
        ui.openSettings(SettingsSection.Memory)
        val scope = object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ShellUiState.Saver) { scope.save(ui) } as List<*>
        val tampered = listOf((saved[0] as String).replace("\"Memory\"", "\"Curator\"")) + saved.drop(1)
        val restored = ShellUiState.Saver.restore(tampered)!!
        assertEquals(Route.Settings(section = null), restored.currentRoute)
    }

    @Test fun navigate_by_name_still_reaches_settings() {
        val ui = ShellUiState()
        ui.navigateByName("settings")
        assertEquals(Route.Settings(), ui.currentRoute)
    }
}
