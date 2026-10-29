package dev.supermux.webview.sample

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.supermux.webview.runtime.RuntimeConfig
import kotlin.test.Test

/** The checks page composes, on the desktop's fake engine, with its menu over it (Task 20). */
@OptIn(ExperimentalTestApi::class)
class ChecksScreenTest {
    @Test fun theChecksPageShowsUnderItsControlsAndItsMenu() = runComposeUiTest {
        val test = testLab(RuntimeConfig.platformDefault())
        setContent { SampleApp(test.lab) }
        onNodeWithTag(SampleTags.screen(Screen.Checks)).performClick()
        waitForIdle()
        onNodeWithTag(SampleTags.ChecksPage).assertExists()
        onNodeWithTag(SampleTags.ChecksMenu).performClick()
        onNodeWithText("Post “hello” to the page").assertExists()
        assertClean(test)
    }
}
