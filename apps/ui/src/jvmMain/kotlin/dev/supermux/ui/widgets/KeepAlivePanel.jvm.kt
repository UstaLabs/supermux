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
 * Heavyweight-safe keep-alive container for panes that embed a SwingPanel (the terminal) or JCEF.
 *
 * STRATEGY: [content] stays in the SAME composition slot whether visible or not — so every
 * `remember` inside it (the TerminalClient, the JediTermWidget, the connector) survives a hide/show
 * cycle — but when hidden, the wrapping Box is laid out at **0×0** (`Modifier.size(0.dp)` + clip).
 * SwingPanel propagates Compose layout bounds to its AWT child, so the heavyweight Swing component
 * gets 0×0 bounds: not painted, not clickable, can't hold focus. Alpha/zIndex alone would NOT
 * achieve this — a heavyweight AWT child ignores Compose drawing modifiers and would keep painting
 * over every Compose sibling.
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
actual fun KeepAlivePanel(
    visible: Boolean,
    modifier: Modifier,
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
