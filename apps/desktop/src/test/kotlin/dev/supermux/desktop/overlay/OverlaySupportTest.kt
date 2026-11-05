package dev.supermux.desktop.overlay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlaySupportTest {
    @Test fun macOsHasTheOverlay() = assertTrue(overlaySupportedOn("Mac OS X"))

    // Windows: its hot key (RegisterHotKey), a tool window on every virtual desktop, kept out of captures.
    @Test fun windowsHasTheOverlay() = assertTrue(overlaySupportedOn("Windows 11"))

    // Linux: override-redirect windows above everything on X11 (and XWayland); the key on X11, Wayland's portal later.
    @Test fun linuxHasTheOverlay() = assertTrue(overlaySupportedOn("Linux"))

    // Only the desktops it was built and checked on.
    @Test fun anotherOsHasNone() = assertFalse(overlaySupportedOn("FreeBSD"))
}
