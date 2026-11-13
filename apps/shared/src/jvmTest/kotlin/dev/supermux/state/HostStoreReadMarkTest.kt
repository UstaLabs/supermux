package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Card #121: a chat opens at its first unread message, so the read mark from BEFORE the open is kept. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreReadMarkTest {
    private fun store(): HostStore {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        return HostStore(
            baseUrl = "ws://test:9898", token = "t", scope = TestScope(UnconfinedTestDispatcher()), deps = testDeps(http = http),
            connectOnInit = false, apiOverride = BrokerApi("ws://test:9898", "t", http),
        )
    }

    private val before = "2020-01-01T00:00:00Z"

    @Test fun viewingAChatKeepsItsReadMarkFromBeforeTheOpen() {
        val app = store()
        app.reduce(ServerFrame.Snapshot(sessions = listOf(SessionInfo(id = "a", name = "a", agent = "claude", workdir = "/w")), reads = mapOf("a" to before)))
        app.updateViewingSessions(listOf("a"), visible = true)
        assertTrue(app.lastRead.value.getValue("a") > before, "viewing marks the chat read")
        assertEquals(before, app.readMarksAtOpen.value["a"])
        // The heartbeat re-asserts the same view: the open's mark stays.
        app.updateViewingSessions(listOf("a"), visible = true)
        assertEquals(before, app.readMarksAtOpen.value["a"])
    }

    @Test fun aChatOpenedAgainGetsItsNewMark() {
        val app = store()
        app.reduce(ServerFrame.Snapshot(sessions = listOf(SessionInfo(id = "a", name = "a", agent = "claude", workdir = "/w")), reads = mapOf("a" to before)))
        app.updateViewingSessions(listOf("a"), visible = true)
        val readWhileOpen = app.lastRead.value.getValue("a")
        app.updateViewingSessions(listOf("b"), visible = true)
        app.updateViewingSessions(listOf("a"), visible = true)
        assertEquals(readWhileOpen, app.readMarksAtOpen.value["a"])
    }
}
