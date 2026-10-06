package dev.supermux.desktop.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsEnvTest {
    private val posix = SystemOsEnv.os == OsEnv.Os.MAC || SystemOsEnv.os == OsEnv.Os.LINUX

    @Test fun aHungCommandIsKilledAtItsTimeout() {
        if (!posix) return
        val started = System.nanoTime()
        val r = SystemOsEnv.runResult(listOf("/bin/sh", "-c", "sleep 30 & sleep 30"), timeoutMs = 300)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(-1, r.exit)
        assertTrue("timed out" in r.err, r.err)
        assertTrue(ms < 5_000, "returned after $ms ms")
    }

    @Test fun theDefaultTimeoutIsConfigurable() {
        if (!posix) return
        val before = SystemOsEnv.defaultTimeoutMs
        try {
            SystemOsEnv.defaultTimeoutMs = 300
            assertNull(SystemOsEnv.runCapture(listOf("/bin/sleep", "30")))
            assertEquals(-1, SystemOsEnv.runResult(listOf("/bin/sleep", "30")).exit)
        } finally {
            SystemOsEnv.defaultTimeoutMs = before
        }
        assertEquals(SystemOsEnv.DEFAULT_TIMEOUT_MS, SystemOsEnv.defaultTimeoutMs)
    }

    @Test fun aQuickCommandsOutputAndExitAreKept() {
        if (!posix) return
        val r = SystemOsEnv.runResult(listOf("/bin/sh", "-c", "echo out; echo err >&2; exit 3"))
        assertEquals(OsEnv.RunResult(3, "out\n", "err\n"), r)
        assertEquals("hi\n", SystemOsEnv.runCapture(listOf("/bin/echo", "hi")))
    }
}
