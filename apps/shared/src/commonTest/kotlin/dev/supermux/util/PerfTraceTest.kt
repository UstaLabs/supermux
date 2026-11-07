package dev.supermux.util

import dev.supermux.proto.ClientFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Card #54: the app's half of the load-time trace. */
class PerfTraceTest {
    private fun unique(name: String) = "$name.${Random.nextInt(1_000_000)}"

    @Test fun anEventCarriesTheLaunchTraceUnlessGivenAnother() {
        val cursor = PerfTrace.cursor()
        val a = unique("test.a")
        val b = unique("test.b")
        PerfTrace.event(a, "n" to 1)
        PerfTrace.event(b, trace = "C1")
        val got = PerfTrace.since(cursor).filter { it.name == a || it.name == b }
        assertEquals(listOf(PerfTrace.launchId, "C1"), got.map { it.trace })
        assertEquals(1, got[0].fields["n"])
        assertTrue(got[0].t >= 0 && got[1].t >= got[0].t)
        assertTrue(PerfTrace.launchId.startsWith("L") && PerfTrace.launchId.length == 7)
    }

    @Test fun onceRecordsAMilestoneOnlyTheFirstTime() {
        val cursor = PerfTrace.cursor()
        val name = unique("test.once")
        PerfTrace.once(name, "n" to 1)
        PerfTrace.once(name, "n" to 2)
        assertEquals(listOf(1), PerfTrace.since(cursor).filter { it.name == name }.map { it.fields["n"] })
    }

    @Test fun theRingKeepsTheNewestEventsAndReadsOnFromACursor() {
        val ring = PerfRing(capacity = 3)
        for (i in 1..5) ring.add { seq -> PerfTrace.Event(seq, i.toLong(), "e$i", "L", emptyMap()) }
        assertEquals(listOf("e3", "e4", "e5"), ring.since(0).map { it.name })
        assertEquals(listOf("e5"), ring.since(4).map { it.name })
        assertEquals(listOf("e3", "e4"), ring.since(0, max = 2).map { it.name })
        assertEquals(5, ring.lastSeq())
    }

    @Test fun eachChatOpenGetsItsOwnTraceForThatSessionOnly() {
        val first = PerfTrace.startChat("s1")
        val second = PerfTrace.startChat("s1")
        assertNotEquals(first, second)
        assertTrue(second.startsWith("C"))
        assertEquals(second, PerfTrace.chatTrace("s1"))
        assertNull(PerfTrace.chatTrace("s2"))
    }

    @Test fun requestIdsAreUnique() {
        assertNotEquals(PerfTrace.nextReq(), PerfTrace.nextReq())
    }

    @Test fun statsAddUpPerTypeAndResetWhenTaken() {
        PerfTrace.takeStats()
        PerfTrace.countFrame("MessageAppend", chars = 300, decodeMicros = 1_500)
        PerfTrace.countFrame("MessageAppend", chars = 200, decodeMicros = 500)
        PerfTrace.countReduce("MessageAppend", micros = 250)
        PerfTrace.countRecompose("shell")
        PerfTrace.countRecompose("shell")
        val stats = PerfTrace.takeStats()!!
        assertEquals(mapOf("n" to 2, "chars" to 500L, "decodeMs" to 2.0, "maxDecodeMs" to 1.5), (stats["frames"] as Map<*, *>)["MessageAppend"])
        assertEquals(mapOf("n" to 1, "ms" to 0.3, "maxMs" to 0.3), (stats["reduce"] as Map<*, *>)["MessageAppend"])
        assertEquals(2, (stats["recompose"] as Map<*, *>)["shell"])
        assertNull(PerfTrace.takeStats())
    }

    @Test fun eventsBecomeAClientLogsFrameTheBrokerReads() {
        val e = PerfTrace.Event(9, 812, "ui.sessions.drawn", "L1", mapOf("sessions" to 150, "host" to "h", "skip" to null, "laps" to mapOf("a" to 1.5)))
        val frame = perfClientLogs(listOf(e), mapOf("t" to 900L, "launch" to "L1", "surface" to "desktop"))
        val json = Json.parseToJsonElement(Json.encodeToString(ClientFrame.serializer(), frame)).jsonObject
        assertEquals("client_logs", json["type"]!!.jsonPrimitive.content)
        assertEquals("900", json["meta"]!!.jsonObject["t"]!!.jsonPrimitive.content)
        val entry = Json.parseToJsonElement(json["entries"].toString()).let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject }
        assertEquals("perf", entry["category"]!!.jsonPrimitive.content)
        assertEquals("ui.sessions.drawn", entry["event"]!!.jsonPrimitive.content)
        assertEquals("812", entry["ts"]!!.jsonPrimitive.content)
        val data = entry["data"]!!.jsonObject
        assertEquals("L1", data["trace"]!!.jsonPrimitive.content)
        assertEquals("150", data["sessions"]!!.jsonPrimitive.content)
        assertEquals("1.5", data["laps"]!!.jsonObject["a"]!!.jsonPrimitive.content)
        assertTrue("skip" !in data)
    }

    @Test fun serverTimingGivesTheBrokersOwnTime() {
        assertEquals(12.5, serverTimingMs("app;dur=12.5"))
        assertEquals(3.0, serverTimingMs("db;dur=1, app;dur=3"))
        assertNull(serverTimingMs(null))
        assertNull(serverTimingMs("cache;desc=hit"))
    }

    /** Card #75: one trace per launcher Send, which the new session's frames and draws join. */
    @Test fun aSessionStartFollowsItsSessionByIdOrByItsFolderAndEnds() {
        val cursor = PerfTrace.cursor()
        val trace = PerfTrace.beginStart("/repo/app", "agent" to "claude")
        assertTrue(trace.startsWith("S") && trace.contains(PerfTrace.launchId.drop(1)))
        assertEquals("start.send", PerfTrace.since(cursor).last { it.trace == trace }.name)
        assertEquals(trace, PerfTrace.pendingStart())
        assertTrue(PerfTrace.startActive)
        // Another session's frames are not this start's; the new one is known by its repo root.
        assertNull(PerfTrace.startTraceFor("other", workdir = "/elsewhere", repoRoot = "/elsewhere"))
        assertEquals(trace, PerfTrace.startTraceFor("s1", workdir = "/wt/uuid", repoRoot = "/repo/app"))
        assertEquals(trace, PerfTrace.startTraceFor("s1"))
        // Bound: no longer pending, and a second new session in the same folder is not it.
        assertNull(PerfTrace.pendingStart())
        assertNull(PerfTrace.startTraceFor("s2", repoRoot = "/repo/app"))
        PerfTrace.endStart(trace)
        assertNull(PerfTrace.startTraceFor("s1"))
        assertTrue(!PerfTrace.startActive)
    }

    @Test fun aStartBoundByItsResponseUsesTheResolvedFolder() {
        val trace = PerfTrace.beginStart("~/repo")
        PerfTrace.startResolved(trace, "/home/u/repo")
        assertEquals(trace, PerfTrace.startTraceFor("s9", repoRoot = "/home/u/repo"))
        val next = PerfTrace.beginStart("/home/u/other")
        assertNotEquals(trace, next)
        PerfTrace.bindStart(next, "s10")
        assertEquals(next, PerfTrace.startTraceFor("s10"))
        assertNull(PerfTrace.startTraceFor("s9"))
        PerfTrace.endStart(next)
    }
}
