package dev.supermux.desktop.chat

// Tests moved to shared: apps/shared/.../chat/TimelineMergeTest.kt
// Re-export nothing — keep this file as a pointer so old IDE bookmarks land somewhere useful.

import dev.supermux.chat.mergeTimeline
import dev.supermux.proto.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke that desktop still resolves the shared fold on the jvm test classpath. */
class TimelineMergeTest {
    @Test fun sharedMergeIsVisibleToDesktopTests() {
        val items = mergeTimeline(
            listOf(LogEntry(id = "1", ts = "2026-01-01T00:00:01Z", direction = "outbound", text = "hi")),
            emptyList(),
        )
        assertEquals(1, items.size)
    }
}
