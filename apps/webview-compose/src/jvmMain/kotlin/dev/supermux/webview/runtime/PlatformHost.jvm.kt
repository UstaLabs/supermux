package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import dev.supermux.webview.engine.mac.MacGlueReady
import dev.supermux.webview.engine.mac.MacMemoryHook
import dev.supermux.webview.engine.mac.MacMemoryHost
import dev.supermux.webview.engine.mac.MacPlatform

/**
 * On macOS, while anything shows its web views, the runtime hears memory pressure ([WebViewRuntime.onMemoryPressure]),
 * once the glue has loaded: in the background, never in this composition (the engine factory started it when the
 * runtime was made).
 */
@Composable
internal actual fun PlatformHost(runtime: WebViewRuntime) {
    if (!MacPlatform.isMac) return
    MacMemoryHost(runtime, MacGlueReady, MacMemoryHook)
}
