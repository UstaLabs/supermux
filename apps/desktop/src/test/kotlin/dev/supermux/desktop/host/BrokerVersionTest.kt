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
}
