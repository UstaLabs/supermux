package dev.supermux.host

import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** Card #121: which chats' recent messages the offline cache keeps, and how much of each. */
class RecentLogsTest {
    private fun e(id: String, ts: String = id, text: String? = null) = LogEntry(id = id, ts = ts, direction = "outbound", text = text)
    private fun s(id: String) = SessionInfo(id = id, name = id, agent = "claude", workdir = "/w")

    @Test fun keepsTheMostRecentlyActiveChats() {
        val logs = mapOf("old" to listOf(e("1")), "new" to listOf(e("9")), "mid" to listOf(e("5")))
        val out = recentLogs(listOf(s("old"), s("new"), s("mid")), logs, chats = 2, perChat = 30, maxText = 100)
        assertEquals(setOf("new", "mid"), out.keys)
    }

    @Test fun keepsOnlyTheNewestEntriesOfEachChat() {
        val out = recentLogs(listOf(s("a")), mapOf("a" to listOf(e("1"), e("2"), e("3"))), chats = 20, perChat = 2, maxText = 100)
        assertEquals(listOf(e("2"), e("3")), out["a"])
    }

    @Test fun leavesOutLocalEchoesAndChatsOfOtherHosts() {
        val logs = mapOf("a" to listOf(e("1"), e("local-x", ts = "2")), "elsewhere" to listOf(e("3")))
        val out = recentLogs(listOf(s("a")), logs, chats = 20, perChat = 30, maxText = 100)
        assertEquals(mapOf("a" to listOf(e("1"))), out)
    }

    @Test fun capsALongMessage() {
        val out = recentLogs(listOf(s("a")), mapOf("a" to listOf(e("1", text = "x".repeat(50)))), chats = 20, perChat = 30, maxText = 10)
        assertEquals("x".repeat(10), out.getValue("a").single().text)
    }
}

/** Card #121: what a storage with a quota (the browser's) falls back to when the cache does not fit. */
class SnapshotShrinkTest {
    private fun e(id: String) = LogEntry(id = id, ts = id, direction = "outbound")

    @Test fun eachStepHalvesTheCachedChatsUntilNoneAreLeft() {
        val snap = HostSnapshot("h", recentLogs = mapOf("a" to listOf(e("4")), "b" to listOf(e("3")), "c" to listOf(e("2")), "d" to listOf(e("1"))))
        val steps = generateSequence(listOf(snap)) { shrinkRecentLogs(it) }.map { it.single().recentLogs.keys }.toList()
        assertEquals(listOf(setOf("a", "b", "c", "d"), setOf("a", "b"), setOf("a"), emptySet()), steps)
    }
}
