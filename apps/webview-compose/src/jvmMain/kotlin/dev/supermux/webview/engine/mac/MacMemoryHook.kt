package dev.supermux.webview.engine.mac

import androidx.compose.runtime.Composable
import dev.supermux.webview.engine.desktop.MemoryHooks
import dev.supermux.webview.engine.desktop.MemoryHost
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * macOS memory pressure (warn and critical, one dispatch source for the process), through the desktop's [MemoryHooks]:
 * the glue loads, its source starts, and its upcall reaches the main thread through [MacPages].
 */
internal object MacMemoryHook : MemoryHooks(
    load = { MacNativeLibrary.ensureLoaded() },
    start = { MacGlue.startMemoryPressure() },
    register = { MacPages.onMemoryPressure = it },
)

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
internal fun MacMemoryHost(runtime: WebViewRuntime, glue: BackgroundGlue, hooks: MemoryHooks) =
    MemoryHost(runtime, glue::await, hooks)
