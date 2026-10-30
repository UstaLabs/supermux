package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import dev.supermux.webview.engine.desktop.MemoryHost
import dev.supermux.webview.engine.mac.MacGlueReady
import dev.supermux.webview.engine.mac.MacMemoryHook
import dev.supermux.webview.engine.mac.MacMemoryHost
import dev.supermux.webview.engine.mac.MacPlatform
import dev.supermux.webview.engine.windows.WinMemoryHook
import dev.supermux.webview.engine.windows.WinPlatform

/**
 * On macOS and Windows, while anything shows its web views, the runtime hears memory pressure
 * ([WebViewRuntime.onMemoryPressure]), once the glue runs: loaded in the background, never in this composition (the
 * engine factory started it when the runtime was made). macOS: its dispatch source; Windows: its low-memory notification.
 */
@Composable
internal actual fun PlatformHost(runtime: WebViewRuntime) {
    when {
        MacPlatform.isMac -> MacMemoryHost(runtime, MacGlueReady, MacMemoryHook)
        WinPlatform.isWindows -> MemoryHost(runtime, ready = WinMemoryHook::ready, hooks = WinMemoryHook)
    }
}
