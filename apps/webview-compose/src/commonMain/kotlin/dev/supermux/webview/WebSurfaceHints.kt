package dev.supermux.webview

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a [WebView] tells its engine's surface about where it is.
 * @property inScrollingList the view is an embed in a scrolling list, where a drag that starts on the page should still
 *   scroll the list: one that sizes itself to its page ([WebSizing.WrapContentHeight]), or one a scrolling parent
 *   measures (a lazy list's item of a fixed height). A pane gives its page every gesture.
 *
 * A plain class, not a data class, so it can grow without breaking code built against it: a new hint comes as a new
 * constructor parameter at the end, with a default, and the signature before it stays, hidden. Compare with `==`.
 */
@Immutable
public class WebSurfaceHints(public val inScrollingList: Boolean = false) {
    override fun equals(other: Any?): Boolean =
        this === other || other is WebSurfaceHints && inScrollingList == other.inScrollingList

    override fun hashCode(): Int = inScrollingList.hashCode()

    override fun toString(): String = "WebSurfaceHints(inScrollingList=$inScrollingList)"
}

/** What a view's surface hints are when nothing provides others. */
internal val DefaultWebSurfaceHints = WebSurfaceHints()

/**
 * The surface hints of the web views in this composition: provide them around the views they are about, and each
 * [WebView] passes them to its engine's surface ([dev.supermux.webview.engine.WebEngine.Surface] reads them here too).
 * Where nothing provides them, a view finds out about itself, from its sizing and the constraints it is measured with.
 *
 * A view can't see a scrolling list through a parent of fixed size: one in `item { Box(Modifier.height(240.dp)) {
 * WebView(…) } }` takes itself for a pane, and gives its page every gesture. Say so around it there:
 * `CompositionLocalProvider(LocalWebSurfaceHints provides WebSurfaceHints(inScrollingList = true)) { WebView(…) }`, and
 * `false` for a pane that a scrolling parent measures. What the app provides wins. The hint matters on iOS, macOS,
 * Linux and Windows; Android and the web chain scrolling to the list their own way, whatever it says.
 *
 * A composition local rather than a [WebView] parameter: the hint is about the place a view sits in (a list's item, a
 * pane), which the code around it knows and the view's own call may not, it reaches every view in that place at once,
 * and the views that need no hint, nearly all, take no parameter for it.
 */
public val LocalWebSurfaceHints: ProvidableCompositionLocal<WebSurfaceHints> =
    staticCompositionLocalOf { DefaultWebSurfaceHints }
