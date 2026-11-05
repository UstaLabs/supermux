package dev.supermux.android.theme

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #76: Android's "Remove animations" is the animator duration scale at 0. */
class ReduceMotionTest {
    @Test fun removeAnimationsIsAScaleOfZero() = assertTrue(reduceMotionFromAnimatorScale(0f))

    @Test fun anyOtherScaleKeepsTheMotion() {
        assertFalse(reduceMotionFromAnimatorScale(1f))
        assertFalse(reduceMotionFromAnimatorScale(0.5f))
        assertFalse(reduceMotionFromAnimatorScale(10f))
    }
}
