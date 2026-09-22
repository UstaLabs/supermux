package dev.supermux.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android's keep-alive container: the same alpha/zIndex hide the app has always used, wrapped in a
 * Box so the composable form is available on both platforms.
 *
 * There is no heavyweight AWT interop here, so the pane can stay MEASURED at full size while
 * hidden: an AndroidView inside it (TextureView, WebView) is never re-laid-out at a degenerate size
 * and a re-show costs nothing.
 */
@Composable
actual fun KeepAlivePanel(
    visible: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.keepAlivePanel(visible)) { content() }
}
