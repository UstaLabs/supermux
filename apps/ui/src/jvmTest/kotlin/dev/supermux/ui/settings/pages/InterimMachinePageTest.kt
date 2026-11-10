package dev.supermux.ui.settings.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.settings.MachineSub
import kotlin.test.Test
import kotlin.test.assertEquals

/** Step 1's machine page (#97 spec §5): one row per screen of the active machine, each opening it as a sub-page. */
@OptIn(ExperimentalTestApi::class)
class InterimMachinePageTest {

    @Test fun every_row_opens_its_screen() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            InterimMachinePage(onOpen = { opened += it }, showComputer = true)
        }
        waitForIdle()
        onNodeWithTag("machine_page").assertExists()
        listOf("status", "computer", "devices", "links", "worktrees").forEach {
            onNodeWithTag("machine_row_$it").performScrollTo().performClick()
            waitForIdle()
        }
        assertEquals(
            listOf(MachineSub.STATUS, MachineSub.COMPUTER, MachineSub.DEVICES, MachineSub.LINKS, MachineSub.WORKTREES),
            opened,
        )
    }

    @Test fun this_computer_is_listed_only_where_the_app_can_host() = runComposeUiTest {
        var canHost by mutableStateOf(false)
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            InterimMachinePage(onOpen = {}, showComputer = canHost)
        }
        waitForIdle()
        onNodeWithTag("machine_row_computer").assertDoesNotExist()
        onNodeWithTag("machine_row_status").assertExists()
        onNodeWithTag("machine_row_devices").assertExists()
        onNodeWithTag("machine_row_links").assertExists()
        onNodeWithTag("machine_row_worktrees").assertExists()
        canHost = true
        waitForIdle()
        onNodeWithTag("machine_row_computer").assertExists()
    }
}
