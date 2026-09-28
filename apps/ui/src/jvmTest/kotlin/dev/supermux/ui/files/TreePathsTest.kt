package dev.supermux.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreePathsTest {
    @Test fun parentAndChild() {
        assertEquals("/a/b", parentOf("/a/b/c"))
        assertEquals("/", parentOf("/a"))
        assertNull(parentOf("/"))
        assertEquals("/a/b", childOf("/a", "b"))
        assertEquals("/b", childOf("/", "b"))
    }

    @Test fun within() {
        assertTrue(isWithin("/w", "/w"))
        assertTrue(isWithin("/w", "/w/src/a.kt"))
        assertFalse(isWithin("/w", "/work/a.kt"))
        assertTrue(isWithin("/", "/anything"))
    }

    @Test fun ancestorsWithinRootExcludeThePathItself() {
        assertEquals(listOf("/w", "/w/src", "/w/src/ui"), ancestorsWithin("/w", "/w/src/ui/A.kt"))
        assertEquals(emptyList(), ancestorsWithin("/w", "/other/A.kt"))
    }

    @Test fun relativeToWorkdir() {
        assertEquals("src/a.kt", relativeToWorkdir("/w", "/w/src/a.kt"))
        assertNull(relativeToWorkdir("/w", "/x/a.kt"))
        assertEquals(".", relativeToWorkdir("/w", "/w"))
        assertEquals("a.kt", relativeToWorkdir("/w/", "/w/a.kt"))
    }

    @Test fun displayName() {
        assertEquals("a.kt", displayName("/w/a.kt"))
        assertEquals("/", displayName("/"))
    }
}
