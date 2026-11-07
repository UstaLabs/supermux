package dev.supermux.webview.engine.android

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import dev.supermux.webview.runtime.WebViewRuntime

/**
 * Android's memory signals, for a runtime ([MemorySignals]). One hook per runtime however many web views use it, since
 * every pressure call halves the snapshot cache again. Main thread only.
 * - The running levels and `onLowMemory` are memory pressure (spec §4: the spares go, and the pool evicts down to half
 *   its budget, for a while).
 * - A cached process (BACKGROUND and the levels past it) gets a light trim ([WebViewRuntime.onBackgroundTrim]): the
 *   decoded snapshots, which decode again from what the cache stores, the spares, and the parked pages, which save
 *   themselves first. The views keep their pages, and the cache what it stores: an app switch, which is what Android 14
 *   reports this for, takes nothing the user would see come back.
 * - Back in the foreground after its UI went away, the app is out of any pressure window.
 */
internal object MemoryHook {
    private class Hook(val callbacks: ComponentCallbacks2, val lifecycle: Application.ActivityLifecycleCallbacks?) {
        var users = 0
    }

    private val hooks = HashMap<WebViewRuntime, Hook>()

    fun attach(runtime: WebViewRuntime, context: Context) {
        val hook = hooks.getOrPut(runtime) {
            val signals = MemorySignals(
                pressure = runtime::onMemoryPressure,
                trim = runtime::onBackgroundTrim,
                recovered = runtime::onMemoryRecovered,
            )
            val callbacks = object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) = signals.onTrimMemory(level)

                @Deprecated("Superseded by onTrimMemory; old devices still call it.")
                override fun onLowMemory() = signals.onLowMemory()

                override fun onConfigurationChanged(newConfig: Configuration) = Unit
            }
            val app = context.applicationContext as? Application
            val lifecycle = app?.let { foregroundWatch(signals).also(it::registerActivityLifecycleCallbacks) }
            Hook(callbacks, lifecycle).also { context.applicationContext.registerComponentCallbacks(it.callbacks) }
        }
        hook.users++
    }

    fun detach(runtime: WebViewRuntime, context: Context) {
        val hook = hooks[runtime] ?: return
        if (--hook.users > 0) return
        hooks.remove(runtime)
        context.applicationContext.unregisterComponentCallbacks(hook.callbacks)
        hook.lifecycle?.let { (context.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(it) }
    }

    /** How many callbacks are registered for [runtime]: 1 while anything uses it, else 0. For tests. */
    fun callbacks(runtime: WebViewRuntime): Int = if (runtime in hooks) 1 else 0

    /** Tells [signals] when one of the app's Activities starts: the app may be back in the foreground. */
    private fun foregroundWatch(signals: MemorySignals) = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = signals.onActivityStarted()

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
