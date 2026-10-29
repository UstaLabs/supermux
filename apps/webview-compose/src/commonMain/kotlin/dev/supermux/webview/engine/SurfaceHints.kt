package dev.supermux.webview.engine

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a [dev.supermux.webview.WebView] tells its engine's [WebEngine.Surface] about where it is.
 * @property inScrollingList the view sizes itself to its page ([dev.supermux.webview.WebSizing.WrapContentHeight]):
 *   an embed in a scrolling list, where a drag that starts on the page should still scroll the list. A pane
 *   ([dev.supermux.webview.WebSizing.Fill]) gives its page every gesture.
 */
@Immutable
data class WebSurfaceHints(val inScrollingList: Boolean = false)

/** The hints of the view whose engine surface is being composed. [dev.supermux.webview.WebView] provides them. */
val LocalWebSurfaceHints = staticCompositionLocalOf { WebSurfaceHints() }
