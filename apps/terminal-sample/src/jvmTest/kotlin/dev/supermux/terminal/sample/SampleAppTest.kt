package dev.supermux.terminal.sample

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sample app, driven through Compose's own test harness against a real engine.
 *
 * It exists because "the sample works" is otherwise a claim nobody can check without a screen: the
 * four controls the task asks for (reset / hide / show / dispose) each change something the UI is
 * supposed to keep on telling the truth about, and a sample whose *dispose* button leaves a session
 * open would be worse than no sample.
 *
 * The frame-time probe is turned OFF for these tests. It re-arms `withFrameNanos` in a loop, so the
 * composition never goes idle and every `performClick` — which waits for idle — would hang. That is
 * not a bug in the probe; it is what a continuous-rendering benchmark does, and it is why the probe
 * is a toggle.
 */
class SampleAppTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stopSessions() {
        scope.cancel()
    }

    private fun controller(count: Int = 1): SampleController =
        SampleController(scope, count = count).also { it.measuring = false }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSampleShowsAFixtureRunningAndCountsTheBytesItFed() = runComposeUiTest {
        val controller = controller()
        setContent { SampleTheme { SampleApp(controller) } }

        // The session opens, the producer feeds, and the panel's own counter moves. Reading the
        // counter through the DIAGNOSTICS rather than the screen is deliberate: it is the number
        // the task asks the sample to show, so it is the number the test should check.
        waitUntil(timeoutMillis = TIMEOUT) { controller.diagnostics.snapshot().outputBytes > 0 }
        waitUntil(timeoutMillis = TIMEOUT) { controller.diagnostics.snapshot().framesPublished > 0 }

        val snapshot = controller.diagnostics.snapshot()
        assertTrue(snapshot.columns > 1 && snapshot.rows > 1, "no grid: ${snapshot.columns}x${snapshot.rows}")
        assertEquals(1, snapshot.sessionsOpened.toInt())
        // The queue cap is the session's, copied in when it opened — the panel shows a bound, not
        // a guess.
        assertTrue(snapshot.outputQueueCapBytes > 0, "the session's caps never reached the panel")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theDiagnosticsPanelIsSampleOnlyAndSaysSo() = runComposeUiTest {
        val controller = controller()
        setContent { SampleTheme { SampleApp(controller) } }
        // The banner is the contract with a reader: nothing under it exists in :terminal-compose.
        onNodeWithText("SAMPLE-ONLY DIAGNOSTICS — none of this exists in :terminal-compose").assertExists()
        onNodeWithText("panel off").performClick()
        onNodeWithText("SAMPLE-ONLY DIAGNOSTICS — none of this exists in :terminal-compose").assertDoesNotExist()
        onNodeWithText("panel on").performClick()
        onNodeWithText("SAMPLE-ONLY DIAGNOSTICS — none of this exists in :terminal-compose").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hidingTheSurfaceLeavesTheSessionRunningAndShowingItBringsItBack() = runComposeUiTest {
        val controller = controller()
        setContent { SampleTheme { SampleApp(controller) } }
        waitUntil(timeoutMillis = TIMEOUT) { controller.diagnostics.snapshot().outputBytes > 0 }

        onNodeWithText("hide").performClick()
        waitUntil(timeoutMillis = TIMEOUT) { controller.terminals.first().mounted.not() }
        onNodeWithText("terminal 1 is hidden — the session keeps parsing, the surface is gone")
            .assertExists()

        // THE point of `hide`: the surface is gone and the terminal keeps going.
        val fedWhileHidden = controller.diagnostics.snapshot().outputBytes
        waitUntil(timeoutMillis = TIMEOUT) {
            controller.diagnostics.snapshot().outputBytes > fedWhileHidden
        }

        onNodeWithText("show").performClick()
        waitUntil(timeoutMillis = TIMEOUT) { controller.terminals.first().mounted }
        // The remounted surface has no rows to patch, so it asks for a full frame.
        val full = controller.diagnostics.snapshot().framesFull
        waitUntil(timeoutMillis = TIMEOUT) { controller.diagnostics.snapshot().framesFull >= full }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun resetClearsTheMeasurementsAndKeepsTheLifecycleTotals() = runComposeUiTest {
        val controller = controller()
        setContent { SampleTheme { SampleApp(controller) } }
        waitUntil(timeoutMillis = TIMEOUT) { controller.diagnostics.snapshot().outputBytes > 4096 }

        onNodeWithText("reset").performClick()
        waitUntil(timeoutMillis = TIMEOUT) { controller.diagnostics.snapshot().resets == 1L }

        val after = controller.diagnostics.snapshot()
        // A leak is invisible if "how many sessions has this process opened" is zeroed too.
        assertEquals(1, after.sessionsOpened.toInt(), "reset must not forget the lifecycle totals")
        assertEquals(1, after.resets.toInt())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun disposeClosesTheSessionAndOpenMakesANewOne() = runComposeUiTest {
        val controller = controller()
        setContent { SampleTheme { SampleApp(controller) } }
        waitUntil(timeoutMillis = TIMEOUT) { controller.terminals.first().session != null }

        onNodeWithText("dispose").performClick()
        waitUntil(timeoutMillis = TIMEOUT) { controller.terminals.first().session == null }
        waitUntil(timeoutMillis = TIMEOUT) { controller.diagnostics.snapshot().sessionsDisposed == 1L }
        onNodeWithText("disposed").assertExists()

        onNodeWithText("open").performClick()
        waitUntil(timeoutMillis = TIMEOUT) { controller.terminals.first().session != null }
        assertEquals(2, controller.diagnostics.snapshot().sessionsOpened.toInt())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun everyFixtureRunsAgainstTheRealEngine() = runComposeUiTest {
        val controller = controller()
        setContent { SampleTheme { SampleApp(controller) } }
        waitUntil(timeoutMillis = TIMEOUT) { controller.terminals.first().session != null }

        for (fixture in SampleFixture.entries) {
            onNodeWithText(fixture.title).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { controller.terminals.first().fixture == fixture }
            val before = controller.diagnostics.snapshot().outputBytes
            waitUntil(timeoutMillis = TIMEOUT) {
                controller.diagnostics.snapshot().outputBytes > before
            }
            // The engine never reported an unrecoverable failure on any of them.
            assertEquals(
                null,
                controller.terminals.first().failure,
                "${fixture.id} killed the session",
            )
        }
        // The alternate-screen fixture really does put the terminal on the alternate screen, which
        // is what makes it the input-routing case.
        onNodeWithText(SampleFixture.ALT_SCREEN.title).performClick()
        waitUntil(timeoutMillis = TIMEOUT) {
            controller.terminals.first().session?.viewports?.value?.modes?.alternateScreen == true
        }
        assertTrue(
            controller.terminals.first().session!!.viewports.value.modes.mouseTracking,
            "the alternate-screen fixture did not enable mouse tracking",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fourTerminalsRunOnOneProcessWithExactlyOneVisible() = runComposeUiTest {
        val controller = controller(count = 4)
        setContent { SampleTheme { SampleApp(controller) } }
        waitUntil(timeoutMillis = TIMEOUT) {
            controller.terminals.all { it.session != null }
        }
        assertEquals(4, controller.terminals.size)
        assertEquals(1, controller.terminals.count { it.active }, "more than one surface is active")
        assertEquals(0, controller.visibleIndex)

        onNodeWithText("#3").performClick()
        waitUntil(timeoutMillis = TIMEOUT) { controller.visibleIndex == 2 }
        assertEquals(1, controller.terminals.count { it.active })
        assertTrue(controller.terminals[2].active, "#3 is not the active one")
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}
