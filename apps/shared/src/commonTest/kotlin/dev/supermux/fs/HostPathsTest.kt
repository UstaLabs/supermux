package dev.supermux.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one rule for whether a path on the broker's host is inside another (HostPaths.kt). */
class HostPathsTest {

    @Test fun aWindowsWorkdirMatchesTheHostsOwnSpellingWhateverItsCase() {
        // The session says c:\users\me\app; the host's search answers under its real path.
        assertEquals("src/Main.kt", hostRelativePath("c:\\users\\me\\app", "C:\\Users\\me\\app/src/Main.kt"))
    }

    @Test fun aWindowsPathMatchesOnEitherSeparator() {
        assertEquals("src/Main.kt", hostRelativePath("C:/Users/me/app", "C:\\Users\\me\\app\\src\\Main.kt"))
        assertEquals("", hostRelativePath("C:\\Users\\me\\app\\", "c:/users/me/app"))
    }

    @Test fun aShareIsAWindowsPathToo() {
        assertEquals("a.kt", hostRelativePath("\\\\Server\\Share\\app", "\\\\server\\share\\app\\a.kt"))
    }

    @Test fun aPosixPathStaysCaseSensitive() {
        assertNull(hostRelativePath("/home/u/App", "/home/u/app/src/a.kt"))
        assertEquals("src/a.kt", hostRelativePath("/home/u/app", "/home/u/app/src/a.kt"))
    }

    @Test fun aSiblingThatSharesAPrefixIsOutside() {
        assertNull(hostRelativePath("/home/u/app", "/home/u/apple/a.kt"))
        assertNull(hostRelativePath("C:\\app", "C:\\apple\\a.kt"))
    }

    @Test fun anotherDriveIsOutside() {
        assertNull(hostRelativePath("C:\\app", "D:\\app\\a.kt"))
    }

    @Test fun aRootHoldsEverythingOnIt() {
        assertEquals("home/u/a.kt", hostRelativePath("/", "/home/u/a.kt"))
        assertEquals("app/a.kt", hostRelativePath("c:\\", "C:\\app\\a.kt"))
    }

    @Test fun aRelativePathIsNotInsideAnAbsoluteRoot() {
        assertNull(hostRelativePath("/home/u/app", "home/u/app/a.kt"))
        assertNull(hostRelativePath("C:\\app", "app\\a.kt"))
    }

    @Test fun windowsPathsAreTheDrivesAndTheShares() {
        assertTrue(isWindowsHostPath("C:\\Users"))
        assertTrue(isWindowsHostPath("c:/users"))
        assertTrue(isWindowsHostPath("D:"))
        assertTrue(isWindowsHostPath("\\\\server\\share\\x"))
        assertFalse(isWindowsHostPath("/home/u"))
        assertFalse(isWindowsHostPath("src/C:x"))
        assertFalse(isWindowsHostPath("C:x"))
    }

    @Test fun windowsSegmentsCompareWithoutCaseAndPosixOnesExactly() {
        assertTrue(sameHostPath("C:\\Users\\Me", "c:/users/me/"))
        assertFalse(sameHostPath("/home/Me", "/home/me"))
        assertTrue(sameHostPath("/home/me/", "/home//me"))
    }
}
