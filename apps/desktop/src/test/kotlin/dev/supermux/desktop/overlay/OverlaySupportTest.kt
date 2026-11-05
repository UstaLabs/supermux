package dev.supermux.desktop.overlay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlaySupportTest {
    @Test fun macOsHasTheOverlay() = assertTrue(overlaySupportedOn("Mac OS X"))

    // Windows: its hot key (RegisterHotKey), a tool window on every virtual desktop, kept out of captures.
    @Test fun windowsHasTheOverlay() = assertTrue(overlaySupportedOn("Windows 11"))

    // Linux waits for a check on a real desktop: under Xvfb its transparent windows can't be seen at all.
    @Test fun linuxWaitsForARealDesktopCheck() = assertFalse(overlaySupportedOn("Linux"))

    // Only the desktops it was built and checked on.
    @Test fun anotherOsHasNone() = assertFalse(overlaySupportedOn("FreeBSD"))
}
