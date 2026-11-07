package dev.supermux.webview.runtime

import dev.supermux.webview.engine.ios.IosWebEngineFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformIosTest {
    @Test fun iosMakesWkWebViewEngines() {
        assertTrue(platformWebEngineFactory() is IosWebEngineFactory, "iOS makes WKWebView engines")
    }

    /** 32 MB of stored snapshots, and 24 MB of decoded ones ready to draw. */
    @Test fun anIphoneKeepsFourLiveEnginesTwoSparesAnd32MbOfSnapshots() {
        assertEquals(
            RuntimeConfig(maxLive = 4, maxIdle = 2, snapshotCacheBytes = 32L shl 20, decodedSnapshotBytes = 24L shl 20),
            RuntimeConfig.platformDefault(),
        )
    }
}
