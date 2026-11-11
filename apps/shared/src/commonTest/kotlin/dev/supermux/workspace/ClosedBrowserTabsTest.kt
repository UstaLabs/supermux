package dev.supermux.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #122: a Browser tab that leaves the workspace is remembered, newest first, so ⌘⇧T can bring it back. */
class ClosedBrowserTabsTest {
    private val groups = mapOf("b1" to "g1", "b2" to "g1", "b3" to "g2")

    @Test fun aTabThatLeavesIsRememberedWithItsPageAndPane() {
        val closed = ClosedBrowserTabs()
        closed.update(mapOf("b1" to "https://a.test/", "b2" to "https://b.test/"), groups::get)
        closed.update(mapOf("b2" to "https://b.test/"), groups::get)
        assertEquals(ClosedBrowserTab("https://a.test/", "g1"), closed.pop())
        assertNull(closed.pop())
    }

    @Test fun theNewestClosedComesBackFirst() {
        val closed = ClosedBrowserTabs()
        closed.update(mapOf("b1" to "https://a.test/", "b3" to "https://c.test/"), groups::get)
        closed.update(mapOf("b3" to "https://c.test/"), groups::get)
        closed.update(emptyMap(), groups::get)
        assertEquals("https://c.test/", closed.pop()?.url)
        assertEquals("https://a.test/", closed.pop()?.url)
    }

    @Test fun aTabWithNoPageIsNotWorthReopening() {
        val closed = ClosedBrowserTabs()
        closed.update(mapOf("b1" to null), groups::get)
        closed.update(emptyMap(), groups::get)
        assertNull(closed.pop())
    }

    @Test fun itKeepsTheLastTen() {
        val closed = ClosedBrowserTabs()
        val many = (1..12).associate { "t$it" to "https://$it.test/" }
        closed.update(many) { "g" }
        closed.update(emptyMap()) { "g" }
        var n = 0
        while (closed.pop() != null) n++
        assertEquals(10, n)
    }

    @Test fun thePageItLastShowedIsTheOneRemembered() {
        val closed = ClosedBrowserTabs()
        closed.update(mapOf("b1" to "https://a.test/"), groups::get)
        closed.update(mapOf("b1" to "https://a.test/next"), groups::get)
        closed.update(emptyMap(), groups::get)
        assertEquals("https://a.test/next", closed.pop()?.url)
    }
}
