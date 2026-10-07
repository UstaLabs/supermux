package dev.supermux.state

import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.Subagent
import dev.supermux.proto.SubagentStats
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostReducerSubagentsTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test fun snapshotSeedsSubagentsOrderedByStart() {
        val state = reduceHostFrame(
            HostState(),
            ServerFrame.Snapshot(subagents = mapOf("s1" to listOf(Subagent(id = "b", startedAt = 20), Subagent(id = "a", startedAt = 10)))),
        )
        assertEquals(listOf("a", "b"), state.subagents["s1"]!!.map { it.id })
    }

    @Test fun updateUpsertsTheFullViewById() {
        var state = reduceHostFrame(HostState(), ServerFrame.SubagentUpdate("s1", Subagent(id = "a", startedAt = 10)))
        state = reduceHostFrame(state, ServerFrame.SubagentUpdate("s1", Subagent(id = "b", startedAt = 5)))
        state = reduceHostFrame(
            state,
            ServerFrame.SubagentUpdate("s1", Subagent(id = "a", startedAt = 10, status = "completed", result = "done", stats = SubagentStats(toolCalls = 2))),
        )
        val list = state.subagents["s1"]!!
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals("completed", list[1].status)
        assertEquals(2, list[1].stats.toolCalls)
        assertFalse(list[1].running)
    }

    @Test fun clearedAndSessionRemovedDropTheList() {
        val seeded = reduceHostFrame(
            HostState(sessions = listOf(SessionInfo(id = "s1", name = "n", workdir = "/w", agent = "claude"))),
            ServerFrame.SubagentUpdate("s1", Subagent(id = "a")),
        )
        assertTrue("s1" !in reduceHostFrame(seeded, ServerFrame.SubagentsCleared("s1")).subagents)
        assertTrue("s1" !in reduceHostFrame(seeded, ServerFrame.SessionRemoved("s1")).subagents)
        val empty = HostState()
        assertSame(empty, reduceHostFrame(empty, ServerFrame.SubagentsCleared("zz")))
    }

    @Test fun wireFramesDecode() {
        val update = json.decodeFromString<ServerFrame>(
            """{"type":"subagent_update","session":"s1","subagent":{"id":"a1","status":"running","stats":{"toolCalls":1},"startedAt":1,"lastActivityAt":2,"messaging":"relay","parentCallId":"toolu_1"}}""",
        ) as ServerFrame.SubagentUpdate
        assertEquals("toolu_1", update.subagent.parentCallId)
        assertTrue(update.subagent.canMessage)
        assertEquals("a1", update.subagent.label)
        // An older broker: none of the new fields, still decodes.
        val snap = json.decodeFromString<ServerFrame>("""{"type":"snapshot"}""") as ServerFrame.Snapshot
        assertTrue(snap.subagents.isEmpty())
    }

    @Test fun newFieldsDecodeInSnapshot() {
        val frame = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","subagents":{"s1":[{"id":"a","name":"Anscombe","status":"cancelled","endedBy":"client",
            "canMessage":false,"canStop":false,"actionsSource":"derived","cannotMessageReason":"Stopped by you",
            "cannotStopReason":"It has already finished","replies":4,"messaging":"direct"}]}}""",
        )
        val a = reduceHostFrame(HostState(), frame).subagents["s1"]!!.single()
        assertEquals("client", a.endedBy)
        assertFalse(a.canMessage)
        assertFalse(a.canStop)
        assertEquals("derived", a.actionsSource)
        assertEquals("Stopped by you", a.cannotMessageReason)
        assertEquals("It has already finished", a.cannotStopReason)
        assertEquals(1, a.unreadReplies(3))
        assertEquals(0, a.unreadReplies(9))
    }

    @Test fun updateReplacesActionFlags() {
        var state = reduceHostFrame(HostState(), ServerFrame.SubagentUpdate("s1", Subagent(id = "a", messaging = "direct", canMessageFlag = true, canStopFlag = true)))
        assertTrue(state.subagents["s1"]!!.single().canMessage)
        state = reduceHostFrame(
            state,
            ServerFrame.SubagentUpdate("s1", Subagent(id = "a", status = "cancelled", messaging = "direct", canMessageFlag = false, cannotMessageReason = "Stopped by you", canStopFlag = false)),
        )
        val a = state.subagents["s1"]!!.single()
        assertFalse(a.canMessage)
        assertEquals("Stopped by you", a.cannotMessageReason)
        assertFalse(a.canStop)
    }

    @Test fun olderBrokerFallsBackToMessagingAndRunning() {
        val relay = Subagent(id = "a", messaging = "relay")
        assertTrue(relay.canMessage)
        assertTrue(relay.canStop)
        val done = Subagent(id = "b", messaging = "none", status = "completed")
        assertFalse(done.canMessage)
        assertFalse(done.canStop)
        assertEquals(0, done.unreadReplies(0))
    }
}
