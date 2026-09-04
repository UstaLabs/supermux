package dev.supermux.ui.editor

import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.supermux.ui.editor.engine.EditorEngine

/**
 * What the Android view host needs beyond [EditorEngine]: the engine's live `WebView`, created on
 * first ask. Android's engine implements this; nothing in `commonMain` knows it exists.
 */
interface WebViewEditorEngine : EditorEngine {
    /** The engine's WebView, created (and its page load started) on the first call. */
    fun obtainWebView(): WebView
}

/**
 * Hosts the engine's WebView.
 *
 * Two behaviours are load-bearing and both are ported verbatim from Android's own host:
 *
 *  • The WebView is attached only after the editor chrome has committed TWO frames. Creating a
 *    session's first WebView stalls the main thread for hundreds of ms (Chromium provider + GPU
 *    functor init); doing that inside the tab-switch frame freezes whatever was last drawn — the
 *    "first editor open flashes" bug. Deferred, the tab switch paints the dark editor chrome
 *    instantly and the stall hides behind a static, correct-looking screen.
 *  • The WebView stays INVISIBLE until cm6 has first-painted. A WebView draws its raw surface WHITE
 *    for the first frames of a fresh load — before the page's own dark background applies — which no
 *    setBackgroundColor reliably prevents. While invisible the surface's dark backing shows through.
 */
@Composable
actual fun EditorEngineHost(engine: EditorEngine, visible: Boolean, modifier: Modifier) {
    val web = engine as? WebViewEditorEngine ?: return
    val ready by engine.ready.collectAsState()
    val failed by engine.failed.collectAsState()
    if (failed != null) return

    var attachWebView by remember(web) { mutableStateOf(false) }
    LaunchedEffect(web) {
        withFrameNanos {}
        withFrameNanos {}
        attachWebView = true
    }
    if (!attachWebView) return

    val show = ready && visible
    AndroidView(
        modifier = modifier,
        factory = { web.obtainWebView().also { it.visibility = if (show) View.VISIBLE else View.INVISIBLE } },
        update = { it.visibility = if (show) View.VISIBLE else View.INVISIBLE },
        onRelease = { /* destroyed with the engine */ },
    )
}
