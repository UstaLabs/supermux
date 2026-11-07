package dev.supermux.webview.engine.ios

import dev.supermux.webview.runtime.WebViewRuntime
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.darwin.NSObjectProtocol

/**
 * iOS memory warnings, for a runtime (spec §4: a memory warning drops the spares and evicts down to half the budget),
 * while any web view uses it ([dev.supermux.webview.runtime.PlatformHost]). One observer per runtime however many web
 * views use it, since every call halves the snapshot cache again. Main thread only; the warnings arrive on the main
 * queue.
 */
internal object MemoryHook {
    private class Hook(val observer: NSObjectProtocol) {
        var users = 0
    }

    private val hooks = HashMap<WebViewRuntime, Hook>()

    fun attach(runtime: WebViewRuntime) {
        val hook = hooks.getOrPut(runtime) {
            Hook(
                NSNotificationCenter.defaultCenter.addObserverForName(
                    UIApplicationDidReceiveMemoryWarningNotification,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                ) { _ -> runtime.onMemoryPressure() },
            )
        }
        hook.users++
    }

    fun detach(runtime: WebViewRuntime) {
        val hook = hooks[runtime] ?: return
        if (--hook.users > 0) return
        hooks.remove(runtime)
        NSNotificationCenter.defaultCenter.removeObserver(hook.observer)
    }

    /** How many observers are registered for [runtime]: 1 while anything uses it, else 0. For tests. */
    fun observers(runtime: WebViewRuntime): Int = if (runtime in hooks) 1 else 0
}
