// Ported from apps/android/src/main/kotlin/dev/supermux/android/ui/KeepAlivePanel.kt, with a
// desktop-specific addition for heavyweight Swing interop — see [KeepAlivePanel].
package dev.supermux.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Keep a panel composed but hidden when [visible] is false (web-style v-show). Straight Android
 * port — for PURE COMPOSE content only. ⚠️ Do NOT use this for content containing a SwingPanel:
 * SwingPanel hosts a heavyweight AWT child that paints in the AWT layer, so Compose alpha/zIndex
 * do not hide it (known Compose interop limitation) — use [KeepAlivePanel] instead.
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
 * Heavyweight-safe keep-alive container for panes that embed a SwingPanel (the terminal).
 *
 * STRATEGY (plan Task 3 Step 3): [content] stays in the SAME composition slot whether visible or
 * not — so every `remember` inside it (the TerminalClient, the JediTermWidget, the connector)
 * survives a hide/show cycle — but when hidden, the wrapping Box is laid out at **0×0**
 * (`Modifier.size(0.dp)` + clip). SwingPanel propagates Compose layout bounds to its AWT child,
 * so the heavyweight Swing component gets 0×0 bounds: not painted, not clickable, can't hold
 * focus. Alpha/zIndex alone would NOT achieve this — a heavyweight AWT child ignores Compose
 * drawing modifiers and would keep painting over every Compose sibling.
 *
 * On re-show the same SwingPanel re-lays-out to full size and re-shows the SAME widget instance
 * (the factory result is remembered by the content), so the grid/scrollback are intact and the
 * client never dropped its websocket. A 0-size layout pass can make JediTerm report a degenerate
 * grid; DesktopTerminalPanel guards `resize` against cols/rows <= 0 so the remote pty is never
 * shrunk by a hide.
 *
 * Verified live (M2 Task 3 probe): client status stayed CONNECTED across hide → show with the
 * terminal content intact; see the task report.
 */
@Composable
fun KeepAlivePanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        if (visible) {
            modifier.fillMaxSize().zIndex(1f)
        } else {
            // 0×0 + clip: layout-level hiding, the only kind a heavyweight AWT child respects.
            modifier.size(0.dp).clipToBounds().zIndex(0f)
        },
    ) {
        content()
    }
}
