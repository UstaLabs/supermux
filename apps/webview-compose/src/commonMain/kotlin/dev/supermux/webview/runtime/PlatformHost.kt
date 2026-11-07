package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable

/**
 * What a platform needs from every [dev.supermux.webview.WebView]'s composition, before any engine exists. On Android:
 * the Activity the view shows in (engines are built for it, never for the application context) and the memory
 * warnings that [runtime] turns into [WebViewRuntime.onMemoryPressure]. Nothing elsewhere.
 */
@Composable
internal expect fun PlatformHost(runtime: WebViewRuntime)
