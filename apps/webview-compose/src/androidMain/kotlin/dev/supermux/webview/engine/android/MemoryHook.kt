package dev.supermux.webview.engine.android

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import dev.supermux.webview.runtime.WebViewRuntime

/**
 * Android's memory warnings, for a runtime (spec §4: `onTrimMemory` drops the spares and evicts down to half the
 * budget). One callback per runtime however many web views use it, since every call halves the snapshot cache again.
 * Main thread only.
 */
internal object MemoryHook {
    private class Hook(val callbacks: ComponentCallbacks2) {
        var users = 0
    }

    private val hooks = HashMap<WebViewRuntime, Hook>()

    fun attach(runtime: WebViewRuntime, context: Context) {
        val hook = hooks.getOrPut(runtime) {
            Hook(
                object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        if (isMemoryPressure(level)) runtime.onMemoryPressure()
                    }

                    @Deprecated("Superseded by onTrimMemory; old devices still call it.")
                    override fun onLowMemory() = runtime.onMemoryPressure()

                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                },
            ).also { context.applicationContext.registerComponentCallbacks(it.callbacks) }
        }
        hook.users++
    }

    fun detach(runtime: WebViewRuntime, context: Context) {
        val hook = hooks[runtime] ?: return
        if (--hook.users > 0) return
        hooks.remove(runtime)
        context.applicationContext.unregisterComponentCallbacks(hook.callbacks)
    }

    /** How many callbacks are registered for [runtime]: 1 while anything uses it, else 0. For tests. */
    fun callbacks(runtime: WebViewRuntime): Int = if (runtime in hooks) 1 else 0
}
