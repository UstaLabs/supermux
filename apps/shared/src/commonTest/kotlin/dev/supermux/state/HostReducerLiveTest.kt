package dev.supermux.state

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LiveStreamDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Card #76: live replies in the host state. */
class HostReducerLiveTest {
    private fun delta(at: Int, text: String, stream: String = "k1", kind: String = "reply", session: String = "s1") =
        ServerFrame.ReplyStream(session = session, stream = stream, kind = kind, at = at, text = text)

    private fun HostState.text(session: String = "s1", stream: String = "k1") = live[session]?.firstOrNull { it.stream == stream }?.text

    @Test fun deltasAppendByOffset() {
        var s = reduceHostFrame(HostState(), delta(0, "Hel"))
        s = reduceHostFrame(s, delta(3, "lo"))
        assertEquals("Hello", s.text())
        // A frame sent twice (at < length) adds only what is new.
        s = reduceHostFrame(s, delta(3, "lo there"))
        assertEquals("Hello there", s.text())
        // at 0 is the whole text so far.
        s = reduceHostFrame(s, delta(0, "Hello there!"))
        assertEquals("Hello there!", s.text())
    }

    @Test fun aGapWaitsForTheWholeText() {
        val s = reduceHostFrame(HostState(), delta(0, "ab"))
        assertSame(s, reduceHostFrame(s, delta(5, "zz")))
        val fresh = HostState()
        assertSame(fresh, reduceHostFrame(fresh, delta(4, "late")))
    }

    @Test fun thinkingAndReplyLiveSideBySideThinkingFirst() {
        var s = reduceHostFrame(HostState(), delta(0, "answer", stream = "k1"))
        s = reduceHostFrame(s, delta(0, "plan", stream = "t1", kind = "thinking"))
        assertEquals(listOf("t1", "k1"), s.live["s1"]!!.map { it.stream })
        // A new stream of a kind replaces the last of that kind.
        s = reduceHostFrame(s, delta(0, "next", stream = "k2"))
        assertEquals(listOf("t1", "k2"), s.live["s1"]!!.map { it.stream })
    }

    @Test fun theStoredMessageReplacesItsLiveReplyInOneStep() {
        val s = reduceHostFrame(HostState(), delta(0, "Hello"))
        val entry = LogEntry(id = "out:web:1", ts = "t", direction = "outbound", text = "Hello", stream = "k1")
        val next = reduceHostFrame(s, ServerFrame.MessageAppend("s1", entry))
        assertEquals(null, next.live["s1"])
        assertEquals(listOf(entry), next.messages["s1"])
    }

    @Test fun theStoredThinkingReplacesTheLiveThinking() {
        val s = reduceHostFrame(HostState(), delta(0, "Plan", stream = "t1", kind = "thinking"))
        val row = ActivityEvent(ts = "t", kind = "reasoning", title = "Thinking", detail = "Plan", stream = "t1")
        assertEquals(null, reduceHostFrame(s, ServerFrame.ActivityAppend("s1", row)).live["s1"])
    }

    @Test fun anEndDropsItAndOtherSessionsKeepTheirs() {
        var s = reduceHostFrame(HostState(), delta(0, "a"))
        s = reduceHostFrame(s, delta(0, "b", session = "s2", stream = "k9"))
        s = reduceHostFrame(s, ServerFrame.ReplyStreamEnd("s1", "k1"))
        assertEquals(null, s.live["s1"])
        assertEquals("b", s.text("s2", "k9"))
    }

    @Test fun theSnapshotIsTheTruth() {
        val s = reduceHostFrame(HostState(), delta(0, "stale"))
        val snap = reduceHostFrame(s, ServerFrame.Snapshot(streams = mapOf("s2" to listOf(LiveStreamDto("k5", "reply", "so far")))))
        assertEquals(null, snap.live["s1"])
        assertEquals("so far", snap.text("s2", "k5"))
    }
}
