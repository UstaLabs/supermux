package dev.supermux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMediaTest {
    private val env = mapOf("XDG_CACHE_HOME" to "/xdg", "LOCALAPPDATA" to "C:\\Users\\a\\AppData\\Local")

    @Test fun eachOsUsesItsOwnCacheFolder() {
        assertEquals("/xdg", mediaCacheBase(DesktopOs.of("Linux"), env::get, "/home/a"))
        assertEquals("/home/a/.cache", mediaCacheBase(DesktopOs.of("Linux"), { null }, "/home/a"))
        assertEquals("/home/a/.cache", mediaCacheBase(DesktopOs.of("Linux"), { if (it == "XDG_CACHE_HOME") " " else null }, "/home/a"))
        assertEquals("/Users/a/Library/Caches", mediaCacheBase(DesktopOs.of("Mac OS X"), env::get, "/Users/a"))
        assertEquals("C:\\Users\\a\\AppData\\Local", mediaCacheBase(DesktopOs.of("Windows 11"), env::get, "C:\\Users\\a"))
        assertEquals("C:\\Users\\a\\AppData\\Local", mediaCacheBase(DesktopOs.of("Windows 11"), { null }, "C:\\Users\\a"))
    }

    /** Eight hand-written os.name parsers; some asked "win" before "mac", and "Darwin" contains "win". */
    @Test fun darwinIsMacNotWindows() {
        assertEquals("/Users/a/Library/Caches", mediaCacheBase(DesktopOs.of("Darwin"), env::get, "/Users/a"))
    }
}
