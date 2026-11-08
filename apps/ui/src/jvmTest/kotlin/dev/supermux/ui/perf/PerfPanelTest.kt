package dev.supermux.ui.perf

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.util.PerfTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Load Timings' Copy (card #54) puts the whole report on the clipboard through the app's [dev.supermux.ui.platform.Platform]. */
@OptIn(ExperimentalTestApi::class)
class PerfPanelTest {
    @Test fun copy_puts_the_report_on_the_platform_clipboard() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform = platform) { PerfPanel(onClose = {}) }
        onNodeWithText("Copy").performClick()
        waitForIdle()
        assertEquals(1, platform.copied.size)
        assertTrue(platform.copied.single().startsWith("Launch ${PerfTrace.launchId}"), platform.copied.single())
    }
}
