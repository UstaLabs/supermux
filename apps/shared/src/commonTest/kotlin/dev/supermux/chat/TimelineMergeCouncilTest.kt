package dev.supermux.chat

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilView
import dev.supermux.proto.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Councils in [mergeTimeline]: one card per council, its forwarded reports folded into it. */
class TimelineMergeCouncilTest {
    private fun council(id: String = "c1", callId: String? = null, createdAt: String = "2026-10-30T12:00:05.000Z") = CouncilView(
        id = id,
        name = "council-1",
        question = "Which?",
        callId = callId,
        createdAt = createdAt,
        members = listOf(
            CouncilMember(letter = "A", session = "ma", agent = "codex", label = "GPT-6", status = "reported"),
            CouncilMember(letter = "B", session = "mb", agent = "grok", label = "Grok", status = "failed"),
        ),
    )

    private val ask = LogEntry(id = "m1", ts = "2026-10-30T12:00:00.000Z", direction = "inbound", text = "ask a council")
    private fun report(id: String, ts: String, councilId: String = "c1") = LogEntry(
        id = id, ts = ts, direction = "inbound", channel = "inbox", text = "council-1 · round 1 · A — GPT-6 [a:ma]:\nPostgres.",
        from_session_id = councilId, council_id = councilId, council_round = 1, council_letter = "A",
    )
    private val summary = LogEntry(id = "m9", ts = "2026-10-30T12:09:00.000Z", direction = "outbound", text = "A says Postgres.")
    private val call = ActivityEvent(ts = "2026-10-30T12:00:02.000Z", kind = "tool", tool = "council", title = "council", phase = "started", callId = "toolu_c")
    private val callResult = ActivityEvent(ts = "2026-10-30T12:00:03.000Z", kind = "tool_result", title = "done", phase = "completed", callId = "toolu_c")

    @Test fun theCardTakesThePlaceOfItsCouncilToolCall() {
        val items = mergeTimeline(
            listOf(ask, report("r1", "2026-10-30T12:05:00.000Z"), summary),
            listOf(call, callResult),
            councils = listOf(council(callId = "toolu_c")),
        )
        assertEquals(3, items.size)
        val card = items[1] as TimelineItem.CouncilCard
        assertEquals("c1", card.council.id)
        assertEquals("toolu_c", card.call!!.event.callId)
        assertEquals(ToolStatus.DONE, card.call!!.status)
        assertEquals(call.ts, card.sortTs)
        assertEquals(listOf("m1", "c1", "m9"), items.map { it.idForTest() })
    }

    @Test fun theCallIsStillSwallowedWhenToolsAreHidden() {
        val items = mergeTimeline(listOf(ask), listOf(call), hideTools = true, councils = listOf(council(callId = "toolu_c")))
        assertEquals(listOf("m1", "c1"), items.map { it.idForTest() })
        assertTrue((items[1] as TimelineItem.CouncilCard).call != null)
    }

    @Test fun withoutACallTheCardSitsAtItsFirstForwardedReport() {
        val items = mergeTimeline(
            listOf(ask, report("r2", "2026-10-30T12:07:00.000Z"), report("r1", "2026-10-30T12:05:00.000Z"), summary),
            emptyList(),
            councils = listOf(council(createdAt = "2026-10-30T12:10:00.000Z")),
        )
        assertEquals(listOf("m1", "c1", "m9"), items.map { it.idForTest() })
        assertEquals("2026-10-30T12:05:00.000Z", items[1].sortTs)
        assertNull((items[1] as TimelineItem.CouncilCard).call)
    }

    @Test fun withNeitherTheCardSitsAtItsCreation() {
        val items = mergeTimeline(listOf(ask, summary), emptyList(), councils = listOf(council()))
        assertEquals(listOf("m1", "c1", "m9"), items.map { it.idForTest() })
        assertEquals("2026-10-30T12:00:05.000Z", items[1].sortTs)
    }

    @Test fun aReportOfAnUnknownCouncilStaysARow() {
        val items = mergeTimeline(listOf(ask, report("r1", "2026-10-30T12:05:00.000Z", councilId = "gone")), emptyList(), councils = listOf(council()))
        assertEquals(listOf("m1", "c1", "r1"), items.map { it.idForTest() })
    }

    @Test fun aMemberErrorNoticeIsTheCardsFailureReason() {
        val notice = LogEntry(
            id = "n1", ts = "2026-10-30T12:06:00.000Z", direction = "inbound", channel = "inbox", from_session_id = "supermux",
            text = "council-1 · B [council-1 B:mb] stopped with an error:\nAPI error (status 402 Payment Required)",
        )
        val other = notice.copy(id = "n2", text = "council-9 · B [x:mb] stopped with an error:\nnot ours")
        val items = mergeTimeline(listOf(ask, notice, other), emptyList(), councils = listOf(council()))
        val card = items.filterIsInstance<TimelineItem.CouncilCard>().single()
        assertEquals(mapOf("B" to "API error (status 402 Payment Required)"), card.failures)
        // The notice itself stays in the chat.
        assertTrue(items.any { it is TimelineItem.Msg && it.entry.id == "n1" })
    }

    private fun TimelineItem.idForTest(): String = when (this) {
        is TimelineItem.Msg -> entry.id
        is TimelineItem.CouncilCard -> council.id
        is TimelineItem.Tool -> event.callId.orEmpty()
        else -> toString()
    }
}
