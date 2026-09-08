package dev.supermux.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * iOS's keep-alive container: the alpha/zIndex hide, same as Android's.
 *
 * There is no heavyweight-AWT problem here (the desktop actual's 0×0 layout exists only because a
 * SwingPanel's AWT child ignores Compose drawing modifiers). A `UIKitView` inside — the SwiftTerm
 * terminal in H5 — is an ORDINARY Compose-positioned interop view, and keeping the pane MEASURED at
 * full size while hidden means it is never re-parented at a degenerate size on re-show.
 */
@Composable
actual fun KeepAlivePanel(
    visible: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.keepAlivePanel(visible)) { content() }
}
