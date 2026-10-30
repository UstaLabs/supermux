package dev.supermux.webview.engine.mac

import dev.supermux.webview.runtime.WebViewRuntime

/**
 * macOS memory pressure (warn and critical, one dispatch source for the process) for the runtimes web views use (spec
 * §4: pressure drops the spares and evicts down to half the budget), while any of their views is composed
 * (`PlatformHost`). [load] loads the glue, null once it is; [start] starts the source. Main thread only: the upcall
 * comes there through [MacPages].
 */
internal open class MemoryHooks(private val load: () -> String?, private val start: () -> Unit) {
    private val users = HashMap<WebViewRuntime, Int>()
    private var started = false

    fun attach(runtime: WebViewRuntime) {
        if (load() != null) return
        if (!started) {
            started = true
            MacPages.onMemoryPressure = ::onPressure
            start()
        }
        users[runtime] = (users[runtime] ?: 0) + 1
    }

    fun detach(runtime: WebViewRuntime) {
        val count = users[runtime] ?: return
        if (count > 1) users[runtime] = count - 1 else users.remove(runtime)
    }

    /** 1 while anything uses [runtime], else 0. For tests. */
    fun observers(runtime: WebViewRuntime): Int = if (runtime in users) 1 else 0

    private fun onPressure() {
        for (runtime in users.keys.toList()) runtime.onMemoryPressure()
    }
}

/** The process's hook. */
internal object MacMemoryHook : MemoryHooks(load = { MacNativeLibrary.ensureLoaded() }, start = { MacGlue.startMemoryPressure() })
