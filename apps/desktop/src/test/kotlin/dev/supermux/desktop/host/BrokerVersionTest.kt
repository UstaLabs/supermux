package dev.supermux.desktop.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrokerVersionTest {
    @Test fun sameBuildIsExactStringEquality() {
        assertTrue(BrokerVersion.sameBuild("1.5.0 (abc)", "1.5.0 (abc)"))
        assertFalse(BrokerVersion.sameBuild("dev (abc)", "dev (def)"))
        assertFalse(BrokerVersion.sameBuild(null, "1.5.0 (abc)"))
    }

    @Test fun isNewerComparesSemverIncludingPrerelease() {
        assertTrue(BrokerVersion.isNewer(found = "1.6.0", bundled = "1.5.9"))
        assertTrue(BrokerVersion.isNewer(found = "1.5.0", bundled = "1.5.0-alpha.3"))
        assertTrue(BrokerVersion.isNewer(found = "1.5.0-alpha.4", bundled = "1.5.0-alpha.3"))
        assertFalse(BrokerVersion.isNewer(found = "1.5.0", bundled = "1.5.0"))
        assertFalse(BrokerVersion.isNewer(found = "1.4.0", bundled = "1.5.0"))
    }

    @Test fun devOrGarbageIsNeverNewer() {
        assertFalse(BrokerVersion.isNewer(found = "dev", bundled = "1.5.0"))
        assertFalse(BrokerVersion.isNewer(found = "1.6.0", bundled = "dev"))
        assertFalse(BrokerVersion.isNewer(found = null, bundled = "1.5.0"))
    }

    @Test fun versionOfBuildStripsTheCommit() {
        assertEquals("1.5.0-alpha.3", BrokerVersion.versionOf("1.5.0-alpha.3 (abc1234)"))
        assertNull(BrokerVersion.versionOf(null))
    }

    @Test fun alphanumericPrereleaseBeatsNumeric() =
        assertTrue(BrokerVersion.isNewer(found = "1.0.0-alpha.beta", bundled = "1.0.0-alpha.1"))
    @Test fun overflowIsNotNewerAndDoesNotThrow() =
        assertFalse(BrokerVersion.isNewer(found = "99999999999.0.0", bundled = "1.0.0"))

    @Test fun killProbesEndsARunningVersionProbe() {
        val script = slowExecutable(30)
        var result: String? = "unset"
        val t = Thread { result = BrokerVersion.readBundledBuild(script, killAfterMs = 60_000) }
        val started = System.currentTimeMillis()
        t.start()
        // RUNNING, not "half a second later": the probe is registered for killProbes() AND its
        // script has forked the `sleep` (`ping` on Windows). Kill any earlier and the test races the
        // machine: a probe not registered yet is not killed at all, and a `sleep` forked after the
        // kill snapshotted the tree is an orphan holding the pipe open for its full 30 s. Both read as
        // "killProbes does not work". Any descendant is not the signal: on Windows that is the
        // script's conhost.exe, which comes first.
        while (BrokerVersion.liveProbesForTest().none(::hasSlowChild)) {
            check(System.currentTimeMillis() - started < 10_000) { "the probe never started its child" }
            Thread.sleep(10)
        }
        BrokerVersion.killProbes()
        t.join(10_000)
        kotlin.test.assertFalse(t.isAlive, "the probe read is still blocked")
        kotlin.test.assertNull(result)
        kotlin.test.assertTrue(System.currentTimeMillis() - started < 10_000)
    }
}
