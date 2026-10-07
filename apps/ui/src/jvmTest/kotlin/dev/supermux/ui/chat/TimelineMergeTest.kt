package dev.supermux.ui.chat

import dev.supermux.chat.mergeTimeline
import dev.supermux.proto.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fold itself is tested in `:shared` (`chat/TimelineMergeTest.kt`). This is the smoke that the
 * shared fold is what the shared timeline resolves — the per-app `mergeTimeline` re-exports both
 * apps carried are gone as of cluster D2.
 */
class TimelineMergeTest {
    @Test fun sharedMergeIsVisibleToTheSharedTimeline() {
        val items = mergeTimeline(
            listOf(LogEntry(id = "1", ts = "2026-01-01T00:00:01Z", direction = "outbound", text = "hi")),
            emptyList(),
        )
        assertEquals(1, items.size)
    }
}
