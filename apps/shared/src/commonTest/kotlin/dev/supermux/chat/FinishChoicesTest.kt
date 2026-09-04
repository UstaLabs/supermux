package dev.supermux.chat

import dev.supermux.proto.FinishJobDto
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The PURE finish-policy cores ([canSkipTests], [isFinishUnacked], [finishDotIsError]) — shared by
 * Android's FinishSheet and desktop's FinishDialog, so both agree without a per-app copy.
 */
class FinishChoicesTest {
    @Test fun merge_can_always_skip() {
        assertTrue(canSkipTests("merge", false))
        assertTrue(canSkipTests("merge", true))
    }

    @Test fun pr_skips_only_when_not_requiring_green() {
        assertTrue(canSkipTests("pr", false))
        assertFalse(canSkipTests("pr", true))
    }

    @Test fun can_skip_tests_merge_always_skippable() {
        assertTrue(canSkipTests("merge", prRequiresGreen = false))
        assertTrue(canSkipTests("merge", prRequiresGreen = true))
    }

    @Test fun can_skip_tests_pr_skippable_unless_requires_green() {
        assertTrue(canSkipTests("pr", prRequiresGreen = false))
        assertFalse(canSkipTests("pr", prRequiresGreen = true))
    }

    @Test fun can_skip_tests_keep_and_discard_always_skippable() {
        assertTrue(canSkipTests("keep", prRequiresGreen = true))
        assertTrue(canSkipTests("discard", prRequiresGreen = true))
    }

    @Test fun is_finish_unacked_derivation() {
        // running → never unacked (even when not acked)
        assertFalse(isFinishUnacked(FinishJobDto(status = "running"), acked = false))
        // terminal + not-yet-acked → unacked
        assertTrue(isFinishUnacked(FinishJobDto(status = "failed"), acked = false))
        assertTrue(isFinishUnacked(FinishJobDto(status = "done"), acked = false))
        // terminal + acked → acked (dot hidden)
        assertFalse(isFinishUnacked(FinishJobDto(status = "done"), acked = true))
        // no job → not unacked
        assertFalse(isFinishUnacked(null, acked = false))
    }

    @Test fun finish_dot_is_error_only_for_failed() {
        assertTrue(finishDotIsError(FinishJobDto(status = "failed")))
        assertFalse(finishDotIsError(FinishJobDto(status = "done")))
        assertFalse(finishDotIsError(null))
    }
}
