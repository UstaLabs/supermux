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
 * Same as desktop: a hidden pane collapses to 0×0 and clips, which also moves any DOM overlay
 * (HtmlElementView) to a zero-size box — the web twin of SwingPanel's "nothing paints above me".
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
            modifier.size(0.dp).clipToBounds().zIndex(0f)
        },
    ) {
        content()
    }
}
