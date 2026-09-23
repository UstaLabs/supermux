package dev.supermux.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * iOS's keep-alive container: the DESKTOP strategy (0×0 layout), not Android's alpha hide.
 *
 * H5 correction. This actual used to delegate to [keepAlivePanel] with a KDoc claiming a
 * `UIKitView` is "an ORDINARY Compose-positioned interop view". It is not. A `UIKitView` mounts a
 * real `UIView` into a UIKit container that Compose positions but does NOT paint: the view is
 * composited by UIKit, above the Compose canvas. `Modifier.alpha(0f)` sets the alpha of the COMPOSE
 * layer, which the interop child is not part of — so an alpha-hidden pane would keep its
 * `WKWebView` fully visible on top of whatever replaced it, and still take touches.
 * That is the same class of problem the desktop actual was written for; only the toolkit differs
 * (heavyweight AWT there, UIKit compositing here), so the same fix applies.
 *
 * STRATEGY, therefore, exactly as JVM's: [content] stays in the SAME composition slot whether
 * visible or not — every `remember` inside it (the `TerminalClient`, the terminal's engine
 * session, the editor engine) survives a hide/show cycle — but when hidden the wrapping Box is laid
 * out at **0×0** (`Modifier.size(0.dp)` + clip). Compose propagates those bounds to the interop
 * view's container, so the `UIView` is clipped away and cannot be touched.
 *
 * The consequence every hidden pane must handle is the DEGENERATE SIZE this produces. It was
 * originally written against the Swift terminal view's own source (a 0×0 hide was inert inside it,
 * but a HALF-degenerate 0×H layout pass computed `cols = 0` and reported it); Plan 4 replaced that
 * view with the shared Compose renderer and the hazard is unchanged, because it comes from the
 * LAYOUT, not the widget. The shared surface guards `cols > 0 && rows > 0` before
 * `TerminalClient.resize`, so a hidden pane can never shrink the remote pty, and on re-show the
 * full-size layout pass reports the real grid — the pty is restored to the foreground pane's
 * geometry with no explicit re-send.
 */
@Composable
actual fun KeepAlivePanel(
    visible: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        if (visible) {
            modifier.fillMaxSize().zIndex(1f)
        } else {
            // 0×0 + clip: layout-level hiding, the only kind a UIKit-composited interop child respects.
            modifier.size(0.dp).clipToBounds().zIndex(0f)
        },
    ) {
        content()
    }
}
