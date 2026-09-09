package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.supermux.ui.editor.engine.EditorEngine

/**
 * Hosts the engine's `WKWebView` (cluster H5).
 *
 * Both of Android's load-bearing behaviours are ported, because both are about the same two frames
 * and neither is Android-specific:
 *
 *  • The web view is attached only after the editor chrome has committed TWO frames. Creating the
 *    first `WKWebView` in a process starts a content process and a network process; doing that
 *    inside the tab-switch frame freezes whatever was last drawn. Deferred, the tab switch paints
 *    the dark editor chrome instantly and the stall hides behind a static, correct-looking screen.
 *  • It stays HIDDEN until cm6 has first-painted. A fresh web view draws white for its first frames
 *    — before the page's own `#282c34` applies — and while hidden the surface's dark backing shows
 *    through instead.
 *
 * The hide is `UIView.hidden`, not `Modifier.alpha`: a `UIKitView`'s child is composited by UIKit
 * ABOVE the Compose canvas, so a Compose drawing modifier does not touch it (the same fact that
 * made `KeepAlivePanel.ios` adopt the desktop 0×0 strategy). `hidden` is UIKit's own switch and it
 * keeps the view LAID OUT at full size — which is what CodeMirror wants, since a re-show costs no
 * relayout and its scroller never sees a degenerate viewport.
 */
@Composable
actual fun EditorEngineHost(engine: EditorEngine, visible: Boolean, modifier: Modifier) {
    val web = engine as? IosWebEditorEngine ?: return
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
    UIKitView(
        factory = { web.obtainWebView().also { it.hidden = !show } },
        modifier = modifier,
        update = { it.hidden = !show },
        // The page is a text editor: it owns its own scrolling, selection, magnifier and (with a
        // hardware keyboard) key handling. Compose's gesture arbitration must not claim a drag
        // before CodeMirror's scroller sees it.
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
        // Nothing to release here: the web view is owned by the engine and torn down by its
        // dispose(), which the surface calls — matching Android's onRelease comment.
        onRelease = { },
    )
}
