package dev.supermux.ui.widgets

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Keep a panel composed but hidden when [visible] is false (web-style v-show). For PURE COMPOSE
 * content only — identical on both platforms, so it is not an expect.
 *
 * ⚠️ Do NOT use this on desktop for content containing a Swing panel: that hosts a heavyweight AWT
 * child which paints in the AWT layer, so Compose alpha/zIndex do not hide it (a known Compose
 * interop limitation) — use [KeepAlivePanel] instead.
 */
@Stable
fun Modifier.keepAlivePanel(visible: Boolean): Modifier = this
    .fillMaxSize()
    .zIndex(if (visible) 1f else 0f)
    .alpha(if (visible) 1f else 0f)
    .then(
        if (!visible) {
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
        } else {
            Modifier
        },
    )

/**
 * Keep-alive container for a whole pane: [content] stays in the SAME composition slot whether
 * visible or not — so every `remember` inside it (a terminal client, a web view, a scroll state)
 * survives a hide/show cycle — but it is not shown while [visible] is false.
 *
 * HOW it hides is the one thing the platforms disagree about, hence expect/actual:
 *
 *  • JVM/desktop lays the wrapper out at **0×0**. A pane here may embed a heavyweight AWT child
 *    (the JCEF editor) which ignores Compose drawing modifiers entirely and would
 *    keep painting over every Compose sibling; the interop wrapper propagates Compose layout
 *    bounds to its AWT child, so 0×0 bounds are the only kind of hiding it respects.
 *  • iOS does the same, for the same reason in a different toolkit: a `UIKitView`'s child is
 *    composited by UIKit ABOVE the Compose canvas, so `Modifier.alpha` — which only dims the
 *    Compose layer — does not hide it either (H5 correction; the iOS actual used to claim it did).
 *  • Android has no interop of that shape: an `AndroidView` is drawn by the same view system as
 *    the Compose host, so it uses the cheaper [keepAlivePanel] alpha/zIndex hide, which keeps the
 *    pane MEASURED at full size — a re-shown pane needs no re-layout and an embedded platform view
 *    (TextureView, WebView) is never re-parented at a degenerate size.
 */
@Composable
expect fun KeepAlivePanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
