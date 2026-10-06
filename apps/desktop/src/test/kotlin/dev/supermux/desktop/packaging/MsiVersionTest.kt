package dev.supermux.desktop.packaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MsiVersionTest {
    @Test fun mapsReleasesAndPrereleases() {
        assertEquals("2.5.99", MsiVersion.of("1.5.0"))
        assertEquals("2.5.299", MsiVersion.of("1.5.2"))
        assertEquals("1.11.3699", MsiVersion.of("0.11.36"))
        assertEquals("1.12.3", MsiVersion.of("0.12.0-alpha.3"))
        assertEquals("1.12.32", MsiVersion.of("0.12.0-beta.2"))
        assertEquals("1.12.61", MsiVersion.of("0.12.0-rc.1"))
        assertEquals("2.99.18", MsiVersion.of("1.99.0-test.18"))
        assertEquals("1.0.0", MsiVersion.of("0.0.0-dryrun"))
        assertEquals("0.0.1", MsiVersion.of("dev"))
    }

    private fun msiOrder(v: String): List<Int> = MsiVersion.of(v).split('.').map { it.toInt() }
    private val cmp = Comparator<List<Int>> { a, b -> (0..2).map { a[it].compareTo(b[it]) }.firstOrNull { it != 0 } ?: 0 }

    @Test fun isMonotonicAcrossTheReleaseTrain() {
        val train = listOf(
            "dev", "0.9.0", "0.12.0-alpha.1", "0.12.0-alpha.12", "0.12.0-beta.0", "0.12.0-beta.9",
            "0.12.0-rc.1", "0.12.0-rc.2", "0.12.0", "0.12.1-alpha.1", "0.12.1", "0.13.0-alpha.0", "1.0.0", "1.99.0-test.18",
            "1.99.0-test.19",
        )
        for ((a, b) in train.zipWithNext()) {
            assertTrue(cmp.compare(msiOrder(a), msiOrder(b)) < 0, "$a (${MsiVersion.of(a)}) must be below $b (${MsiVersion.of(b)})")
        }
    }

    @Test fun everyReleaseIsAboveTheOld1_0_0Msis() {
        // Every MSI before this said 1.0.0: a release must upgrade them, not be refused as older.
        for (v in listOf("0.11.36", "0.12.0-alpha.1", "0.12.0")) assertTrue(cmp.compare(msiOrder(v), listOf(1, 0, 0)) > 0, v)
    }

    @Test fun refusesWhatMsiCannotHold() {
        assertFailsWith<IllegalArgumentException> { MsiVersion.of("255.0.0") }
        assertFailsWith<IllegalArgumentException> { MsiVersion.of("1.0.656") }
        assertFailsWith<IllegalArgumentException> { MsiVersion.of("1.0.0-alpha.30") }
        assertFailsWith<IllegalArgumentException> { MsiVersion.of("v1.0") }
    }
}
