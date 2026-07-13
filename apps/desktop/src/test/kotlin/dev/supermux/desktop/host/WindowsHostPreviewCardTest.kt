package dev.supermux.desktop.host

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.SessionListPanel
import dev.supermux.proto.SessionInfo
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proofs for the Windows preview card (Plan 3 Task 6): the pure OS gate, the local sign-up recorder,
 * the card's join/explainer/WSL affordances, and that the fleet list only shows it when asked (Windows).
 */
@OptIn(ExperimentalTestApi::class)
class WindowsHostPreviewCardTest {

    @Test fun osGate_showsOnlyOnWindows() {
        assertTrue(shouldShowWindowsPreview("Windows 11"))
        assertTrue(shouldShowWindowsPreview("Windows Server 2022"))
        assertFalse(shouldShowWindowsPreview("Mac OS X"))
        assertFalse(shouldShowWindowsPreview("Linux"))
    }

    @Test fun signupRecorder_appendsLocallyWithoutNetwork() {
        val dir = createTempDirectory("win-preview").also { it.toFile().deleteOnExit() }
        val log = dir.resolve("nested/preview.log")
        assertTrue(recordWindowsPreviewSignup(log, nowMs = 123L))
        assertTrue(recordWindowsPreviewSignup(log, nowMs = 456L))
        val text = java.nio.file.Files.readString(log)
        assertTrue(text.contains("123") && text.contains("456"), "both signups appended")
    }

    @Test fun card_showsCopyJoinAndWslPath() = runComposeUiTest {
        var joined = false
        var wslOpened = false
        setContent {
            WindowsHostPreviewCard(
                onJoinPreview = { joined = true; true },
                onOpenWslGuide = { wslOpened = true },
            )
        }
        onNodeWithTag("windows_host_preview_card").assertIsDisplayed()
        onNodeWithText(WINDOWS_PREVIEW_TITLE, substring = true).assertIsDisplayed()
        onNodeWithText(WINDOWS_PREVIEW_SUBTITLE, substring = true).assertIsDisplayed()

        // Join records the sign-up and swaps to the thank-you.
        onNodeWithTag("windows_host_preview_join").performClick()
        assertTrue(joined)
        onNodeWithTag("windows_host_preview_thanks").assertIsDisplayed()

        // The advanced WSL path is behind the explainer toggle.
        onNodeWithTag("windows_host_preview_wsl").assertDoesNotExist()
        onNodeWithTag("windows_host_preview_explain_toggle").performClick()
        onNodeWithTag("windows_host_preview_wsl").assertIsDisplayed()
        onNodeWithTag("windows_host_preview_wsl").performClick()
        assertTrue(wslOpened)
    }

    private fun session(id: String) = SessionInfo(id = id, name = "s-$id", workdir = "/home/u/p", agent = "claude")

    @Test fun fleetList_showsPreviewCard_onlyWhenEnabled() = runComposeUiTest {
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                showWindowsPreview = true,
                onJoinWindowsPreview = { true },
            )
        }
        onNodeWithTag("windows_host_preview_card").assertIsDisplayed()
    }

    @Test fun fleetList_hidesPreviewCard_onNativeHosts() = runComposeUiTest {
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                showWindowsPreview = false,
            )
        }
        onNodeWithTag("windows_host_preview_card").assertDoesNotExist()
    }
}
