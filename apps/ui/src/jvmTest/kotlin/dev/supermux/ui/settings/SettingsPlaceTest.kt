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
        assertEquals("This computer", settingsTitle(SettingsPlace(SettingsSection.Machine, sub = MachineSub.COMPUTER), machineName = "studio-mini"))
        assertEquals("studio-mini", settingsTitle(SettingsPlace(SettingsSection.Machine), machineName = "studio-mini"))
        assertEquals("Chat worktrees", settingsTitle(SettingsPlace(SettingsSection.Machine, sub = MachineSub.WORKTREES), machineName = "studio-mini"))
        assertEquals("Privacy", settingsTitle(SettingsPlace(SettingsSection.PrivacyLabs), labs = false))
    }

    @Test fun a_sub_page_is_kept_only_where_its_page_has_it() {
        assertEquals("codex", settingsSubOrNull(SettingsSection.Providers, "codex"))
        assertNull(settingsSubOrNull(SettingsSection.Providers, "glossary"))
        assertEquals(GLOSSARY_SUB, settingsSubOrNull(SettingsSection.Voice, GLOSSARY_SUB))
        assertEquals("computer", settingsSubOrNull(SettingsSection.Machine, "computer"))
        // The Worktrees page is the machine page's sub-page (#97 Step 2); Step 1's other machine screens are parts of it.
        assertEquals("worktrees", settingsSubOrNull(SettingsSection.Machine, MachineSub.WORKTREES))
        for (sub in listOf("status", "devices", "links")) assertNull(settingsSubOrNull(SettingsSection.Machine, sub))
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

    @Test fun the_old_machine_screens_land_on_the_machine_page_at_their_part() {
        val machine = SettingsSection.Machine
        assertEquals(Route.Settings(machine, anchor = "updates"), canonicalRoute(Route.Settings(machine, sub = "status")))
        assertEquals(Route.Settings(machine, anchor = "devices"), canonicalRoute(Route.Settings(machine, sub = "devices")))
        assertEquals(Route.Settings(machine, anchor = "links"), canonicalRoute(Route.Settings(machine, sub = "links")))
        // Worktrees is a sub-page of its own now: its old screen is itself, and its old anchor opens it.
        assertEquals(Route.Settings(machine, sub = "worktrees"), canonicalRoute(Route.Settings(machine, sub = "worktrees")))
        assertEquals(Route.Settings(machine, sub = "worktrees"), canonicalRoute(Route.Settings(machine, anchor = "worktrees")))
        // An explicit anchor wins over the old screen's.
        assertEquals(Route.Settings(machine, anchor = "pair"), canonicalRoute(Route.Settings(machine, sub = "devices", anchor = "pair")))
        // "This computer" is the card's own page, not an old screen.
        assertEquals(Route.Settings(machine, sub = "computer"), canonicalRoute(Route.Settings(machine, sub = "computer")))
        // The standalone pages are the machine page's parts too.
        assertEquals(Route.Settings(machine, anchor = "devices"), canonicalRoute(Route.Devices))
        assertEquals(Route.Settings(machine, anchor = "links"), canonicalRoute(Route.Proxies))
        // Everything else is itself.
        assertEquals(Route.Settings(SettingsSection.Providers, sub = "claude"), canonicalRoute(Route.Settings(SettingsSection.Providers, sub = "claude")))
        assertEquals(Route.Usage, canonicalRoute(Route.Usage))
    }

    @Test fun every_way_to_the_old_screens_opens_the_machine_page() {
        val ui = ShellUiState()
        ui.navigate(Route.Devices)
        assertEquals(listOf(Route.Home, Route.Settings(SettingsSection.Machine, anchor = "devices")), ui.backStack.toList())
        ui.navigate(Route.Proxies)
        assertEquals(Route.Settings(SettingsSection.Machine, anchor = "links"), ui.currentRoute)
        ui.navigateByName("devices")
        assertEquals(Route.Settings(SettingsSection.Machine, anchor = "devices"), ui.currentRoute)
        ui.navigateByName("proxies")
        assertEquals(Route.Settings(SettingsSection.Machine, anchor = "links"), ui.currentRoute)
        ui.openSettings(SettingsSection.Machine, sub = "worktrees")
        assertEquals(Route.Settings(SettingsSection.Machine, sub = "worktrees"), ui.currentRoute)
        ui.openMachine("worktrees")
        assertEquals(Route.Settings(SettingsSection.Machine, sub = "worktrees"), ui.currentRoute)
        assertEquals("worktrees", ui.settingsSub)
        ui.openMachine()
        assertEquals(Route.Settings(SettingsSection.Machine), ui.currentRoute)
    }

    @Test fun a_saved_stack_with_an_old_screen_restores_on_the_machine_page() {
        val scope = object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }
        for ((old, anchor) in listOf("devices" to "devices", "proxies" to "links")) {
            val ui = ShellUiState().apply { openUsage() }
            val saved = with(ShellUiState.Saver) { scope.save(ui) } as List<*>
            val tampered = listOf((saved[0] as String).replace("\"usage\"", "\"$old\"")) + saved.drop(1)
            val restored = ShellUiState.Saver.restore(tampered)!!
            assertEquals(listOf(Route.Home, Route.Settings(SettingsSection.Machine, anchor = anchor)), restored.backStack.toList(), old)
        }
        val ui = ShellUiState().apply { backStack.add(Route.Settings(SettingsSection.Machine, sub = "status")) }
        val saved = with(ShellUiState.Saver) { scope.save(ui) } as List<*>
        assertEquals(Route.Settings(SettingsSection.Machine, anchor = "updates"), ShellUiState.Saver.restore(saved)!!.currentRoute)
    }
}
