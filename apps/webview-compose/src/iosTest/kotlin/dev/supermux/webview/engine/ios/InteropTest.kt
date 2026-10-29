package dev.supermux.webview.engine.ios

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InteropTest {
    @Test fun bytesSurviveNSData() {
        val bytes = ByteArray(1000) { (it * 7).toByte() }
        assertContentEquals(bytes, bytes.toNSData().toByteArray())
        assertContentEquals(ByteArray(0), ByteArray(0).toNSData().toByteArray())
    }

    @Test fun aNamedProfileHasOneStableUuid() {
        val a = profileUuid("alice").UUIDString
        assertEquals(a, profileUuid("alice").UUIDString)
        assertNotEquals(a, profileUuid("bob").UUIDString)
        // Version 5 layout: the version nibble, and the RFC 4122 variant.
        assertEquals('5', a[14])
        assertTrue(a[19] in "89AB", a)
    }

    @Test fun theSimulatorRunsIos15OrLater() {
        assertTrue(osAtLeast(15))
        assertTrue(!osAtLeast(99))
    }
}
