package dev.supermux.webview.engine.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertTrue

/**
 * The real runtime on the phone, with Android's engines, showing [content] in an [EngineTestActivity]: for tests of
 * what a view goes through (its phase, its bridge), as an app sees it. [block] runs off main; [onMain] hops there.
 */
internal fun runtimeOnAndroid(
    config: RuntimeConfig = RuntimeConfig(maxLive = 2, snapshotCacheBytes = 16L shl 20),
    content: @Composable () -> Unit,
    block: suspend RuntimeOnAndroid.() -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val runtime = WebViewRuntime(config.copy(scope = scope))
    val launched = ActivityScenario.launch(EngineTestActivity::class.java)
    try {
        launched.onActivity { it.setContent { ProvideWebViewRuntime(runtime) { content() } } }
        runBlocking { RuntimeOnAndroid(runtime).block() }
    } finally {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { scope.cancel() }
        launched.close()
    }
}

internal class RuntimeOnAndroid(val runtime: WebViewRuntime) {
    suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    /** Waits, up to [millis], for [condition], read on main. */
    suspend fun waitFor(what: String, millis: Long = 20_000, condition: () -> Boolean) {
        val met = withTimeoutOrNull(millis) {
            while (!onMain { condition() }) delay(20)
            true
        }
        assertTrue(met == true, "no $what within $millis ms")
    }
}
