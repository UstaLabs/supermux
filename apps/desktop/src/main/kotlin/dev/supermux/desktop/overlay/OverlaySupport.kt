// Where the assistant overlay (#62) can float.
package dev.supermux.desktop.overlay

/**
 * macOS, Windows and Linux. On Linux the head and bubble are override-redirect windows, above full-screen
 * apps and on every workspace (X11, and XWayland on Wayland); the key from any app is an X11 grab, and on
 * Wayland only its GlobalShortcuts portal can hand one over.
 */
internal fun overlaySupportedOn(osName: String): Boolean =
    osName.startsWith("Mac") || osName.startsWith("Windows") || osName.startsWith("Linux")
