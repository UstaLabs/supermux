package dev.supermux.webview.engine.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.webkit.WebView

/** The engine's WebView. It reports keyboard focus, which Compose never sees over a native view. */
@SuppressLint("ViewConstructor")
internal class EngineWebView(context: Context, private val onFocus: (Boolean) -> Unit) : WebView(context) {
    /** Whether the engine destroyed it. Nothing touches it after that. */
    var gone: Boolean = false

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        onFocus(focused)
    }
}
