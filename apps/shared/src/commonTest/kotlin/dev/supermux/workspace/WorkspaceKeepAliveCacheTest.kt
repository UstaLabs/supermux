package dev.supermux.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceKeepAliveCacheTest {
    @Test
    fun selectedWorkspaceIsMostRecentAndNeverDuplicated() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)

        assertEquals(listOf("w1"), cache.update("w1", setOf("w1", "w2")))
        assertEquals(listOf("w1", "w2"), cache.update("w2", setOf("w1", "w2")))
        assertEquals(listOf("w2", "w1"), cache.update("w1", setOf("w1", "w2")))
    }

    @Test
    fun eleventhWorkspaceEvictsTheLeastRecentlyViewed() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        val live = (1..11).map { "w$it" }.toSet()

        (1..11).forEach { cache.update("w$it", live) }

        assertEquals((2..11).map { "w$it" }, cache.update("w11", live))
    }

    @Test
    fun removedWorkspacesArePrunedImmediately() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        cache.update("w1", setOf("w1", "w2"))
        cache.update("w2", setOf("w1", "w2"))

        assertEquals(listOf("w2"), cache.update("w2", setOf("w2")))
    }

    @Test
    fun extraRetainIdsAreNotEvictedByAnEleventhVisit() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        val live = (1..11).map { "w$it" }.toSet()
        (1..10).forEach { cache.update("w$it", live) }
        val kept = cache.preview("w11", live, extraIds = setOf("w1"))
        assertTrue("w1" in kept)
        assertTrue("w11" in kept)
        assertEquals(10, kept.size)
    }

    @Test
    fun previewDoesNotMutateRetentionUntilCommitted() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        val live = setOf("w1", "w2")
        cache.update("w1", live)

        val candidate = cache.preview("w2", live)

        assertEquals(listOf("w1", "w2"), candidate)
        assertEquals(
            listOf("w1"),
            cache.preview(activeWorkspaceId = null, liveWorkspaceIds = live),
        )

        cache.commit(candidate)

        assertEquals(
            listOf("w1", "w2"),
            cache.preview(activeWorkspaceId = null, liveWorkspaceIds = live),
        )
    }
}
