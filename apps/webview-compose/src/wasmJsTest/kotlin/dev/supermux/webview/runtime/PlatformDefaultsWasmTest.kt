package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformDefaultsWasmTest {
    @Test fun theWebKeepsTwelveLiveIframesThreeSparesAnd64MbOfSnapshots() {
        assertEquals(RuntimeConfig(maxLive = 12, maxIdle = 3, snapshotCacheBytes = 64L shl 20), RuntimeConfig.platformDefault())
    }

    @Test fun theWebHasNoEngineYet() = runTest {
        assertEquals(
            WebEngineResult.Unavailable(UnavailableReason.NotImplemented),
            platformEngineFactory().create(EngineConfig(WebProfile.Default)),
        )
    }

    @Test fun theDefaultRuntimeIsSizedForThePlatform() {
        assertEquals(RuntimeConfig.platformDefault(), WebViewRuntime.Default.config)
    }
}
