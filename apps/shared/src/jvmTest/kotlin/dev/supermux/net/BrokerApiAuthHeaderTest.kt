package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The browser has no bearer (cookie session), so a blank token must send NO Authorization header —
 * a literal "Bearer " would not match the broker's regex and only muddies the request.
 */
class BrokerApiAuthHeaderTest {
    private fun apiWithToken(token: String, seen: MutableList<String?>): BrokerApi {
        val engine = MockEngine { req ->
            seen += req.headers[HttpHeaders.Authorization]
            respond(
                """{"paired":true,"device":"x"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi(baseUrl = "http://broker.test", token = token, http = HttpClient(engine))
    }

    @Test
    fun bearerIsSentWhenTokenPresent() = runBlocking {
        val seen = mutableListOf<String?>()
        apiWithToken("abc", seen).me()
        assertEquals("Bearer abc", seen.single())
    }

    @Test
    fun noAuthorizationHeaderWhenTokenBlank() = runBlocking {
        val seen = mutableListOf<String?>()
        apiWithToken("", seen).me()
        assertNull(seen.single())
    }
}
