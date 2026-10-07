package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two calls the BROWSER bootstrap needs and no native host does: ending a cookie session and
 * the trust-on-first-connect claim that carries no secret. The 403 case is the one that matters —
 * "this broker already belongs to someone" is an ANSWER, not a failure, so it must come back as
 * `paired=false` rather than the CancellationException `decode` raises for every other non-2xx.
 */
class BrokerApiWebSessionTest {
    @Test
    fun logoutPostsToLogoutWithoutABody() = runTest {
        var seen: Pair<HttpMethod, String>? = null
        val engine = MockEngine { req -> seen = req.method to req.url.encodedPath; respond("", HttpStatusCode.NoContent) }
        BrokerApi("http://b.test", "", HttpClient(engine)).logout()
        assertEquals(HttpMethod.Post to "/logout", seen)
    }

    @Test
    fun secretlessClaimParsesThePairedShape() = runTest {
        var body = ""
        val engine = MockEngine { req ->
            body = req.body.toByteArray().decodeToString()
            respond(
                """{"paired":true,"name":"chrome"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val r = BrokerApi("http://b.test", "", HttpClient(engine)).claimSecretless("chrome")
        assertTrue(r.paired)
        assertEquals("chrome", r.name)
        assertTrue(body.contains("\"name\":\"chrome\""), body)
        assertFalse(body.contains("claimSecret"), "secretless claim must not send a claimSecret key: $body")
    }

    @Test
    fun secretlessClaimOn403IsUnpairedNotAnException() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":"already set up — use normal pairing"}""",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val r = BrokerApi("http://b.test", "", HttpClient(engine)).claimSecretless("chrome")
        assertFalse(r.paired)
        assertEquals("already set up — use normal pairing", r.error)
    }
}
