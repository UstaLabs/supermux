package dev.supermux.webview

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

internal const val TIMEOUT = 5_000L

/**
 * A runtime on the test's virtual clock, so hidden and settle times are deterministic. Its scope is cancelled when the
 * test ends, so nothing it started outlives the test.
 */
@OptIn(ExperimentalTestApi::class)
internal suspend fun ComposeUiTest.testRuntime(
    factory: WebEngineFactory,
    maxLive: Int = 4,
    maxIdle: Int = 0,
    hiddenReleaseMillis: Long = 600_000,
    snapshotCacheBytes: Long = 64L shl 20,
): WebViewRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    currentCoroutineContext().job.invokeOnCompletion { scope.cancel() }
    return WebViewRuntime(
        factory = factory,
        config = RuntimeConfig(
            maxLive = maxLive,
            maxIdle = maxIdle,
            snapshotCacheBytes = snapshotCacheBytes,
            hiddenReleaseMillis = hiddenReleaseMillis,
        ),
        scope = scope,
        clock = { mainClock.currentTime },
    )
}
