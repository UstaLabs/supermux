package dev.supermux.desktop.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #132: a covered window's decorative loops stand still; only looks read this, never push muting. */
@OptIn(ExperimentalTestApi::class)
class WindowCoveredTest {
    @Test fun only_a_window_with_nothing_visible_counts_as_covered() {
        assertTrue(occlusionCovers(0L))
        assertFalse(occlusionCovers(1L shl 1))
        assertFalse(occlusionCovers((1L shl 1) or 1L))
    }

    private class FakeProbe(var answer: Boolean?) : CoverProbe {
        var asked = 0
        override suspend fun covered(handle: Long): Boolean? { asked++; return answer }
    }

    @Test fun a_watched_window_follows_the_probe() = runComposeUiTest {
        val probe = FakeProbe(answer = true)
        var covered = false
        setContent { covered = rememberWindowCovered({ 7L }, watch = true, probe = probe).value }
        waitForIdle()
        assertTrue(covered)
        probe.answer = false
        mainClock.advanceTimeBy(1_100)
        waitForIdle()
        assertFalse(covered)
    }

    @Test fun an_unwatched_window_is_never_covered_and_never_asked() = runComposeUiTest {
        val probe = FakeProbe(answer = true)
        var watch by mutableStateOf(false)
        var covered = true
        setContent { covered = rememberWindowCovered({ 7L }, watch = watch, probe = probe).value }
        mainClock.advanceTimeBy(3_000)
        assertFalse(covered)
        assertEquals(0, probe.asked)
        watch = true
        waitForIdle()
        assertTrue(covered)
    }

    @Test fun an_unknown_answer_counts_as_seen() = runComposeUiTest {
        var covered = true
        setContent { covered = rememberWindowCovered({ 7L }, watch = true, probe = FakeProbe(null)).value }
        waitForIdle()
        assertFalse(covered)
    }
}
