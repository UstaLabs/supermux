package dev.supermux.ui.terminal

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals

/** A clicked terminal hyperlink opens through the app's [dev.supermux.ui.platform.Platform], which never throws. */
@OptIn(ExperimentalTestApi::class)
class TerminalLinkDialogTest {
    @Test fun open_hands_the_link_to_the_platform() = runComposeUiTest {
        val platform = FakePlatform()
        var dismissed = false
        setPlatformContent(platform = platform) {
            TerminalLinkDialog(uri = "https://example.com/docs", onCopy = {}, onDismiss = { dismissed = true })
        }
        onNodeWithTag("terminal_link_open").performClick()
        waitForIdle()
        assertEquals(listOf("https://example.com/docs"), platform.openedUrls)
        assertEquals(true, dismissed)
    }
}
