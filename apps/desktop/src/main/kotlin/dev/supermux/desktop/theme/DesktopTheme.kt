package dev.supermux.desktop.theme

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.supermux.desktop.platform.DesktopPlatform
import dev.supermux.desktop.ui.SupermuxContextMenuRepresentation
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.NoticeOverlay
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs

/**
 * Desktop's thin wrapper over the shared [SupermuxTheme].
 *
 * The only desktop-specific bit: right-click menus are drawn by Compose Desktop itself and the
 * only supported way to restyle them is to replace the representation. Provided at the theme root
 * so every window (main, detached, dialogs) gets the same one. See `ui/DesktopContextMenu.kt`.
 *
 * Desktop keeps the default `LocalModalHost` (the identity wrapper): since the M5 native editor
 * there is no heavyweight AWT child left for a dialog to hide.
 *
 * The second: the persisted UI preferences (`ui/prefs/UiPrefs.kt`) are installed on `LocalUiPrefs`
 * here, so every window root gets them from one place. `Main.kt` passes the real store
 * (`desktopDeps.settings`); anything else — tests, the interop probe — falls back to a
 * process-local one, which behaves identically but persists nothing.
 *
 * Haptics stay on the shared `NoHaptics` default (through `DesktopPlatform`) — no actuator here. No typography is
 * passed either: the shared theme reads `LocalWindowWidthClass`/`LocalInputMode` (provided at each
 * window root) and desktop is always Pointer, so it always resolves to the desktop scale.
 */
@Composable
fun DesktopTheme(
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    textScale: Float = 1f,
    uiPrefs: UiPrefs? = null,
    content: @Composable () -> Unit,
) {
    val contextMenu = remember { SupermuxContextMenuRepresentation() }
    // Platform services (links, clipboard, pickers, no-op haptics) — provided here so every
    // window root (main + detached) installs them from one place, as on Android.
    val platform = remember { DesktopPlatform() }
    val prefs = uiPrefs ?: remember { UiPrefs(InMemorySettingsStore()) }
    CompositionLocalProvider(
        LocalContextMenuRepresentation provides contextMenu,
        LocalPlatform provides platform,
        LocalUiPrefs provides prefs,
    ) {
        SupermuxTheme(
            appearance = appearance,
            textScale = textScale,
        ) {
            // Desktop's `Platform.notices`: a snackbar at the window root, so a shared screen's
            // one-line "couldn't open that" surfaces the same way Android's Toast does. The host
            // itself is `:ui`'s NoticeOverlay — iOS raises its notices exactly the same way.
            NoticeOverlay(platform.notices) { content() }
        }
    }
}
