package dev.supermux.android.editor

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** One warm-up per process — survives recompositions / re-entering the composable. */
private var warmedThisProcess = false

/**
 * Invisible, one-shot WebView warm-up. Mount once near the app root (once past pairing).
 *
 * The editor is the app's ONLY WebView, so the FIRST time the user taps Editor it pays — on the
 * main thread and the GPU, all at once — the Chromium provider load, the window's first-ever
 * WebView attach, the render-surface init, and the editor page's asset/JS load. Measured on the
 * emulator that was ~2.5 s of UI-thread jank and ~5 s of GPU time (gfxinfo: 100% janky frames),
 * long enough to blank the WHOLE window — i.e. the "blink" on first editor open. The dark backing
 * behind the WebView can't hide it because the entire window flashes, not just the editor pane.
 *
 * Mounting a 1-px, fully-transparent WebView that loads the SAME editor page pays all of that early
 * and hidden (behind the session list right after launch), so the first real editor open is instant.
 * It tears itself down as soon as it is warm, so no WebView is held for the session's lifetime.
 */
@Composable
fun WebViewWarmup() {
    if (warmedThisProcess) return
    var warming by remember { mutableStateOf(true) }
    if (!warming) return

    Box(Modifier.size(1.dp).alpha(0f)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = true
                    setBackgroundColor(0xFF282C34.toInt())
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // Provider + window-attach + GPU surface + asset/JS are now warm.
                            // Retire on the next frame so no WebView is kept around.
                            warmedThisProcess = true
                            view?.post { warming = false }
                        }

                        override fun onReceivedError(
                            v: WebView?,
                            r: WebResourceRequest?,
                            e: WebResourceError?,
                        ) {
                            // Never block on the warm-up; give up quietly on any error.
                            warmedThisProcess = true
                            v?.post { warming = false }
                        }
                    }
                    loadUrl("file:///android_asset/editor/index.html")
                }
            },
            // Removed from composition once warm → destroy the throwaway WebView.
            onRelease = { it.destroy() },
        )
    }
}
