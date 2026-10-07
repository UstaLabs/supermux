package dev.supermux.state

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SlashCommand
import dev.supermux.proto.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The snapshot's trimmed logs (`logTail` / `partialLogs`) and the client's fully-loaded set. */
class HostReducerLogTailTest {
    private fun e(id: String, ts: String = id, text: String? = null) =
        LogEntry(id = id, ts = ts, direction = "outbound", text = text)

    private fun snapshot(logs: Map<String, List<LogEntry>>, partial: List<String>?) =
        ServerFrame.Snapshot(logs = logs, partialLogs = partial)

    @Test fun oldBrokerSnapshotMarksEverySessionComplete() {
        val out = reduceHostFrame(HostState(), snapshot(mapOf("a" to listOf(e("1"))), partial = null))
        assertEquals(listOf(e("1")), out.messages["a"])
        assertEquals(setOf("a"), out.completeLogs)
    }

    @Test fun partialSessionGetsItsTailAndIsNotComplete() {
        val out = reduceHostFrame(
            HostState(),
            snapshot(mapOf("a" to listOf(e("9")), "b" to listOf(e("1"), e("2"))), partial = listOf("a")),
        )
        assertEquals(listOf(e("9")), out.messages["a"])
        assertEquals(setOf("b"), out.completeLogs)
    }

    @Test fun reconnectKeepsALoadedLogWhenNothingNewArrived() {
        val loaded = HostState(messages = mapOf("a" to listOf(e("1"), e("2"), e("3"))), completeLogs = setOf("a"))
        val out = reduceHostFrame(loaded, snapshot(mapOf("a" to listOf(e("3", text = "edited"))), partial = listOf("a")))
        assertEquals(listOf(e("1"), e("2"), e("3", text = "edited")), out.messages["a"])
        assertTrue("a" in out.completeLogs)
    }

    @Test fun reconnectDropsALoadedLogThatMissedMessages() {
        val loaded = HostState(messages = mapOf("a" to listOf(e("1"), e("2"))), completeLogs = setOf("a"))
        val out = reduceHostFrame(loaded, snapshot(mapOf("a" to listOf(e("5"))), partial = listOf("a")))
        assertEquals(listOf(e("5")), out.messages["a"])
        assertFalse("a" in out.completeLogs)
    }

    @Test fun snapshotPrunesSessionsItNoLongerCarries() {
        val loaded = HostState(messages = mapOf("gone" to listOf(e("1"))), completeLogs = setOf("gone"))
        val out = reduceHostFrame(loaded, snapshot(mapOf("a" to listOf(e("2"))), partial = listOf("a")))
        assertEquals(setOf("a"), out.messages.keys)
        assertTrue(out.completeLogs.isEmpty())
    }

    @Test fun sessionRemovedForgetsTheLoadedLog() {
        val loaded = HostState(messages = mapOf("a" to listOf(e("1"))), completeLogs = setOf("a"))
        val out = reduceHostFrame(loaded, ServerFrame.SessionRemoved(id = "a"))
        assertFalse("a" in out.completeLogs)
    }

    @Test fun fetchedLogKeepsLiveEntriesThatArrivedDuringTheFetch() {
        val fetched = listOf(e("1"), e("2"))
        val current = listOf(e("2"), e("3"), e("local-0", ts = "4"))
        assertEquals(listOf(e("1"), e("2"), e("3"), e("local-0", ts = "4")), mergeFetchedLog(fetched, current))
    }

    @Test fun fetchedLogDropsCurrentEntriesOlderThanItsNewest() {
        // The tail entry the snapshot seeded is inside the fetched page; nothing is duplicated.
        assertEquals(listOf(e("1"), e("2")), mergeFetchedLog(listOf(e("1"), e("2")), listOf(e("2"))))
    }

    private fun act(seq: Int) = ActivityEvent(ts = "t$seq", kind = "tool", seq = seq)
    private val cmd = SlashCommand(id = "c", family = "agent", name = "review")

    @Test fun oldBrokerSnapshotMarksEveryChatsExtrasCurrent() {
        val out = reduceHostFrame(HostState(), ServerFrame.Snapshot(logs = mapOf("a" to emptyList()), activity = mapOf("a" to listOf(act(1)))))
        assertEquals(setOf("a"), out.completeExtras)
    }

    @Test fun trimmedExtrasAreNotCurrentButWhatWeHeldIsKeptOnScreen() {
        val held = HostState(
            activity = mapOf("a" to listOf(act(1))),
            commands = mapOf("a" to listOf(cmd)),
            commandsResolved = mapOf("a" to true),
            completeExtras = setOf("a"),
        )
        val out = reduceHostFrame(
            held,
            ServerFrame.Snapshot(
                logs = mapOf("a" to emptyList(), "b" to emptyList()),
                activity = mapOf("b" to listOf(act(2))),
                partialExtras = listOf("a"),
            ),
        )
        assertEquals(setOf("b"), out.completeExtras)
        assertEquals(listOf(act(1)), out.activity["a"])
        assertEquals(listOf(cmd), out.commands["a"])
        assertEquals(true, out.commandsResolved["a"])
        assertEquals(listOf(act(2)), out.activity["b"])
    }

    @Test fun sessionRemovedForgetsCurrentExtras() {
        val out = reduceHostFrame(HostState(completeExtras = setOf("a")), ServerFrame.SessionRemoved(id = "a"))
        assertFalse("a" in out.completeExtras)
    }

    @Test fun fetchedActivityKeepsLiveAppendsNewerThanIt() {
        assertEquals(
            listOf(act(1), act(2), act(3)),
            mergeFetchedActivity(listOf(act(1), act(2)), listOf(act(2), act(3))),
        )
        assertEquals(listOf(act(4)), mergeFetchedActivity(emptyList(), listOf(act(4))))
    }
}
