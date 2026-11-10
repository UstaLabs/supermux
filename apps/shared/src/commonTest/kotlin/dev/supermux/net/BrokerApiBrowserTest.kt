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
import kotlin.test.assertNull

/** The Browser tab's two broker calls (#105): its preview-only token, and the host's by-port preview template. */
class BrokerApiBrowserTest {
    private fun api(seen: MutableList<HttpRequestData>, status: HttpStatusCode, body: String) = BrokerApi("http://h", "tok", HttpClient(MockEngine { req ->
        seen += req
        respond(content = ByteReadChannel(body), status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }))

    @Test fun aPreviewTokenIsAskedForWithTheBearerAndItsCookieDecoded() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val body = """{"token":"pv_abc","cookie":{"name":"cmux_preview","value":"pv_abc","url":"https://h.test/p/",""" +
            """"path":"/p/","httpOnly":true,"secure":true,"sameSite":"Lax","maxAge":31536000}}"""
        val answer = api(seen, HttpStatusCode.OK, body).previewToken()
        assertEquals(HttpMethod.Post, seen.single().method)
        assertEquals("/devices/preview-token", seen.single().url.encodedPath)
        assertEquals("Bearer tok", seen.single().headers[HttpHeaders.Authorization])
        assertEquals(PreviewCookieDto("cmux_preview", "pv_abc", "https://h.test/p/", path = "/p/", maxAge = 31536000), answer.cookie)
    }

    @Test fun theByPortTemplateIsReadAndABrokerWithoutOneAnswersNull() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        assertEquals("https://h.test/p/{port}/", api(seen, HttpStatusCode.OK, """{"url":"https://h.test/p/{port}/"}""").byPortTemplate())
        assertEquals("/proxies/by-port", seen.single().url.encodedPath)
        assertNull(api(mutableListOf(), HttpStatusCode.NotFound, """{"error":"not found"}""").byPortTemplate())
    }
}
