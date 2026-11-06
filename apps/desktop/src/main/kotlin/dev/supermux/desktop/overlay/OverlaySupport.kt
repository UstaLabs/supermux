// Where the assistant overlay (#62) can float.
package dev.supermux.desktop.overlay

/**
 * macOS and Windows. Linux is built (override-redirect windows above full-screen apps and on every
 * workspace, an X11 key grab, Wayland's GlobalShortcuts portal) but waits for a check on a real desktop:
 * under Xvfb its transparent windows can't be seen at all. Until then its switch stays out of Settings.
 */
internal fun overlaySupportedOn(osName: String): Boolean = osName.startsWith("Mac") || osName.startsWith("Windows")
