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

    @Test fun windowsCaseFoldingIsOrdinalLikeNtfsDottedIIsNotI() {
        // NTFS upper-cases one character at a time with its own table: 'i' and 'İ' (U+0130) are two names.
        // String.equals(ignoreCase = true) calls them equal, because it also compares the lower-case forms.
        assertFalse(sameHostPath("C:\\proj\\i.txt", "C:\\proj\\\u0130.txt"))
        assertTrue(sameHostPath("C:\\proj\\FILE", "c:\\PROJ\\file"))
    }

    @Test fun leafOnEitherSeparator() {
        assertEquals("app", pathLeaf("C:\\Users\\me\\app\\"))
        assertEquals("x", pathLeaf("/home/u/x/"))
        assertEquals("TunnelWho", pathLeaf("D:\\Projects\\TunnelWho"))
        assertEquals("erydor", pathLeaf("…\\yasinemre\\erydor"))
        assertEquals("plain", pathLeaf("plain"))
    }

    @Test fun parentStaysInThePathsOwnSpellingAndStopsAtARoot() {
        assertEquals("C:\\Users", hostParent("C:\\Users\\me"))
        assertEquals("C:\\", hostParent("C:\\Users"))
        assertNull(hostParent("C:\\"))
        assertEquals("/home", hostParent("/home/me"))
        assertEquals("/", hostParent("/home"))
        assertNull(hostParent("/"))
        assertEquals("\\\\server\\share", hostParent("\\\\server\\share\\app"))
        assertNull(hostParent("\\\\server\\share"))
        assertEquals("C:/Users", hostParent("C:/Users/me"))
    }

    @Test fun joinUsesTheRootsSeparatorForABrokerRelativePath() {
        assertEquals("C:\\Users\\me\\app\\src\\Main.kt", hostJoin("C:\\Users\\me\\app", "src/Main.kt"))
        assertEquals("C:\\app\\a.kt", hostJoin("C:\\app\\", "a.kt"))
        assertEquals("/home/me/app/src/Main.kt", hostJoin("/home/me/app", "src/Main.kt"))
        assertEquals("/home/me/app", hostJoin("/home/me/app", ""))
    }

    @Test fun normaliseResolvesDotsAndSeparatorsInTheHostsStyle() {
        assertEquals("C:\\Users\\me\\proj\\src", normalizeHostPath("c:\\Users\\me\\proj/./lib/../src/"))
        assertEquals("C:\\", normalizeHostPath("C:/"))
        assertEquals("/a/c", normalizeHostPath("/a//b/../c/"))
        assertEquals("/", normalizeHostPath("/"))
        assertEquals("\\\\server\\share\\x", normalizeHostPath("\\\\server\\share\\y\\..\\x"))
    }

    @Test fun fileUriFollowsTheHost() {
        assertEquals("file:///C:/Users/me/my%20app/a.kt", hostFileUri("C:\\Users\\me\\my app\\a.kt"))
        assertEquals("file:///home/me/a%23b.kt", hostFileUri("/home/me/a#b.kt"))
        assertEquals("file://server/share/a.kt", hostFileUri("\\\\server\\share\\a.kt"))
    }

    @Test fun inferHomeDirReadsBothHosts() {
        assertEquals("C:\\Users\\me", inferHomeDir("C:\\Users\\me\\proj"))
        assertEquals("/home/me", inferHomeDir("/home/me/proj"))
        assertNull(inferHomeDir("/srv/proj"))
    }
}
