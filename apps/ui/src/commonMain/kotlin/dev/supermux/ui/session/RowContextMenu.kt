package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** One entry of a row's right-click menu. */
data class RowContextMenuEntry(val label: String, val onClick: () -> Unit)

/**
 * A right-click context menu around a list row.
 *
 * Desktop's `ContextMenuArea` (the sidebar's rename / mute / archive menu); a passthrough on
 * Android, where the same actions live in the row's overflow menu and its swipe actions — there is
 * no right-click on a phone. [items] is a lambda so it is only evaluated when the menu opens,
 * exactly as `ContextMenuArea` expects.
 */
@Composable
expect fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
)

/**
 * Whether [RowContextMenu] actually shows anything on this platform: true on desktop
 * (`ContextMenuArea`), false on Android, where the actual is a passthrough.
 */
expect val platformContextMenuAvailable: Boolean

/**
 * Whether a right-click menu can carry a row's actions.
 *
 * A Pointer-mode row (desktop, but also DeX / a Chromebook / a docked tablet / a phone in a
 * keyboard case — see Android's `InputModeDetector`) renders the lean pointer branch, which puts
 * rename / mute / archive in a right-click menu. On Android that menu is inert, so anything gated
 * on it MUST also offer a visible affordance. Read this local instead of assuming a platform, and
 * tests can drive the Android-shaped Pointer row on the JVM by providing `false`.
 */
val LocalContextMenuAvailable = staticCompositionLocalOf { platformContextMenuAvailable }
