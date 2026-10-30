package dev.supermux.webview.engine.mac

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation

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

/**
 * [loading] run once, on a background thread (moved here from the runtime's review): extracting, hashing and loading
 * the glue take tens of milliseconds, a dropped frame if a composition or the main thread waited on them. [prewarm]
 * starts it (the factory does, when the runtime makes it); [await] suspends until it is done, without blocking.
 */
internal class BackgroundGlue(private val loading: GlueLoading) {
    private val load: Deferred<String?> =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).async(start = CoroutineStart.LAZY) { loading.ensureLoaded() }

    fun prewarm() {
        load.start()
    }

    /** Null once the glue is loaded, else why it isn't. */
    suspend fun await(): String? = load.await()
}

/** The process's glue, loaded in the background. */
internal val MacGlueReady: BackgroundGlue = BackgroundGlue(MacNativeLibrary)

/**
 * While composed, [runtime] hears macOS memory pressure through [hooks], once [glue] has loaded off the UI thread:
 * `PlatformHost` on macOS.
 */
@Composable
internal fun MacMemoryHost(runtime: WebViewRuntime, glue: BackgroundGlue, hooks: MemoryHooks) {
    LaunchedEffect(runtime, glue, hooks) {
        if (glue.await() != null) return@LaunchedEffect
        hooks.attach(runtime)
        try {
            awaitCancellation()
        } finally {
            hooks.detach(runtime)
        }
    }
}
