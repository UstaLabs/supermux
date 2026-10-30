package dev.supermux.webview.engine.android

import android.content.ComponentCallbacks2
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What engines need before they exist (Task 11), on the phone. */
@RunWith(AndroidJUnit4::class)
class PlumbingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun webViewStartsUpOnceAndHasEveryFeatureTheEngineNeeds() = runBlocking {
        val app = instrumentation.targetContext.applicationContext
        val result = withContext(Dispatchers.Main) {
            val first = WebViewStartup.begin(app)
            assertSame(first, WebViewStartup.begin(app), "one startup per process")
            withTimeout(20_000) { first.await() }
        }
        assertEquals(WebViewStartup.Result.Ready, result)
        withContext(Dispatchers.Main) {
            assertEquals(emptyList(), Features.missing(WebProfile.Ephemeral))
        }
    }

    @Test fun theRegistryKnowsTheActivityThatShowsWebViewsUntilItIsDestroyed() {
        val scenario = ActivityScenario.launch(EngineTestActivity::class.java)
        var shown: EngineTestActivity? = null
        val destroyed = mutableListOf<Boolean>()
        var stop: () -> Unit = {}
        scenario.onActivity { activity ->
            shown = activity
            ActivityRegistry.register(activity)
            assertSame(activity, ActivityRegistry.current())
            stop = ActivityRegistry.onDestroyed { gone -> if (gone === activity) destroyed += gone.isChangingConfigurations }
        }
        scenario.close()
        instrumentation.runOnMainSync {
            assertEquals(listOf(false), destroyed, "told once, and not as a configuration change")
            assertTrue(ActivityRegistry.current() !== shown)
            stop()
        }
    }

    @Test fun theEngineContextCarriesDarkOrLightAndItsActivity() {
        val scenario = ActivityScenario.launch(EngineTestActivity::class.java)
        scenario.onActivity { activity ->
            val context = EngineContext(activity, dark = true)
            assertFalse(context.isLightTheme())
            context.dark = false
            assertTrue(context.isLightTheme())
            assertSame(activity, context.findActivity())
            context.detach()
            assertNull(context.findActivity())
            assertTrue(context.isLightTheme(), "the theme survives the Activity")
        }
        scenario.close()
    }

    @Test fun memoryWarningsReachTheRuntimeThroughOneCallbackPerRuntime() {
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        instrumentation.runOnMainSync {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val runtime = WebViewRuntime(
                factory = WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) },
                config = RuntimeConfig(maxLive = 4, snapshotCacheBytes = 1L shl 20),
                scope = scope,
            )
            MemoryHook.attach(runtime, app)
            MemoryHook.attach(runtime, app)
            assertEquals(1, MemoryHook.callbacks(runtime))
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(4, runtime.pool.budget, "UI_HIDDEN isn't pressure")
            @Suppress("DEPRECATION")
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertEquals(2, runtime.pool.budget, "half the budget during the pressure window")
            MemoryHook.detach(runtime, app)
            assertEquals(1, MemoryHook.callbacks(runtime))
            MemoryHook.detach(runtime, app)
            assertEquals(0, MemoryHook.callbacks(runtime))
            scope.cancel()
        }
    }

    /**
     * An app switch is no memory pressure (final review, item 5): Android 14 says BACKGROUND at each, and as pressure it
     * halved the budget, evicted views and dropped covers. And a pressure window ends once the app is back in front.
     */
    @Test fun anAppSwitchKeepsTheBudgetAndAReturnEndsAPressureWindow() {
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var runtime: WebViewRuntime
        instrumentation.runOnMainSync {
            runtime = WebViewRuntime(
                factory = WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) },
                config = RuntimeConfig(maxLive = 4, snapshotCacheBytes = 1L shl 20),
                scope = scope,
            )
            MemoryHook.attach(runtime, app)
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            assertEquals(4, runtime.pool.budget, "BACKGROUND is a light trim, never pressure")
            @Suppress("DEPRECATION")
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            assertEquals(2, runtime.pool.budget, "half the budget during the pressure window")
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        }
        // The user comes back: an Activity of the app starts.
        val scenario = ActivityScenario.launch(EngineTestActivity::class.java)
        try {
            instrumentation.runOnMainSync { assertEquals(4, runtime.pool.budget, "the window ends with the app back in front") }
        } finally {
            scenario.close()
            instrumentation.runOnMainSync {
                MemoryHook.detach(runtime, app)
                scope.cancel()
            }
        }
    }

    private fun android.content.Context.isLightTheme(): Boolean {
        val values = obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
        try {
            return values.getBoolean(0, true)
        } finally {
            values.recycle()
        }
    }
}
