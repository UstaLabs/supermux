package dev.supermux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #76: the ink edge and the gliding scroll step aside when the OS asks for less motion. */
class ReduceMotionTest {
    @Test fun macOsReadsTheUniversalAccessSetting() {
        assertTrue(reduceMotionFrom("Mac OS X", "1\n"))
        assertFalse(reduceMotionFrom("Mac OS X", "0\n"))
        // The key is absent until the user has touched the setting: `defaults` says so on stderr.
        assertFalse(reduceMotionFrom("Mac OS X", "The domain/default pair of (com.apple.universalaccess, reduceMotion) does not exist"))
    }

    @Test fun gnomeReadsEnableAnimations() {
        assertTrue(reduceMotionFrom("Linux", "false\n"))
        assertFalse(reduceMotionFrom("Linux", "true\n"))
    }

    @Test fun anythingElseOrNoAnswerIsFalse() {
        assertFalse(reduceMotionFrom("Windows 11", "1"))
        assertFalse(reduceMotionFrom("Mac OS X", null))
    }
}
