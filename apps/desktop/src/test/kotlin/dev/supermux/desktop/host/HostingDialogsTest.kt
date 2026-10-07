package dev.supermux.desktop.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HostingDialogsTest {
    private val takeoverText =
        "supermux is already running on this computer (set up outside the app). Let the app manage and update it?"

    @Test fun takeoverManage() = runComposeUiTest {
        val answers = mutableListOf<Boolean>()
        setContent {
            HostingDialogs(HostingStatus.AskTakeover("h1"), null, onTakeover = { answers += it }, onDowngrade = {}, onQuit = {}, onCancelQuit = {})
        }
        onNodeWithText(takeoverText).assertIsDisplayed()
        onNodeWithText("Manage it").performClick()
        onNodeWithText("Leave it alone").performClick()
        assertEquals(listOf(true, false), answers)
    }

    @Test fun downgradeKeepIsNotUseBundled() = runComposeUiTest {
        val answers = mutableListOf<Boolean>()
        setContent {
            HostingDialogs(HostingStatus.AskDowngrade("h1"), null, onTakeover = {}, onDowngrade = { answers += it }, onQuit = {}, onCancelQuit = {})
        }
        onNodeWithText("A newer supermux is running. Keep it until the app catches up?").assertIsDisplayed()
        onNodeWithText("Keep it").performClick()
        onNodeWithText("Use this app's version").performClick()
        assertEquals(listOf(false, true), answers)
    }

    @Test fun quitConfirm() = runComposeUiTest {
        var quit = 0
        var confirm by mutableStateOf<String?>("This stops supermux and your 2 running sessions.")
        setContent {
            HostingDialogs(HostingStatus.Running(9898, false), confirm, onTakeover = {}, onDowngrade = {}, onQuit = { quit++ }, onCancelQuit = { confirm = null })
        }
        onNodeWithText("This stops supermux and your 2 running sessions.").assertIsDisplayed()
        onNodeWithText("Quit").performClick()
        assertEquals(1, quit)
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        onNodeWithText("This stops supermux and your 2 running sessions.").assertDoesNotExist()
    }

    @Test fun noQuestionNoDialog() = runComposeUiTest {
        setContent {
            HostingDialogs(HostingStatus.Running(9898, false), null, onTakeover = {}, onDowngrade = {}, onQuit = {}, onCancelQuit = {})
        }
        onNodeWithText("Manage it").assertDoesNotExist()
        onNodeWithText("Quit").assertDoesNotExist()
    }
}
