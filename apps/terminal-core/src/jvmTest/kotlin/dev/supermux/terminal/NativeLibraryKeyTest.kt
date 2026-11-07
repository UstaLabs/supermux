package dev.supermux.terminal

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The native-library key and cache folder, which editor-syntax's SesNativeLoader.kt carries word for word
 * (tests/native-library-key.test.ts holds the two copies together, so this test covers both).
 */
class NativeLibraryKeyTest {
    @Test fun eachDesktopJvmNamesItsBuildTarget() {
        assertEquals("linux-x64", nativeLibraryKey("Linux", "amd64"))
        assertEquals("linux-arm64", nativeLibraryKey("Linux", "aarch64"))
        assertEquals("macos-x64", nativeLibraryKey("Mac OS X", "x86_64"))
        assertEquals("macos-arm64", nativeLibraryKey("Mac OS X", "aarch64"))
        assertEquals("windows-x64", nativeLibraryKey("Windows 11", "amd64"))
        assertEquals("windows-arm64", nativeLibraryKey("Windows 11", "aarch64"))
    }

    @Test fun anyOtherOsOrArchitectureHasNoKey() {
        assertNull(nativeLibraryKey("FreeBSD", "amd64"))
        assertNull(nativeLibraryKey("Linux", "riscv64"))
        assertNull(nativeLibraryKey("", ""))
    }

    @Test fun theKeyIgnoresTheDefaultLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            assertEquals("linux-x64", nativeLibraryKey("LINUX", "AMD64"))
            assertEquals("windows-arm64", nativeLibraryKey("WINDOWS 11", "AARCH64"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun theCacheFolderIsTheOsUsersOwn() {
        val env = mapOf("LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local", "XDG_CACHE_HOME" to "/xdg/cache")
        assertEquals(File("C:\\Users\\me\\AppData\\Local"), nativeLibraryCacheRoot("Windows 11", env::get, "C:\\Users\\me"))
        assertEquals(File("/Users/me/Library/Caches"), nativeLibraryCacheRoot("Mac OS X", env::get, "/Users/me"))
        assertEquals(File("/xdg/cache"), nativeLibraryCacheRoot("Linux", env::get, "/home/me"))
        assertEquals(File("/home/me/.cache"), nativeLibraryCacheRoot("Linux", emptyMap<String, String>()::get, "/home/me"))
    }

    @Test fun aRelativeXdgCacheHomeIsIgnored() {
        // The XDG spec: a relative path in an XDG variable is invalid.
        assertEquals(File("/home/me/.cache"), nativeLibraryCacheRoot("Linux", mapOf("XDG_CACHE_HOME" to "cache")::get, "/home/me"))
    }

    @Test fun noFolderWithoutItsVariableOrAHome() {
        assertNull(nativeLibraryCacheRoot("Windows 11", emptyMap<String, String>()::get, "C:\\Users\\me"))
        assertNull(nativeLibraryCacheRoot("Mac OS X", emptyMap<String, String>()::get, null))
        assertNull(nativeLibraryCacheRoot("Linux", emptyMap<String, String>()::get, null))
    }
}
