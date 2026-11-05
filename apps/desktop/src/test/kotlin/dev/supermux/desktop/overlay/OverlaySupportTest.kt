package dev.supermux.desktop.overlay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlaySupportTest {
    @Test fun macOsHasTheOverlay() = assertTrue(overlaySupportedOn("Mac OS X"))

    // Windows gets it with its own pass (its hot key, keeping it out of captures), not before.
    @Test fun windowsWaitsForItsOwnPass() = assertFalse(overlaySupportedOn("Windows 11"))

    // A Linux desktop's compositor may not keep it on top.
    @Test fun linuxHasNone() = assertFalse(overlaySupportedOn("Linux"))
}
