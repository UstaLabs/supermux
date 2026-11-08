package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.linux.LinuxWebEngineFactory
import dev.supermux.webview.engine.mac.MacWebEngineFactory
import dev.supermux.webview.engine.windows.WindowsWebEngineFactory
import kotlinx.coroutines.test.runTest
import org.jetbrains.skiko.OS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Which engine and pool each desktop gets (D12). The test driver's window copies are tested with it, in the testing module. */
class DesktopPlatformTest {
    private val config = EngineConfig(WebProfile.Default)

    @Test fun windowsGetsTheWebView2Engine() {
        assertIs<WindowsWebEngineFactory>(desktopEngineFactory(OS.Windows, "10.0"))
    }

    @Test fun linuxGetsTheWpeEngine() {
        assertIs<LinuxWebEngineFactory>(desktopEngineFactory(OS.Linux, "6.8.0"))
    }

    /** Every WPE engine is a web process of its own, so Linux keeps fewer; macOS and Windows keep the desktop's. */
    @Test fun eachDesktopGetsItsOwnPool() {
        assertEquals(RuntimeConfig(maxLive = 6, maxIdle = 2, snapshotCacheBytes = 128L shl 20, decodedSnapshotBytes = 64L shl 20), desktopRuntimeConfig(OS.Linux))
        assertEquals(RuntimeConfig(maxLive = 8, maxIdle = 3, snapshotCacheBytes = 128L shl 20, decodedSnapshotBytes = 64L shl 20), desktopRuntimeConfig(OS.MacOS))
        assertEquals(RuntimeConfig(maxLive = 8, maxIdle = 3, snapshotCacheBytes = 128L shl 20, decodedSnapshotBytes = 64L shl 20), desktopRuntimeConfig(OS.Windows))
    }

    @Test fun macOsBefore14IsToldWhy() = runTest {
        assertEquals(
            WebEngineResult.Unavailable(UnavailableReason.NotImplemented, "the macOS engine needs macOS 14 or later"),
            desktopEngineFactory(OS.MacOS, "13.6.1").create(config),
        )
    }

    @Test fun macOs14AndLaterGetTheMacEngine() {
        assertIs<MacWebEngineFactory>(desktopEngineFactory(OS.MacOS, "14.0"))
        assertIs<MacWebEngineFactory>(desktopEngineFactory(OS.MacOS, "26.6.2"))
    }

    /** A desktop Skiko doesn't know (it throws there): no engine, and the desktop's pool, never a failure at start. */
    @Test fun aDesktopSkikoDoesntKnowGetsNoEngine() = runTest {
        assertEquals(WebEngineResult.Unavailable(UnavailableReason.NotImplemented), desktopEngineFactory(null, "").create(config))
        assertEquals(RuntimeConfig(maxLive = 8, maxIdle = 3, snapshotCacheBytes = 128L shl 20, decodedSnapshotBytes = 64L shl 20), desktopRuntimeConfig(null))
    }
}
