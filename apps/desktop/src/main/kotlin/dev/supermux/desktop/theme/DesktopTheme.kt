package dev.supermux.desktop.theme

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.supermux.desktop.platform.DesktopPlatform
import dev.supermux.desktop.ui.ModalPresenceHost
import dev.supermux.desktop.ui.SupermuxContextMenuRepresentation
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.ui.widgets.LocalModalHost

/**
 * Desktop's thin wrapper over the shared [SupermuxTheme].
 *
 * The only desktop-specific bit: right-click menus are drawn by Compose Desktop itself and the
 * only supported way to restyle them is to replace the representation. Provided at the theme root
 * so every window (main, detached, dialogs) gets the same one. See `ui/DesktopContextMenu.kt`.
 *
 * The second: the shared dialogs and menus (`ui/widgets`) announce themselves through
 * `LocalModalHost`, and desktop's host is the AWT interop shield — `ModalOpen()` counts the surface
 * on `LocalModalPresence` so the heavyweight children (JediTerm, JCEF) lay themselves out at 0×0
 * and the modal is actually visible. See `ui/ModalPresence.kt`.
 *
 * Haptics stay on the shared `NoHaptics` default (through `DesktopPlatform`) — no actuator here. No typography is
 * passed either: the shared theme reads `LocalWindowWidthClass`/`LocalInputMode` (provided at each
 * window root) and desktop is always Pointer, so it always resolves to the desktop scale.
 */
@Composable
fun DesktopTheme(
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val contextMenu = remember { SupermuxContextMenuRepresentation() }
    // Platform services (links, clipboard, pickers, no-op haptics) — provided here so every
    // window root (main + detached) installs them from one place, as on Android.
    val platform = remember { DesktopPlatform() }
    CompositionLocalProvider(
        LocalContextMenuRepresentation provides contextMenu,
        LocalPlatform provides platform,
        LocalModalHost provides ModalPresenceHost,
    ) {
        SupermuxTheme(
            appearance = appearance,
            textScale = textScale,
            content = content,
        )
    }
}
