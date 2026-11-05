package dev.supermux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostOsTest {
    @Test
    fun mac_os_name_detection() {
        assertTrue(isMacOs(DesktopOs.of("Mac OS X")))
        assertTrue(isMacOs(DesktopOs.of("macOS")))
        assertTrue(isMacOs(DesktopOs.of("Darwin")))
        assertFalse(isMacOs(DesktopOs.of("Linux")))
        assertFalse(isMacOs(DesktopOs.of("Windows 11")))
        assertFalse(isMacOs(DesktopOs.of(null)))
    }

    /** "Darwin" contains "win": a parser asking "win" first took a Mac for Windows. One answer, Mac first. */
    @Test
    fun one_answer_for_every_check() {
        assertTrue(isWindowsOs(DesktopOs.of("Windows 11")))
        assertFalse(isWindowsOs(DesktopOs.of("Darwin")))
        assertTrue(isLinuxOs(DesktopOs.of("Linux")))
        assertFalse(isLinuxOs(DesktopOs.of("Mac OS X")))
    }
}
