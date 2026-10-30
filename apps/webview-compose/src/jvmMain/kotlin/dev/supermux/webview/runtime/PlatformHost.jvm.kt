package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.supermux.webview.engine.mac.MacMemoryHook
import dev.supermux.webview.engine.mac.MacPlatform

/** On macOS, while anything shows its web views, the runtime hears memory pressure ([WebViewRuntime.onMemoryPressure]). */
@Composable
internal actual fun PlatformHost(runtime: WebViewRuntime) {
    if (!MacPlatform.isMac) return
    DisposableEffect(runtime) {
        MacMemoryHook.attach(runtime)
        onDispose { MacMemoryHook.detach(runtime) }
    }
}
