package dev.supermux.webview.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.runtime.RuntimeConfig
import kotlin.test.Test

/** The checks page composes, on the desktop's fake engine, with its menu over it (Task 20). */
@OptIn(ExperimentalTestApi::class)
class ChecksScreenTest {
    @Test fun theChecksPageShowsUnderItsControlsAndItsMenu() = runComposeUiTest {
        val test = testLab(RuntimeConfig.platformDefault())
        setContent { SampleApp(test.lab, initialScreen = Screen.PerfLab) }
        onNodeWithTag(SampleTags.screen(Screen.Checks)).performClick()
        waitForIdle()
        onNodeWithTag(SampleTags.ChecksPage).assertExists()
        onNodeWithTag(SampleTags.ChecksMenu).performClick()
        onNodeWithText("Post “hello” to the page").assertExists()
        assertClean(test)
    }

    @Test fun theChecksTabCanBeReachedOnAPhoneWideScreen() = runComposeUiTest {
        val test = testLab(RuntimeConfig.platformDefault())
        // The Fold7's cover screen: 1080 px at 420 dpi is 411 dp wide, too narrow for the header's title and three tabs.
        setContent { Box(Modifier.width(411.dp)) { SampleApp(test.lab, initialScreen = Screen.PerfLab) } }
        onNodeWithTag(SampleTags.screen(Screen.Checks)).performScrollTo().assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag(SampleTags.ChecksPage).assertExists()
        assertClean(test)
    }
}
