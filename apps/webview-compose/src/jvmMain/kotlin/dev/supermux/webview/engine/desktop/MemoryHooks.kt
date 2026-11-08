package dev.supermux.webview.engine.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.awaitCancellation

/**
 * A platform's memory pressure for the runtimes web views use (spec §4: pressure drops the spares and evicts down to half
 * the budget), while any of their views is composed (`PlatformHost`). [load] loads the platform's glue, null once it
 * is; [start] starts its source once; [register] hands the source the function its pressure calls (on the main thread).
 * macOS: its dispatch source; Windows: its low-memory notification.
 */
internal open class MemoryHooks(
    private val load: () -> String?,
    private val start: () -> Unit,
    private val register: (onPressure: () -> Unit) -> Unit,
) {
    private val users = HashMap<WebViewRuntime, Int>()
    private var started = false

    fun attach(runtime: WebViewRuntime) {
        if (load() != null) return
        if (!started) {
            started = true
            register(::onPressure)
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

/** While composed, [runtime] hears memory pressure through [hooks], once [ready] (the glue, off the UI thread) says null. */
@Composable
internal fun MemoryHost(runtime: WebViewRuntime, ready: suspend () -> String?, hooks: MemoryHooks) {
    LaunchedEffect(runtime, ready, hooks) {
        if (ready() != null) return@LaunchedEffect
        hooks.attach(runtime)
        try {
            awaitCancellation()
        } finally {
            hooks.detach(runtime)
        }
    }
}
