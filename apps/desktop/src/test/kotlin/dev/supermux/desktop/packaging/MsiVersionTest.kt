package dev.supermux.desktop.packaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MsiVersionTest {
    @Test fun mapsReleasesAndPrereleases() {
        assertEquals("1.5.999", MsiVersion.of("1.5.0"))
        assertEquals("1.5.2999", MsiVersion.of("1.5.2"))
        assertEquals("0.12.3", MsiVersion.of("0.12.0-alpha.3"))
        assertEquals("0.12.302", MsiVersion.of("0.12.0-beta.2"))
        assertEquals("0.12.601", MsiVersion.of("0.12.0-rc.1"))
        assertEquals("1.99.18", MsiVersion.of("1.99.0-test.18"))
        assertEquals("0.0.0", MsiVersion.of("0.0.0-dryrun"))
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

    @Test fun refusesWhatMsiCannotHold() {
        assertFailsWith<IllegalArgumentException> { MsiVersion.of("256.0.0") }
        assertFailsWith<IllegalArgumentException> { MsiVersion.of("1.0.66") }
        assertFailsWith<IllegalArgumentException> { MsiVersion.of("1.0.0-alpha.300") }
        assertFailsWith<IllegalArgumentException> { MsiVersion.of("v1.0") }
    }
}
