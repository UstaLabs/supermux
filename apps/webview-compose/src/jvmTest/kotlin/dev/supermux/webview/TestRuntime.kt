package dev.supermux.webview

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal const val TIMEOUT = 5_000L

/** A runtime on the test's virtual clock, so grace periods and settle times are deterministic. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.testRuntime(
    factory: FakeWebEngineFactory,
    maxLive: Int = 4,
    maxIdle: Int = 0,
    graceMillis: Long = 2_000,
    hiddenReleaseMillis: Long = 600_000,
): WebViewRuntime = WebViewRuntime(
    factory = factory,
    config = RuntimeConfig(
        maxLive = maxLive,
        maxIdle = maxIdle,
        snapshotCacheBytes = 64L shl 20,
        graceMillis = graceMillis,
        hiddenReleaseMillis = hiddenReleaseMillis,
    ),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    clock = { mainClock.currentTime },
)
