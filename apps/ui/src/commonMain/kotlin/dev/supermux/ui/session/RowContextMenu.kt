package dev.supermux.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.ui.notify.NotifyTarget
import dev.supermux.ui.notify.NotifyTargetMenu

/** One entry of a row's right-click menu. */
data class RowContextMenuEntry(val label: String, val onClick: () -> Unit) {
    /** #163: set by [notifications]: choosing the entry opens the Notifications choices for this target at the row. */
    var notify: NotifyTarget? = null
        private set

    companion object {
        /** "Notifications…": opens the agent's, session's or applet's Notifications choices where the row is. */
        fun notifications(label: String, target: NotifyTarget): RowContextMenuEntry =
            RowContextMenuEntry(label) {}.also { it.notify = target }
    }
}

/**
 * A right-click context menu around a list row ([PlatformRowContextMenu]); an entry made with
 * [RowContextMenuEntry.notifications] opens the Notifications choices as a menu at the row (#163).
 */
@Composable
fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
) {
    var notifyFor by remember { mutableStateOf<NotifyTarget?>(null) }
    Box {
        PlatformRowContextMenu(
            items = { items().map { e -> e.notify?.let { t -> RowContextMenuEntry(e.label) { notifyFor = t } } ?: e } },
            content = content,
        )
        notifyFor?.let { NotifyTargetMenu(it, onDismiss = { notifyFor = null }) }
    }
}

/**
 * A right-click context menu around a list row.
 *
 * Desktop's `ContextMenuArea` (the sidebar's rename / mute / archive menu); a passthrough on
 * Android, where the same actions live in the row's overflow menu and its swipe actions — there is
 * no right-click on a phone. [items] is a lambda so it is only evaluated when the menu opens,
 * exactly as `ContextMenuArea` expects.
 */
@Composable
expect fun PlatformRowContextMenu(
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
