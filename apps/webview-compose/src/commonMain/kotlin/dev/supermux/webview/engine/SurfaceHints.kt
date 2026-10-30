package dev.supermux.webview.engine

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a [dev.supermux.webview.WebView] tells its engine's [WebEngine.Surface] about where it is.
 * @property inScrollingList the view is an embed in a scrolling list, where a drag that starts on the page should still
 *   scroll the list: one that sizes itself to its page ([dev.supermux.webview.WebSizing.WrapContentHeight]), or one a
 *   scrolling parent measures (a lazy list's item of a fixed height). A pane gives its page every gesture.
 */
@Immutable
data class WebSurfaceHints(val inScrollingList: Boolean = false)

/** What a view's surface hints are when nothing provides others. */
internal val DefaultWebSurfaceHints = WebSurfaceHints()

/**
 * The hints of the view whose engine surface is being composed. [dev.supermux.webview.WebView] provides them: the ones
 * the app provides around the view, if it does, or else what the view finds out about itself.
 */
val LocalWebSurfaceHints = staticCompositionLocalOf { DefaultWebSurfaceHints }
