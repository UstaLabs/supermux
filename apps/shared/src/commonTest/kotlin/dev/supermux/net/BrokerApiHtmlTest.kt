package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #104: a card's page text, GET /html/<id> as JSON, read with the bearer and kept in memory by id. */
class BrokerApiHtmlTest {
    private fun api(seen: MutableList<HttpRequestData>, status: HttpStatusCode, body: String) = BrokerApi("http://h", "tok", HttpClient(MockEngine { req ->
        seen += req
        respond(content = ByteReadChannel(body), status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }))

    @Test fun thePageIsReadWithTheBearer() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val html = api(seen, HttpStatusCode.OK, """{"id":"abc","html":"<p>hi</p>"}""").htmlPage("abc")
        assertEquals("<p>hi</p>", html)
        assertEquals("/html/abc", seen.single().url.encodedPath)
        assertEquals("Bearer tok", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test fun anUnknownPageIsNull() = runTest {
        assertNull(api(mutableListOf(), HttpStatusCode.NotFound, "not found").htmlPage("abc"))
    }

    @Test fun thePagesAreKeptByIdAndAMissIsAskedAgain() = runTest {
        val asked = mutableListOf<String>()
        var answer: String? = null
        val pages = HtmlPages(fetch = { id -> asked += id; answer }, max = 2)
        assertNull(pages.get("a"))
        answer = "A"
        assertEquals("A", pages.get("a"))
        assertEquals("A", pages.get("a"))
        assertEquals(listOf("a", "a"), asked)
        assertEquals("A", pages.peek("a"))
        answer = "x"
        pages.get("b")
        pages.get("c")
        assertNull(pages.peek("a"), "the oldest goes past max")
    }
}
