// Navigation 3 destination keys for the desktop window.
//
// The back stack IS the navigation state (Nav3 model): always starts with [Home], full-pane
// overlays are pushed on top, back pops. No parallel boolean flags as source of truth.
package dev.supermux.desktop.shell

/**
 * Destinations in the desktop [ShellUiState.backStack].
 *
 * - [Home] — session list + session detail (the persistent workspace shell). Always the stack root.
 * - Overlay routes — full-pane layers rendered via [FullPaneOverlaySceneStrategy] on top of Home
 *   so chat/editor/terminal composition under Home is not disposed.
 *
 * The New-Session launcher is intentionally *not* a route: it is a detail-pane swap that must
 * leave the sidebar mounted (see [ShellUiState.launcherOpen]).
 */
sealed interface DesktopRoute {
    data object Home : DesktopRoute

    data class Settings(val section: SettingsSection = SettingsSection.Agents) : DesktopRoute
    data object Archived : DesktopRoute
    data object Usage : DesktopRoute
    data object AppUpdate : DesktopRoute
}
