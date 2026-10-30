package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.linux.LinuxWebEngineFactory
import dev.supermux.webview.engine.mac.MacWebEngineFactory
import dev.supermux.webview.testing.MacEngineTesting
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Which engine and pool each desktop gets (D12), and the test driver's window copies. */
class DesktopPlatformTest {
    private val config = EngineConfig(WebProfile.Default)

    @Test fun windowsHasNoEngineYet() = runTest {
        assertEquals(
            WebEngineResult.Unavailable(UnavailableReason.NotImplemented),
            desktopEngineFactory("Windows 11", "10.0").create(config),
        )
    }

    @Test fun linuxGetsTheWpeEngine() {
        assertIs<LinuxWebEngineFactory>(desktopEngineFactory("Linux", "6.8.0"))
    }

    /** Every WPE engine is a web process of its own, so Linux keeps fewer; macOS and Windows keep the desktop's. */
    @Test fun eachDesktopGetsItsOwnPool() {
        assertEquals(RuntimeConfig(maxLive = 6, maxIdle = 2, snapshotCacheBytes = 128L shl 20, decodedSnapshotBytes = 64L shl 20), desktopRuntimeConfig("Linux"))
        assertEquals(RuntimeConfig(maxLive = 8, maxIdle = 3, snapshotCacheBytes = 128L shl 20, decodedSnapshotBytes = 64L shl 20), desktopRuntimeConfig("Mac OS X"))
        assertEquals(RuntimeConfig(maxLive = 8, maxIdle = 3, snapshotCacheBytes = 128L shl 20, decodedSnapshotBytes = 64L shl 20), desktopRuntimeConfig("Windows 11"))
    }

    @Test fun macOsBefore14IsToldWhy() = runTest {
        assertEquals(
            WebEngineResult.Unavailable(UnavailableReason.NotImplemented, "the macOS engine needs macOS 14 or later"),
            desktopEngineFactory("Mac OS X", "13.6.1").create(config),
        )
    }

    @Test fun macOs14AndLaterGetTheMacEngine() {
        assertIs<MacWebEngineFactory>(desktopEngineFactory("Mac OS X", "14.0"))
        assertIs<MacWebEngineFactory>(desktopEngineFactory("Mac OS X", "26.6.2"))
    }

    @Test fun aWindowCopyCarriesItsSizeFirst() {
        val copied = MacEngineTesting.copiedOf(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 1, 1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(2, copied!!.width)
        assertEquals(1, copied.height)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), copied.rgba)
        assertNull(MacEngineTesting.copiedOf(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 2, 1, 2, 3, 4)), "too short for 2 x 2")
    }
}
