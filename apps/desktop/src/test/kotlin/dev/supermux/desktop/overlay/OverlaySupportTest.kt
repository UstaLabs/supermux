package dev.supermux.desktop.overlay

import dev.supermux.desktop.platform.DesktopOs
import kotlin.test.Test
import kotlin.test.assertTrue

class OverlaySupportTest {
    @Test fun macOsHasTheOverlay() = assertTrue(overlaySupportedOn(DesktopOs.of("Mac OS X")))

    // Windows: its hot key (RegisterHotKey), a tool window on every virtual desktop, kept out of captures.
    @Test fun windowsHasTheOverlay() = assertTrue(overlaySupportedOn(DesktopOs.of("Windows 11")))

    // Linux: checked on real GNOME, KDE and X11 desktops (headless on the box's GPU), not just Xvfb.
    @Test fun linuxHasTheOverlay() = assertTrue(overlaySupportedOn(DesktopOs.of("Linux")))
}
