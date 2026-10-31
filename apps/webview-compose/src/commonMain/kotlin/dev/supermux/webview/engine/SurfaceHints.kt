package dev.supermux.webview.engine

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a [dev.supermux.webview.WebView] tells its engine's [WebEngine.Surface] about where it is.
 * @property inScrollingList the view is an embed in a scrolling list, where a drag that starts on the page should still
 *   scroll the list: one that sizes itself to its page ([dev.supermux.webview.WebSizing.WrapContentHeight]), or one a
 *   scrolling parent measures (a lazy list's item of a fixed height). A pane gives its page every gesture.
 */
@Immutable
public data class WebSurfaceHints(val inScrollingList: Boolean = false)

/** What a view's surface hints are when nothing provides others. */
internal val DefaultWebSurfaceHints = WebSurfaceHints()

/**
 * The hints of the view whose engine surface is being composed. [dev.supermux.webview.WebView] provides them: the ones
 * the app provides around the view, if it does, or else what the view finds out about itself, from its sizing and the
 * constraints it is measured with.
 *
 * A view can't see a scrolling list through a parent of fixed size: one in `item { Box(Modifier.height(240.dp)) {
 * WebView(…) } }` takes itself for a pane, and gives its page every gesture. Say so around it there:
 * `CompositionLocalProvider(LocalWebSurfaceHints provides WebSurfaceHints(inScrollingList = true)) { WebView(…) }`, and
 * `false` for a pane that a scrolling parent measures. The hint matters on iOS, macOS and Linux; Android and the web
 * chain scrolling to the list their own way, whatever it says.
 */
public val LocalWebSurfaceHints: ProvidableCompositionLocal<WebSurfaceHints> =
    staticCompositionLocalOf { DefaultWebSurfaceHints }
