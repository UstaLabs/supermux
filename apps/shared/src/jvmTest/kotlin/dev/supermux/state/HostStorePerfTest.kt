package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.util.PerfTrace
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Card #54: a broker with tracing on gets this app's timings, and its requests carry the trace. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostStorePerfTest {
    @AfterTest fun reset() { PerfTrace.shipping = false }

    private class Harness(val app: HostStore, val sent: MutableList<ClientFrame>, val headers: MutableList<Pair<String, String?>>)

    private fun harness(): Harness {
        val sent = Collections.synchronizedList(mutableListOf<ClientFrame>())
        val headers = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val engine = MockEngine { req ->
            headers.add(req.url.encodedPath to req.headers["x-mux-trace"])
            val body = when {
                req.url.encodedPath.endsWith("/chat-extras") -> """{"activity":[],"commands":[],"commandsResolved":true}"""
                req.url.encodedPath.endsWith("/messages") -> """[{"id":"1","ts":"1","direction":"outbound"}]"""
                else -> "{}"
            }
            respond(
                ByteReadChannel(body),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "server-timing" to listOf("app;dur=4.5")),
            )
        }
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { sent.add(it) },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        return Harness(app, sent, headers)
    }

    private fun shippedNames(sent: List<ClientFrame>): List<String> =
        sent.filterIsInstance<ClientFrame.ClientLogs>().flatMap { f -> f.entries.map { it.event } }

    @Test fun aSnapshotThatAsksForTheTraceGetsTheEventsSoFar() {
        val h = harness()
        val marker = "test.marker.${Random.nextInt(1_000_000)}"
        PerfTrace.event(marker)

        h.app.reduce(ServerFrame.Snapshot(perf = true))

        assertTrue(marker in shippedNames(h.sent), "the launch events reach the broker")
        assertTrue("state.snapshot.reduced" in shippedNames(h.sent), "including the snapshot's own")
        val logs = h.sent.filterIsInstance<ClientFrame.ClientLogs>().first()
        assertEquals(PerfTrace.launchId, logs.meta!!["launch"]!!.jsonPrimitive.content)
        assertTrue(PerfTrace.shipping)
    }

    @Test fun aSnapshotWithoutTheFlagShipsNothing() {
        val h = harness()
        h.app.reduce(ServerFrame.Snapshot())
        assertTrue(h.sent.none { it is ClientFrame.ClientLogs })
    }

    @Test fun openingAChatFetchesItsHistoryUnderTheChatTrace() {
        val h = harness()
        h.app.reduce(ServerFrame.Snapshot(perf = true, logs = mapOf("a" to listOf(LogEntry("1", "1", "outbound"))), partialLogs = listOf("a")))
        val trace = PerfTrace.startChat("a")
        val cursor = PerfTrace.cursor()

        h.app.ensureMessagesLoaded("a")
        runBlocking { withTimeout(5_000) { h.app.state.first { "a" in it.completeLogs && "a" in it.completeExtras } } }
        // loadChat records chat.loaded in its finally, AFTER the state shows the chat complete and
        // on the store's own scope: wait for the event too, or the assertions below race it.
        runBlocking {
            withTimeout(5_000) {
                while (PerfTrace.since(cursor).none { it.trace == trace && it.name == "chat.loaded" }) delay(10)
            }
        }

        val history = h.headers.first { it.first == "/sessions/a/messages" }.second
        assertNotNull(history)
        assertTrue(history.startsWith("$trace/"), "history request carries the chat trace: $history")
        val events = PerfTrace.since(cursor).filter { it.trace == trace }
        val http = events.first { it.name == "http" && it.fields["path"] == "/sessions/a/messages" }
        assertEquals(200, http.fields["status"])
        assertEquals(4.5, http.fields["serverMs"])
        assertTrue(events.any { it.name == "http.body" && it.fields["req"] == http.fields["req"] }, "the body's decode joins the request")
        assertTrue(events.any { it.name == "chat.loaded" && it.fields["messages"] == 1 })
    }

    @Test fun withTracingOffRequestsCarryNoTraceHeader() {
        val h = harness()
        h.app.reduce(ServerFrame.Snapshot(logs = mapOf("a" to listOf(LogEntry("1", "1", "outbound"))), partialLogs = listOf("a")))
        h.app.ensureMessagesLoaded("a")
        runBlocking { withTimeout(5_000) { h.app.state.first { "a" in it.completeLogs } } }
        assertNull(h.headers.first { it.first == "/sessions/a/messages" }.second)
    }

    @Test fun theSubscribeFrameNamesTheLaunchAndTheRequest() {
        val json = Json.parseToJsonElement(subscribeFrameJson(listOf("a"), trace = "L1", req = "w3")).jsonObject
        assertEquals("L1", json["trace"]!!.jsonPrimitive.content)
        assertEquals("w3", json["req"]!!.jsonPrimitive.content)
        assertEquals("subscribe", json["type"]!!.jsonPrimitive.content)
    }
}
