package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrokerApiSubagentTest {
    private fun api(body: String, status: HttpStatusCode, sink: MutableList<HttpRequestData>): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.bodyText(): String = (body as io.ktor.http.content.TextContent).text

    @Test fun messagePostsTextAndReturnsVia() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val r = api("""{"ok":true,"via":"relay"}""", HttpStatusCode.OK, sink).messageSubagent("s1", "a/1", "hi")
        assertEquals(SubagentActionResult(ok = true, via = "relay"), r)
        val req = sink.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/sessions/s1/subagents/a%2F1/message", req.url.encodedPath)
        assertEquals("""{"text":"hi"}""", req.bodyText())
    }

    @Test fun refusalIsAnAnswerNotAFailure() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val r = api("""{"ok":false,"error":"this subagent does not accept messages"}""", HttpStatusCode.Conflict, sink)
            .messageSubagent("s1", "a1", "hi")
        assertFalse(r.ok)
        assertEquals("this subagent does not accept messages", r.error)
    }

    @Test fun stopPostsToStop() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val r = api("""{"ok":true}""", HttpStatusCode.OK, sink).stopSubagent("s1", "a1")
        assertTrue(r.ok)
        assertEquals("/sessions/s1/subagents/a1/stop", sink.single().url.encodedPath)
    }
}
