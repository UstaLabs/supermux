package dev.supermux.webview.runtime

import dev.supermux.webview.engine.mac.MacPlatform
import dev.supermux.webview.engine.mac.MacWebEngineFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformDefaultsJvmTest {
    /**
     * The machine running the tests gets its own OS's pool: the values per OS are pinned in [DesktopPlatformTest]
     * (8 live and 3 spares on a Mac, WPE's 6 and 2 on Linux, Windows' own), with 128 MB of stored snapshots and 64 MB
     * of decoded ones on every desktop.
     */
    @Test fun theDesktopDefaultIsThisOsesPool() {
        assertEquals(desktopRuntimeConfig(System.getProperty("os.name").orEmpty()), RuntimeConfig.platformDefault())
        assertEquals(128L shl 20, RuntimeConfig.platformDefault().snapshotCacheBytes)
        assertEquals(64L shl 20, RuntimeConfig.platformDefault().decodedSnapshotBytes)
    }

    /** On a Mac with macOS 14 or later the desktop's factory is the macOS engine's; never elsewhere (D12). */
    @Test fun theDesktopsFactoryIsTheMacEngineOnARecentMac() {
        val expected = MacPlatform.isMac && MacPlatform.majorVersion >= MacPlatform.MIN_MAJOR
        assertEquals(expected, platformEngineFactory() is MacWebEngineFactory)
        assertEquals(expected, platformWebEngineFactory() is MacWebEngineFactory)
    }

    @Test fun theDefaultRuntimeIsSizedForThePlatform() {
        assertEquals(RuntimeConfig.platformDefault(), WebViewRuntime.Default.config)
    }

    /** The library's own jvmMain dependency on kotlinx-coroutines-swing gives desktop apps a `Dispatchers.Main`. */
    @Test fun theDefaultRuntimeWorksOnTheSwingThread() = runBlocking {
        val onSwingThread = withTimeout(10_000) {
            WebViewRuntime.Default.scope.async { SwingUtilities.isEventDispatchThread() }.await()
        }
        assertTrue(onSwingThread)
    }
}
