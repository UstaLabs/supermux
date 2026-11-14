package dev.supermux.desktop.packaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleVersionTest {
    @Test fun macShowsTheNumericRelease() {
        assertEquals("0.18.2", BundleVersion.macShortVersion("0.18.2"))
        assertEquals("0.18.3", BundleVersion.macShortVersion("0.18.3-rc.1"))
        assertEquals("0.0.0", BundleVersion.macShortVersion("0.0.0-dryrun"))
        assertEquals("1.2.3", BundleVersion.macShortVersion("1.2.3+build.7"))
        assertNull(BundleVersion.macShortVersion("dev"))
        assertNull(BundleVersion.macShortVersion(""))
        assertNull(BundleVersion.macShortVersion(null))
    }

    @Test fun macBuildVersionIsTheMsiMappingAndAboveTheOld1_0_0() {
        assertEquals("1.18.299", BundleVersion.macBuildVersion("0.18.2"))
        assertEquals("1.18.361", BundleVersion.macBuildVersion("0.18.3-rc.1"))
        assertNull(BundleVersion.macBuildVersion("dev"))
        for (v in listOf("0.18.2", "0.18.3-rc.1", "0.12.0-alpha.1")) {
            val parts = BundleVersion.macBuildVersion(v)!!.split('.').map { it.toInt() }
            assertTrue(parts[0] >= 1, "jpackage refuses a CFBundleVersion below 1: $v")
            assertTrue(compareValues(parts[0], 1) > 0 || parts[1] > 0 || parts[2] > 0, "$v must be above 1.0.0")
        }
    }

    @Test fun debHasEpochAndTildePrereleases() {
        assertEquals("1:0.18.2", BundleVersion.debVersion("0.18.2"))
        assertEquals("1:0.18.3~rc.1", BundleVersion.debVersion("0.18.3-rc.1"))
        assertEquals("1:0.0.0~dryrun", BundleVersion.debVersion("0.0.0-dryrun"))
        assertEquals("1:0.12.0~alpha.x.1", BundleVersion.debVersion("0.12.0-alpha-x.1"))
        assertNull(BundleVersion.debVersion("dev"))
    }

    // dpkg's own comparison where dpkg exists (the Linux lanes; the .deb is only built there).
    @Test fun debVersionsUpgradeInOrderAndOverTheOld1_0_0() {
        val dpkg = listOf("/usr/bin/dpkg", "/bin/dpkg").firstOrNull { java.io.File(it).canExecute() } ?: return
        val train = listOf("1.0.0-1") + listOf("0.18.2", "0.18.3-alpha.1", "0.18.3-rc.1", "0.18.3-rc.2", "0.18.3", "0.19.0")
            .map { BundleVersion.debVersion(it)!! + "-1" }
        for ((a, b) in train.zipWithNext()) {
            val lt = ProcessBuilder(dpkg, "--compare-versions", a, "lt", b).start().waitFor()
            assertEquals(0, lt, "dpkg must order $a below $b")
        }
    }

    @Test fun controlKeepsItsRevision() {
        val control = "Package: supermux\nVersion: 1.0.0-1\nSection: utils\n"
        assertEquals("Package: supermux\nVersion: 1:0.18.2-1\nSection: utils\n", BundleVersion.withDebVersion(control, "1:0.18.2"))
        assertEquals("Version: 1:0.18.3~rc.1\n", BundleVersion.withDebVersion("Version: 1.0.0\n", "1:0.18.3~rc.1"))
        assertFailsWith<IllegalArgumentException> { BundleVersion.withDebVersion("Package: supermux\n", "1:0.18.2") }
    }

    @Test fun refusesWhatIsNotSemver() {
        assertFailsWith<IllegalArgumentException> { BundleVersion.debVersion("v0.18.2") }
        assertFailsWith<IllegalArgumentException> { BundleVersion.macShortVersion("0.18") }
    }
}
