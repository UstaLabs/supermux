package dev.supermux.state

import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilView
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostReducerCouncilsTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private fun council(id: String, round: Int = 1, createdAt: String = "2026-10-30T12:00:00.000Z", status: String = "working") = CouncilView(
        id = id,
        name = "council-$id",
        question = "Q $id",
        round = round,
        createdAt = createdAt,
        members = listOf(CouncilMember(letter = "A", session = "m-$id", name = "A", agent = "claude", label = "Claude", status = status)),
    )

    @Test fun snapshotSeedsCouncilsPerCallerOrderedByCreation() {
        val state = reduceHostFrame(
            HostState(),
            ServerFrame.Snapshot(councils = mapOf("s1" to listOf(council("b", createdAt = "2026-10-30T13:00:00.000Z"), council("a")))),
        )
        assertEquals(listOf("a", "b"), state.councils["s1"]!!.map { it.id })
    }

    @Test fun updateReplacesTheCouncilById() {
        var state = reduceHostFrame(HostState(), ServerFrame.CouncilUpdate("s1", council("a")))
        state = reduceHostFrame(state, ServerFrame.CouncilUpdate("s1", council("b", createdAt = "2026-10-30T13:00:00.000Z")))
        state = reduceHostFrame(state, ServerFrame.CouncilUpdate("s1", council("a", round = 2, status = "reported")))
        val list = state.councils["s1"]!!
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals(2, list[0].round)
        assertEquals("reported", list[0].members.single().status)
        // Another caller's councils are its own.
        state = reduceHostFrame(state, ServerFrame.CouncilUpdate("s2", council("c")))
        assertEquals(2, state.councils["s1"]!!.size)
        assertEquals(listOf("c"), state.councils["s2"]!!.map { it.id })
    }

    @Test fun sessionRemovedDropsItsCouncils() {
        val seeded = reduceHostFrame(
            HostState(sessions = listOf(SessionInfo(id = "s1", name = "n", workdir = "/w", agent = "claude"))),
            ServerFrame.CouncilUpdate("s1", council("a")),
        )
        assertTrue("s1" !in reduceHostFrame(seeded, ServerFrame.SessionRemoved("s1")).councils)
        val empty = HostState()
        assertSame(empty, reduceHostFrame(empty, ServerFrame.SessionRemoved("zz")))
    }

    @Test fun wireFramesDecode() {
        val update = json.decodeFromString<ServerFrame>(
            """{"type":"council_update","session":"s1","council":{"id":"c1","name":"council-1","question":"Which?","round":2,
            "focus":"cost","status":"open","callId":"toolu_9","createdAt":"2026-10-30T12:00:00.000Z",
            "members":[{"letter":"A","session":"m1","name":"council-1 A","agent":"codex","model":"gpt-6","label":"GPT-6","status":"reported"}],
            "rounds":[{"round":1,"startedAt":"2026-10-30T12:00:00.000Z","completedAt":"2026-10-30T12:05:00.000Z",
            "reports":[{"id":"r1","letter":"A","seq":1,"text":"Postgres.","at":"2026-10-30T12:03:00.000Z"}]},
            {"round":2,"focus":"cost","startedAt":"2026-10-30T12:05:00.000Z","reports":[]}]}}""",
        ) as ServerFrame.CouncilUpdate
        val c = update.council
        assertEquals("s1", update.session)
        assertEquals("toolu_9", c.callId)
        assertEquals("cost", c.focus)
        assertEquals("gpt-6", c.members.single().model)
        assertEquals("Postgres.", c.rounds[0].reports.single().text)
        assertNull(c.rounds[1].completedAt)
        // An older broker: no councils, still decodes.
        val snap = json.decodeFromString<ServerFrame>("""{"type":"snapshot"}""") as ServerFrame.Snapshot
        assertTrue(snap.councils.isEmpty())
        // A snapshot carries them per caller session.
        val withCouncils = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","councils":{"s1":[{"id":"c1","name":"council-1","question":"Which?","round":1,"status":"open","createdAt":"2026-10-30T12:00:00.000Z","members":[],"rounds":[]}]}}""",
        )
        assertEquals("c1", reduceHostFrame(HostState(), withCouncils).councils["s1"]!!.single().id)
    }
}
