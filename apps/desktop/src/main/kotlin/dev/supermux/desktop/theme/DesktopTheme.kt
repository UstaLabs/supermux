package dev.supermux.desktop.theme

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.supermux.desktop.ui.SupermuxContextMenuRepresentation
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.ui.theme.supermuxTypography

/**
 * Desktop's thin wrapper over the shared [SupermuxTheme].
 *
 * The only desktop-specific bit: right-click menus are drawn by Compose Desktop itself and the
 * only supported way to restyle them is to replace the representation. Provided at the theme root
 * so every window (main, detached, dialogs) gets the same one. See `ui/DesktopContextMenu.kt`.
 *
 * Haptics stay on the shared `NoHaptics` default — desktop has no actuator.
 */
@Composable
fun DesktopTheme(
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val contextMenu = remember { SupermuxContextMenuRepresentation() }
    CompositionLocalProvider(LocalContextMenuRepresentation provides contextMenu) {
        SupermuxTheme(
            appearance = appearance,
            typography = supermuxTypography(),
            textScale = textScale,
            content = content,
        )
    }
}
