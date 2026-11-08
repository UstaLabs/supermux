package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.supermux.webview.engine.ios.MemoryHook

/** While anything shows its web views, the runtime hears iOS memory warnings ([WebViewRuntime.onMemoryPressure]). */
@Composable
internal actual fun PlatformHost(runtime: WebViewRuntime) {
    DisposableEffect(runtime) {
        MemoryHook.attach(runtime)
        onDispose { MemoryHook.detach(runtime) }
    }
}
