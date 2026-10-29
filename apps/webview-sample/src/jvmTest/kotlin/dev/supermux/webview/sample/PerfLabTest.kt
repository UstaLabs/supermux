package dev.supermux.webview.sample

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.supermux.webview.runtime.RuntimeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The perf lab under scripted scrolling (spec §10, §11), on the fake engine: 200 messages, each embedding a web view
 * (agent demos as Html, web pages as Url, plugins as Bundle), read a screen at a time or scrolled without stopping.
 * Every frame is checked: live engines within the budget, engines in existence within maxLive + maxIdle, covers within
 * a screenful, the cache within its bound. Each test ends with every fake engine's contract held.
 *
 * The 1,024 × 768 test window shows about three messages at once, so the tight budgets below keep the pool contended.
 * Each test prints its numbers as a `perf-lab-test` line, and the lab prints its own `perf-lab` line per run.
 */
@OptIn(ExperimentalTestApi::class)
class PerfLabTest {
    /** Fewer live engines than messages on screen. */
    private val tight = RuntimeConfig(maxLive = 2, maxIdle = 3, snapshotCacheBytes = 32L shl 20)

    /** A phone's sizes (spec §4). */
    private val phone = RuntimeConfig(maxLive = 4, maxIdle = 3, snapshotCacheBytes = 32L shl 20)

    @Test fun readingALazyListOfTwoHundredDemosKeepsEveryBudget() = runComposeUiTest(testTimeout = 10.minutes) {
        val test = testLab(tight)
        val lab = test.lab
        setContent { SampleTheme { PerfLab(lab) } }
        assertEquals(DEFAULT_VIEWS, lab.views)
        assertEquals(ListLayout.Lazy, lab.layout)
        val guard = Guard(lab, coverLimits())
        val costs = drive(lab, LabRun.Reading, guard)
        val report = assertNotNull(lab.lastReport)
        println("perf-lab-test: ${report.summary()}; test harness cost per moving frame ${costs.costs()}; pages started while moving ${guard.loadsWhileMoving}")
        assertEquals(LabRun.Reading.label, report.name)
        assertEquals(tight.maxLive, guard.peakLive, "the screens wanted more engines than the budget")
        assertTrue(report.loads >= lab.views / 4, "only ${report.loads} pages went live while reading ${lab.views} messages")
        assertTrue(report.snapshots > 0 && report.pages > 0, "nothing was snapshotted or remembered")
        // The list recreates every message it scrolls back to: page memory brings their snapshots back as covers.
        assertTrue(guard.peakCoverCountGoingUp > 0, "no message scrolled back to showed its snapshot")
        assertTrue(report.peakEngines <= tight.maxLive + tight.maxIdle)
        // Nothing lost: once the list rests, every engine is live or a spare.
        waitUntil("every engine live or a spare", WAIT_MILLIS) { ui { lab.sample().let { it.engines == it.live + it.spares } } }
        assertClean(test)
    }

    @Test fun readingAColumnOfTwoHundredComposedDemosKeepsEveryBudget() = runComposeUiTest(testTimeout = 10.minutes) {
        // Hidden views keep their engine for the grace, so even three live engines are contended.
        val config = RuntimeConfig(maxLive = 3, maxIdle = 3, snapshotCacheBytes = 32L shl 20)
        val test = testLab(config)
        val lab = test.lab
        lab.layout = ListLayout.Column
        setContent { SampleTheme { PerfLab(lab) } }
        waitForIdle()
        val demos = SemanticsMatcher("a demo") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("demo-") == true }
        assertEquals(lab.views, onAllNodes(demos).fetchSemanticsNodes().size, "every message is composed at once")
        val guard = Guard(lab, coverLimits())
        val costs = drive(lab, LabRun.Reading, guard)
        val report = assertNotNull(lab.lastReport)
        println("perf-lab-test: ${report.summary()}; test harness cost per moving frame ${costs.costs()}; pages started while moving ${guard.loadsWhileMoving}")
        assertEquals(config.maxLive, guard.peakLive, "views on screen evicted hidden ones up to the budget")
        assertTrue(report.loads >= lab.views / 4, "only ${report.loads} pages went live while reading ${lab.views} messages")
        assertTrue(guard.peakCoverCountGoingUp > 0, "no message scrolled back to showed its snapshot")
        waitUntil("every engine live or a spare", WAIT_MILLIS) { ui { lab.sample().let { it.engines == it.live + it.spares } } }
        assertClean(test)
    }

    /** Spec §4: a cold view stays a snapshot until the list has been still for its settle time. */
    @Test fun aContinuousScrollStartsNoPageUntilItStops() = runComposeUiTest(testTimeout = 10.minutes) {
        val test = testLab(phone)
        val lab = test.lab
        setContent { SampleTheme { PerfLab(lab) } }
        val guard = Guard(lab, coverLimits())
        val webCosts = drive(lab, LabRun.Scroll, guard)
        val web = assertNotNull(lab.lastReport)
        assertTrue(guard.peakLive >= 1, "the first screen never went live")
        assertEquals(0, guard.loadsWhileMoving, "pages started while the list moved")
        // The same run over still images: the baseline the spec measures the web views against.
        onNodeWithTag(SampleTags.ContentImages).performClick()
        waitUntil("the web views' engines given back", WAIT_MILLIS) { ui { lab.sample().live } == 0 }
        val imageCosts = drive(lab, LabRun.Scroll, Guard(lab, coverLimits()))
        val images = assertNotNull(lab.lastReport)
        assertEquals(0, images.loads, "images start no pages")
        println("perf-lab-test: ${web.summary()}; test harness cost per moving frame ${webCosts.costs()}")
        println("perf-lab-test: ${images.summary()}; test harness cost per moving frame ${imageCosts.costs()}")
        assertClean(test)
    }

    @Test fun memoryPressureHalvesTheBudgetAndTheSnapshotCacheUntilRecovery() = runComposeUiTest(testTimeout = 5.minutes) {
        val test = testLab(tight)
        val lab = test.lab
        setContent { SampleTheme { PerfLab(lab) } }
        waitUntil("the first screen live and snapshotted", WAIT_MILLIS) { ui { lab.sample() }.let { it.live == 2 && it.cacheCount >= 2 } }
        val before = ui { lab.sample() }
        onNodeWithTag(SampleTags.Pressure).performClick()
        // Half the budget, no spares, and no engine left but the live one: the evicted one is destroyed, not kept.
        waitUntil("down to half the budget", WAIT_MILLIS) {
            ui { lab.sample() }.let { it.budget == 1 && it.live <= 1 && it.spares == 0 && it.engines == it.live }
        }
        // The view the pool evicted snapshotted its page into the cache on the way out (spec §4), and the cache is still
        // at most half of what it held.
        val during = ui { lab.sample() }
        assertTrue(during.cacheBytes <= before.cacheBytes / 2, "the snapshot cache went from ${before.cacheBytes} to ${during.cacheBytes} bytes")
        // The evicted view asks again, and the window keeps it out, and the cache at half.
        mainClock.advanceTimeBy(5_000)
        val later = ui { lab.sample() }
        assertEquals(1, later.budget)
        assertTrue(later.live <= 1 && later.spares == 0, "${later.live} live and ${later.spares} spares during the window")
        assertTrue(later.cacheBytes <= before.cacheBytes / 2, "the snapshot cache grew back to ${later.cacheBytes} of ${before.cacheBytes} bytes")
        onNodeWithTag(SampleTags.StatsEngines).assertTextContains("memory pressure", substring = true)
        onNodeWithTag(SampleTags.Recovered).performClick()
        waitUntil("back to the full budget", WAIT_MILLIS) { ui { lab.sample() }.let { it.budget == 2 && it.live == 2 } }
        mainClock.advanceTimeBy(1_000)
        val after = ui { lab.sample() }
        assertFalse("memory pressure" in after.enginesLine())
        println(
            "perf-lab-test: memory pressure: live ${before.live} -> ${during.live}, spares ${before.spares} -> ${during.spares}, " +
                "engines in existence ${before.engines} -> ${during.engines}, snapshot cache ${size(before.cacheBytes)} " +
                "(${before.cacheCount}) -> ${size(during.cacheBytes)} (${during.cacheCount}), 5 s later " +
                "${size(later.cacheBytes)} (${later.cacheCount}); after recovery live ${after.live}, snapshot cache " +
                "${size(after.cacheBytes)} (${after.cacheCount})",
        )
        assertClean(test)
    }
}
