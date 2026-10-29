package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformDefaultsJvmTest {
    /** 128 MB of stored snapshots, and 64 MB of decoded ones ready to draw (sharp snapshots, Task 2). */
    @Test fun theDesktopKeepsEightLiveEnginesThreeSparesAnd128MbOfSnapshots() {
        assertEquals(
            RuntimeConfig(
                maxLive = 8,
                maxIdle = 3,
                snapshotCacheBytes = 128L shl 20,
                decodedSnapshotBytes = 64L shl 20,
            ),
            RuntimeConfig.platformDefault(),
        )
    }

    @Test fun theDesktopHasNoEngineYet() = runTest {
        assertEquals(
            WebEngineResult.Unavailable(UnavailableReason.NotImplemented),
            platformEngineFactory().create(EngineConfig(WebProfile.Default)),
        )
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
