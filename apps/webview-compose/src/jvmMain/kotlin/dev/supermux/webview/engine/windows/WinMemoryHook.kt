package dev.supermux.webview.engine.windows

import dev.supermux.webview.engine.desktop.MemoryHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object WinPlatform {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
}

/** Windows' low-memory notification (D21) for the runtimes web views use, once the glue runs (off the EDT). */
internal object WinMemoryHook : MemoryHooks(
    load = { if (Wv2RuntimeStarted.ready) null else "the Windows glue isn't running" },
    start = { WinGlue.startMemoryWatch() },
    register = { WinPages.onMemory = it },
) {
    /** Null once the runtime is ready: started off the EDT. */
    suspend fun ready(): String? = withContext(Dispatchers.IO) {
        when (val state = Wv2Runtime.start()) {
            is Wv2Runtime.State.Ready -> { Wv2RuntimeStarted.ready = true; null }
            is Wv2Runtime.State.Unavailable -> state.detail
        }
    }
}

internal object Wv2RuntimeStarted {
    @Volatile var ready: Boolean = false
}
