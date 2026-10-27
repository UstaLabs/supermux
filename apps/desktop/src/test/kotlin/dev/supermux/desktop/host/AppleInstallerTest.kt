package dev.supermux.desktop.host

import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [AppleInstaller] only ever ACTIVATES Apple's installer once it runs; it never starts one. */
class AppleInstallerTest {
    private val running = AppleInstaller.runningArgv
    private val front = AppleInstaller.frontArgv
    private val notYet = OsEnv.RunResult(1, "", "")
    private val up = OsEnv.RunResult(0, "2042\n", "")

    @Test fun waitsForTheInstallerThenActivatesItOnce() = runBlocking {
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = createTempDirectory(), scripted = mapOf(running to listOf(notYet, notYet, up)))
        val pauses = mutableListOf<Long>()
        assertTrue(AppleInstaller.bringToFront(env, pause = { pauses += it }))
        assertEquals(listOf(running, running, running, front), env.ran)
        assertEquals(3, pauses.size)
        assertEquals(listOf("/usr/bin/open", "-a", "/System/Library/CoreServices/Install Command Line Developer Tools.app"), front)
    }

    @Test fun neverOpensTheInstallerWhenItIsNotRunning() = runBlocking {
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = createTempDirectory(), failing = setOf(running))
        assertFalse(AppleInstaller.bringToFront(env, attempts = 4, pause = {}))
        assertEquals(List(4) { running }, env.ran, "`open -a` would LAUNCH it: only ever run once it is up")
    }

    @Test fun doesNothingOffAMac() = runBlocking {
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = createTempDirectory())
        assertFalse(AppleInstaller.bringToFront(env, pause = {}))
        assertTrue(env.ran.isEmpty())
    }
}
