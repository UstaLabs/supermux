package dev.supermux.ui.settings.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KitChromeTest {
    private val mac = KitMachine("m1", "Ahmet's MacBook", local = true, online = true, status = "Running")
    private val mini = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline · last seen 2h ago")

    @Test fun the_top_bar_shows_its_title_and_goes_back() = runComposeUiTest {
        var back = 0
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) { KitTopBar("Voice", { back++ }) }
        onNodeWithTag("settings_detail_title").assertExists()
        onNodeWithText("Voice").assertExists()
        onNodeWithTag("settings_detail_back").performClick()
        assertEquals(1, back)
    }

    @Test fun a_sheet_shows_its_title_text_and_buttons() = runComposeUiTest {
        var closed = 0
        setPlatformContent {
            KitSheet("Add a Claude login", { closed++ }, description = "Each login is its own account.") {
                // body
            }
        }
        onNodeWithText("Add a Claude login").assertExists()
        onNodeWithText("Each login is its own account.").assertExists()
    }

    @Test fun a_selected_rail_row_says_so() = runComposeUiTest {
        setPlatformContent { KitNavRow("Voice", KitGlyphs.Mic, selected = true, onClick = {}, modifier = Modifier.testTag("row")) }
        onNodeWithTag("row").assertIsSelected()
    }

    @Test fun a_phone_index_row_shows_its_description() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            KitNavRow("Voice", KitGlyphs.Mic, selected = false, onClick = {}, description = "Dictation, languages, read aloud")
        }
        onNodeWithText("Dictation, languages, read aloud").assertExists()
    }

    @Test fun a_machine_card_shows_name_and_status_and_opens() = runComposeUiTest {
        var opened = 0
        setPlatformContent { KitMachineCard(mini, selected = false, onClick = { opened++ }) }
        onNodeWithText("studio-mini").assertExists()
        onNodeWithText("Offline · last seen 2h ago").assertExists()
        onNodeWithTag("settings_machine_card_m2").performClick()
        assertEquals(1, opened)
    }

    @Test fun a_device_page_says_only_this_computer_on_desktop() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(caps = NO_CAPS.copy(localBroker = true))) { KitDeviceScope() }
        onNodeWithText("Only this computer").assertExists()
    }

    @Test fun one_machine_is_named_without_a_picker() = runComposeUiTest {
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(mac), "m1") {}) { KitMachineScope() }
        }
        onNodeWithText("Ahmet's MacBook").assertExists()
        onNodeWithTag("settings_scope_picker").assertDoesNotExist()
    }

    @Test fun two_machines_get_a_picker_that_switches() = runComposeUiTest {
        var picked: String? = null
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(mac, mini), "m1") { picked = it }) {
                Column { KitMachineScope() }
            }
        }
        onNodeWithTag("settings_scope_picker").performClick()
        onNodeWithTag("settings_machine_pick_m2").performClick()
        assertEquals("m2", picked)
    }

    @Test fun a_mixed_page_has_no_scope_line_with_one_machine() = runComposeUiTest {
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(mac), "m1") {}) { KitMixedScope() }
        }
        onNodeWithTag("settings_scope").assertDoesNotExist()
    }
}
