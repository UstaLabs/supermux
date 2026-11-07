package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.supermux.webview.engine.android.ActivityRegistry
import dev.supermux.webview.engine.android.MemoryHook
import dev.supermux.webview.engine.android.findActivity

/**
 * Every web view's Activity registers, so engines are built for it (and WebView starts up the first time), and the
 * runtime hears Android's memory warnings while anything shows its web views.
 */
@Composable
internal actual fun PlatformHost(runtime: WebViewRuntime) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, runtime) {
        activity?.let(ActivityRegistry::register)
        MemoryHook.attach(runtime, context)
        onDispose { MemoryHook.detach(runtime, context) }
    }
}
