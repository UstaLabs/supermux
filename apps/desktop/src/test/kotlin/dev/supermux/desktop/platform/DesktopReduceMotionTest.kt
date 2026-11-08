package dev.supermux.desktop.platform

import dev.supermux.desktop.host.FakeOs
import dev.supermux.desktop.host.OsEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #76: the ink edge and the gliding scroll step aside when the OS asks for less motion. */
class DesktopReduceMotionTest {
    @Test fun macOsReadsTheUniversalAccessSetting() {
        assertTrue(reduceMotionFrom(DesktopOs.MACOS, "1\n"))
        assertFalse(reduceMotionFrom(DesktopOs.MACOS, "0\n"))
        // The key is absent until the user has touched the setting: `defaults` says so on stderr.
        assertFalse(reduceMotionFrom(DesktopOs.MACOS, "The domain/default pair of (com.apple.universalaccess, reduceMotion) does not exist"))
    }

    @Test fun gnomeReadsEnableAnimations() {
        assertTrue(reduceMotionFrom(DesktopOs.LINUX, "false\n"))
        assertFalse(reduceMotionFrom(DesktopOs.LINUX, "true\n"))
    }

    @Test fun anythingElseOrNoAnswerIsFalse() {
        assertFalse(reduceMotionFrom(DesktopOs.WINDOWS, "1"))
        assertFalse(reduceMotionFrom(DesktopOs.MACOS, null))
    }

    // The query runs through OsEnv, the desktop's one process runner, within 2 s; a query that fails is no answer.
    @Test fun theProbeAsksTheOsThroughOsEnvWithinTwoSeconds() {
        val asked = mutableListOf<Pair<List<String>, Long>>()
        fun env(exit: Int, out: String) = object : FakeOs(DesktopOs.MACOS) {
            override fun runResult(argv: List<String>, timeoutMs: Long): OsEnv.RunResult {
                asked += argv to timeoutMs
                return OsEnv.RunResult(exit, out, "")
            }
        }
        assertTrue(probeReduceMotion(DesktopOs.MACOS, env(0, "1\n")))
        assertEquals(listOf("defaults", "read", "com.apple.universalaccess", "reduceMotion") to 2_000L, asked.last())
        assertTrue(probeReduceMotion(DesktopOs.LINUX, env(0, "false\n")))
        assertEquals(listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"), asked.last().first)
        // `defaults` exits 1 while the key was never set: no answer, so motion stays on.
        assertFalse(probeReduceMotion(DesktopOs.MACOS, env(1, "")))
        val before = asked.size
        assertFalse(probeReduceMotion(DesktopOs.WINDOWS, env(0, "1")))
        assertEquals(before, asked.size, "nothing is run on Windows")
    }
}
