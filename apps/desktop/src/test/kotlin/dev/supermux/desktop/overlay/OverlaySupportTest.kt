package dev.supermux.desktop.overlay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlaySupportTest {
    @Test fun macOsHasTheOverlay() = assertTrue(overlaySupportedOn("Mac OS X"))

    // Windows: its hot key (RegisterHotKey), a tool window on every virtual desktop, kept out of captures.
    @Test fun windowsHasTheOverlay() = assertTrue(overlaySupportedOn("Windows 11"))

    // Linux: checked on real GNOME, KDE and X11 desktops (headless on the box's GPU), not just Xvfb.
    @Test fun linuxHasTheOverlay() = assertTrue(overlaySupportedOn("Linux"))

    // Only the desktops it was built and checked on.
    @Test fun anotherOsHasNone() = assertFalse(overlaySupportedOn("FreeBSD"))
}
