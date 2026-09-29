package dev.supermux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostOsTest {
    @Test
    fun mac_os_name_detection() {
        assertTrue(isMacOs("Mac OS X"))
        assertTrue(isMacOs("macOS"))
        assertTrue(isMacOs("Darwin"))
        assertFalse(isMacOs("Linux"))
        assertFalse(isMacOs("Windows 11"))
        assertFalse(isMacOs(null))
    }
}
