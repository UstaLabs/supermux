package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The browser has no bearer — it authenticates with the HttpOnly `cmux_token` cookie — so a blank
 * token must send NO `Authorization` header at all. A literal `Bearer ` does not match the broker's
 * regex and only muddies the request. Every request shape is covered: GET, POST-with-body, and the
 * raw-bytes upload, which used to build the header itself.
 */
class BrokerApiAuthHeaderTest {
    private fun apiWithToken(token: String, seen: MutableList<String?>): BrokerApi {
        val engine = MockEngine { req ->
            seen += req.headers[HttpHeaders.Authorization]
            respond(
                """{"paired":true,"device":"x","file_id":"f1","url":"/files/f1"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi(baseUrl = "http://broker.test", token = token, http = HttpClient(engine))
    }

    @Test
    fun bearerIsSentWhenTokenPresent() = runTest {
        val seen = mutableListOf<String?>()
        apiWithToken("abc", seen).me()
        assertEquals("Bearer abc", seen.single())
    }

    @Test
    fun noAuthorizationHeaderWhenTokenBlank() = runTest {
        val seen = mutableListOf<String?>()
        apiWithToken("", seen).me()
        assertNull(seen.single())
    }

    /** A token of spaces is not a credential either — `isNotBlank`, not `isNotEmpty`. */
    @Test
    fun noAuthorizationHeaderWhenTokenIsWhitespace() = runTest {
        val seen = mutableListOf<String?>()
        apiWithToken("   ", seen).me()
        assertNull(seen.single())
    }

    /** POST with a JSON body goes through the same helper. */
    @Test
    fun bearerIsSentOnJsonPost() = runTest {
        val seen = mutableListOf<String?>()
        apiWithToken("abc", seen).switchModel("s1", "opus")
        assertEquals("Bearer abc", seen.single())
    }

    @Test
    fun noAuthorizationHeaderOnJsonPostWhenTokenBlank() = runTest {
        val seen = mutableListOf<String?>()
        apiWithToken("", seen).switchModel("s1", "opus")
        assertNull(seen.single())
    }

    /** `upload` built the header from a literal before this change. */
    @Test
    fun bearerIsSentOnRawUpload() = runTest {
        val seen = mutableListOf<String?>()
        apiWithToken("abc", seen).upload("s1", ByteArray(4), "a.bin", "application/octet-stream")
        assertEquals("Bearer abc", seen.single())
    }

    @Test
    fun noAuthorizationHeaderOnRawUploadWhenTokenBlank() = runTest {
        val seen = mutableListOf<String?>()
        apiWithToken("", seen).upload("s1", ByteArray(4), "a.bin", "application/octet-stream")
        assertNull(seen.single())
    }
}
