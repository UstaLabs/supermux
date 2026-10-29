package dev.supermux.webview.runtime

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.web.IframeWebEngine
import dev.supermux.webview.engine.web.WebEngineDecisions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlatformDefaultsWasmTest {
    @Test fun theWebKeepsTheDecidedBudgetThreeSparesAnd64MbOfSnapshots() {
        assertEquals(
            RuntimeConfig(maxLive = WebEngineDecisions.MAX_LIVE, maxIdle = 3, snapshotCacheBytes = 64L shl 20),
            RuntimeConfig.platformDefault(),
        )
    }

    @Test fun theWebMakesIframeEnginesThatSitAboveTheCanvas() = runTest {
        val engine = assertIs<WebEngineResult.Created>(platformEngineFactory().create(EngineConfig(WebProfile.Ephemeral))).engine
        assertIs<IframeWebEngine>(engine)
        assertEquals(EmbeddingMode.NativeView, engine.mode)
        assertTrue(engine.needsCutouts)
        assertTrue(engine.isUsable)
        engine.release()
    }

    @Test fun theDefaultRuntimeIsSizedForThePlatform() {
        assertEquals(RuntimeConfig.platformDefault(), WebViewRuntime.Default.config)
    }
}
