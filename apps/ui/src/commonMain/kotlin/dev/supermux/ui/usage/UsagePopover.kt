// The anchored Usage container (cluster E6) — desktop's `usage/UsagePopover.kt`, moved as-is.
//
// It sits above the sidebar footer Usage icon, not in the window centre, so the parent must be
// that icon's Box: `Popup` measures against its anchor. POINTER-ONLY on purpose — a popover
// anchored to a 28dp rail icon is a mouse affordance; a touch device gets the full
// [dev.supermux.ui.usage.UsageScreen] as its own route instead (Android's `Route.Usage`), which is
// why this composable renders nothing without a pointer.
//
// The AWT interop shield stays a desktop concern: on desktop Compose cannot paint over a
// heavyweight child (the JCEF editor), so the popover announces itself through [ModalHost] and
// `DesktopTheme`'s host does the retain/release that `ModalOpen()` used to do here directly.
package dev.supermux.ui.usage

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.widgets.ModalHost

/**
 * Icon-anchored Usage card. Prefer placing this as a sibling of the Usage `IconButton`
 * inside a `Box` so the popup sits on top of that icon, not in the window center.
 */
@Composable
fun UsagePopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    // No mouse or touchpad → no anchored popover (the host routes to the full screen instead).
    if (!LocalPointerAvailable.current) return
    ModalHost {}
    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gap = 8
                // Right-align to the icon (footer icons are at the trailing edge of the sidebar).
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX)
                val yAbove = anchorBounds.top - popupContentSize.height - gap
                val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                val y = if (yAbove >= 0) yAbove else (anchorBounds.bottom + gap).coerceIn(0, maxY)
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val cs = MaterialTheme.colorScheme
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Surface(
            modifier = Modifier
                .width(380.dp)
                .heightIn(min = 200.dp, max = 520.dp)
                .testTag("usage_overlay")
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        onDismissRequest()
                        true
                    } else false
                },
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            color = cs.surfaceContainerHigh,
        ) {
            content()
        }
    }
}
