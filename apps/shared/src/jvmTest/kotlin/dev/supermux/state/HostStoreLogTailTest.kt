package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Loading a session's history when the snapshot only carried its tail. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreLogTailTest {
    private fun e(id: String) = LogEntry(id = id, ts = id, direction = "outbound")

    private class Harness(val app: HostStore, val scope: TestScope, val fetched: MutableList<String>) {
        /** The mock engine answers on its own thread; wait for the store to reach [done]. */
        fun awaitState(done: (HostState) -> Boolean) = runBlocking {
            withTimeout(5_000) { app.state.first(done) }
        }
    }

    /** A HostStore whose GET /sessions/<id>/messages answers [status] with entries "1" and "2". */
    private fun harness(status: HttpStatusCode = HttpStatusCode.OK): Harness {
        val fetched = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { req ->
            // Only history fetches: a snapshot also refreshes the launcher's model catalog.
            if (req.url.encodedPath.startsWith("/sessions/")) fetched.add(req.url.encodedPath)
            val body = if (req.url.encodedPath.endsWith("/chat-extras")) {
                """{"activity":[{"ts":"1","kind":"tool","seq":7}],"commands":[{"id":"c","family":"agent","name":"review"}],"commandsResolved":true}"""
            } else {
                """[{"id":"1","ts":"1","direction":"outbound"},{"id":"2","ts":"2","direction":"outbound"}]"""
            }
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val scope = TestScope(UnconfinedTestDispatcher())
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = scope,
            deps = testDeps(),
            connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        return Harness(app, scope, fetched)
    }

    @Test fun openingATrimmedSessionFetchesItsHistoryOnce() {
        val h = harness()
        h.app.reduce(ServerFrame.Snapshot(logs = mapOf("a" to listOf(e("2"))), partialLogs = listOf("a")))

        h.app.ensureMessagesLoaded("a")
        h.app.ensureMessagesLoaded("a")
        h.awaitState { "a" in it.completeLogs }
        h.app.ensureMessagesLoaded("a")

        assertEquals(listOf("/sessions/a/messages"), h.fetched)
        assertEquals(listOf(e("1"), e("2")), h.app.state.value.messages["a"])
        assertTrue("a" in h.app.state.value.completeLogs)
    }

    @Test fun aFullSnapshotLogNeedsNoFetch() {
        val h = harness()
        h.app.reduce(ServerFrame.Snapshot(logs = mapOf("a" to listOf(e("2"))), partialLogs = emptyList()))

        h.app.ensureMessagesLoaded("a")

        assertTrue(h.fetched.isEmpty())
    }

    @Test fun aFailedFetchLeavesTheSessionToRetry() {
        val h = harness(HttpStatusCode.InternalServerError)
        h.app.reduce(ServerFrame.Snapshot(logs = mapOf("a" to listOf(e("2"))), partialLogs = listOf("a")))

        // A failed fetch changes nothing, so retry until a second request goes out.
        runBlocking {
            withTimeout(5_000) {
                while (h.fetched.size < 2) {
                    h.app.ensureMessagesLoaded("a")
                    delay(20)
                }
            }
        }

        assertFalse("a" in h.app.state.value.completeLogs)
        assertEquals(listOf(e("2")), h.app.state.value.messages["a"])
    }

    @Test fun aTrimmedSnapshotPrefetchesTheMostRecentSessions() {
        val h = harness()
        val logs = (1..6).associate { n -> "s$n" to listOf(e("$n")) }
        h.app.reduce(ServerFrame.Snapshot(logs = logs, partialLogs = logs.keys.toList()))

        h.scope.advanceUntilIdle()
        h.awaitState { it.completeLogs.size == 4 }

        assertEquals(listOf("s6", "s5", "s4", "s3").map { "/sessions/$it/messages" }, h.fetched)
    }

    @Test fun aReconnectDoesNotWarmMoreSessionsWhenTheRecentOnesAreLoaded() {
        val h = harness()
        val logs = (1..6).associate { n -> "s$n" to listOf(e("$n")) }
        h.app.reduce(ServerFrame.Snapshot(logs = logs, partialLogs = logs.keys.toList()))
        h.scope.advanceUntilIdle()
        h.awaitState { it.completeLogs.size == 4 }
        h.fetched.clear()

        // Reconnect: nothing new arrived, so the four loaded logs survive — and nothing else loads.
        val st = h.app.state.value
        val tails = st.messages.mapValues { (_, log) -> listOf(log.last()) }
        h.app.reduce(ServerFrame.Snapshot(logs = tails, partialLogs = tails.keys.toList()))
        h.scope.advanceUntilIdle()

        assertEquals(4, h.app.state.value.completeLogs.size)
        assertTrue(h.fetched.isEmpty(), "unexpected fetches: ${h.fetched}")
    }

    @Test fun openingATrimmedChatAlsoFetchesItsActivityAndCommands() {
        val h = harness()
        h.app.reduce(
            ServerFrame.Snapshot(
                logs = mapOf("a" to listOf(e("2"))),
                partialLogs = listOf("a"),
                partialExtras = listOf("a"),
            ),
        )

        h.app.ensureMessagesLoaded("a")
        h.awaitState { "a" in it.completeLogs && "a" in it.completeExtras }

        assertEquals(setOf("/sessions/a/messages", "/sessions/a/chat-extras"), h.fetched.toSet())
        val st = h.app.state.value
        assertEquals(listOf(7), st.activity["a"]?.map { it.seq })
        assertEquals(listOf("review"), st.commands["a"]?.map { it.name })
        assertEquals(true, st.commandsResolved["a"])
    }

    @Test fun aChatWithItsHistoryButNotItsExtrasFetchesOnlyTheExtras() {
        val h = harness()
        h.app.reduce(
            ServerFrame.Snapshot(logs = mapOf("a" to listOf(e("2"))), partialLogs = emptyList(), partialExtras = listOf("a")),
        )

        h.app.ensureMessagesLoaded("a")
        h.awaitState { "a" in it.completeExtras }

        assertEquals(listOf("/sessions/a/chat-extras"), h.fetched.toList())
    }

    @Test fun subscribeAsksForTailsExceptTheChatsOnScreen() {
        val frame = Json.parseToJsonElement(subscribeFrameJson(listOf("a", "b", "a"))).jsonObject
        assertEquals("subscribe", frame["type"]!!.jsonPrimitive.content)
        assertEquals(1, frame["logTail"]!!.jsonPrimitive.int)
        assertEquals(listOf("a", "b"), frame["fullLogs"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", frame["trimExtras"]!!.jsonPrimitive.content)
        assertEquals("true", frame["slimArchived"]!!.jsonPrimitive.content)
    }
}
