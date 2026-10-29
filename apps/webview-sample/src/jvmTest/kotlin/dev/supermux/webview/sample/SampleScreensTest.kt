package dev.supermux.webview.sample

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.supermux.webview.runtime.RuntimeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The sample's screens compose and draw, with the desktop app's sizes, through their own controls. Each is written to
 * build/sample-screens/<name>.png, so a person can look at what nobody can open a window to see.
 */
@OptIn(ExperimentalTestApi::class)
class SampleScreensTest {
    private val page = SampleEngineBehavior.color

    @Test fun theSampleScreensComposeAndDraw() = runComposeUiTest(testTimeout = 5.minutes) {
        val test = testLab(RuntimeConfig.platformDefault())
        val lab = test.lab
        setContent { SampleApp(lab) }

        // The perf lab: the first messages' pages go live and are snapshotted.
        waitUntil("pages live and snapshotted", WAIT_MILLIS) { ui { lab.sample() }.let { it.live >= 2 && it.snapshots >= 2 } }
        waitForIdle()
        onNodeWithText(LabRun.Reading.label).assertExists()
        onNodeWithTag(SampleTags.demo(0)).assertExists()
        onNodeWithTag(SampleTags.StatsEngines).assertTextContains("live", substring = true)
        val lazy = saveScreen("perf-lab-lazycolumn")
        assertTrue(share(lazy, page) > 0.1, "the live pages hardly show: ${share(lazy, page)} of the window")

        // A fling, from its button: the run reports on the panel.
        onNodeWithTag(SampleTags.run(LabRun.Fling)).performClick()
        waitUntil("the fling reported", WAIT_MILLIS) { lab.lastReport?.name == LabRun.Fling.label }
        waitForIdle()
        onNodeWithTag(SampleTags.StatsRun).assertTextContains(LabRun.Fling.label, substring = true)

        // Every message composed at once.
        onNodeWithTag(SampleTags.layout(ListLayout.Column)).performClick()
        waitUntil("the column's pages live", WAIT_MILLIS) { ui { lab.sample() }.let { it.live >= 2 && it.coverCount >= 1 } }
        waitForIdle()
        val column = saveScreen("perf-lab-column")
        assertTrue(share(column, page) > 0.1, "the column's pages hardly show")

        // The baseline: still images where the web views were, and no engine leased.
        onNodeWithTag(SampleTags.ContentImages).performClick()
        waitUntil("the engines given back", WAIT_MILLIS) { ui { lab.sample().live } == 0 }
        waitForIdle()
        saveScreen("perf-lab-images")
        onNodeWithTag(SampleTags.ContentWeb).performClick()

        // The overlay demo: a page under a menu and a dialog, which the page's engine is told to cut out.
        onNodeWithTag(SampleTags.screen(Screen.Overlays)).performClick()
        waitUntil("the page live", WAIT_MILLIS) { centerColor(SampleTags.OverlayPage) == page }
        fun holes() = ui { test.fakes.created.filter { !it.released }.sumOf { it.cutouts?.holes?.size ?: 0 } }
        fun scrims() = ui { test.fakes.created.filter { !it.released }.sumOf { it.cutouts?.scrims?.size ?: 0 } }
        assertEquals(0, holes())
        onNodeWithTag(SampleTags.OpenMenu).performClick()
        waitUntil("the menu cut out of the page", WAIT_MILLIS) { holes() >= 1 }
        waitForIdle()
        saveScreen("overlays-menu")
        onNodeWithText(MENU_ITEMS.first()).performClick()
        waitUntil("the menu's hole closed", WAIT_MILLIS) { holes() == 0 }
        onNodeWithTag(SampleTags.OpenDialog).performClick()
        waitUntil("the dialog cut out and the page dimmed", WAIT_MILLIS) { holes() >= 1 && scrims() >= 1 }
        waitForIdle()
        saveScreen("overlays-dialog")
        onNodeWithTag(SampleTags.CloseDialog).performClick()
        waitUntil("the dialog's hole and scrim gone", WAIT_MILLIS) { holes() == 0 && scrims() == 0 }
        assertClean(test)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.centerColor(tag: String) =
        onNodeWithTag(tag).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
}
