package dev.supermux.web

import dev.supermux.web.auth.CookieSession
import dev.supermux.web.auth.SessionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CookieSessionTest {
    private fun client(vararg replies: Pair<String, Pair<Int, String>>): HttpClient {
        val map = replies.toMap()
        return HttpClient(MockEngine { req ->
            val (code, body) = map[req.url.encodedPath] ?: (404 to "{}")
            respond(body, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }

    @Test fun pairedWhenMeSaysSo() = runTest {
        val s = CookieSession("http://b.test", client("/me" to (200 to """{"paired":true,"device":"chrome"}""")))
        assertEquals(SessionState.Paired("chrome"), s.probe())
    }

    @Test fun freshBrokerClaimsSecretlessly() = runTest {
        val s = CookieSession("http://b.test", client(
            "/me" to (401 to """{"error":"unauthorized"}"""),
            "/pair/claim" to (200 to """{"paired":true,"name":"setup"}"""),
        ))
        assertEquals(SessionState.Paired("setup"), s.probe())
    }

    @Test fun setUpBrokerIsUnpaired() = runTest {
        val s = CookieSession("http://b.test", client(
            "/me" to (401 to """{"error":"unauthorized"}"""),
            "/pair/claim" to (403 to """{"error":"already set up — use normal pairing"}"""),
        ))
        assertIs<SessionState.Unpaired>(s.probe())
    }

    @Test fun meSaysNotPairedSoItClaims() = runTest {
        // A 200 that says `paired:false` is the same answer as a 401: try the secretless claim.
        val s = CookieSession("http://b.test", client(
            "/me" to (200 to """{"paired":false}"""),
            "/pair/claim" to (200 to """{"paired":true,"name":"setup"}"""),
        ))
        assertEquals(SessionState.Paired("setup"), s.probe())
    }

    @Test fun throttledIsOfflineNotUnpaired() = runTest {
        // The broker rate-limits /me. A throttled browser is NOT an unpaired one — sending it to
        // the pair screen (or to a claim) on a 429 is the bug this test exists for.
        val s = CookieSession("http://b.test", client("/me" to (429 to """{"error":"slow down"}""")))
        assertEquals(SessionState.Offline("HTTP 429"), s.probe())
    }

    @Test fun serverErrorIsOffline() = runTest {
        val s = CookieSession("http://b.test", client("/me" to (503 to "upstream down")))
        assertEquals(SessionState.Offline("HTTP 503"), s.probe())
    }

    @Test fun undecodableOkBodyIsOffline() = runTest {
        // A captive portal / proxy answering 200 with HTML is not a verdict about pairing.
        val s = CookieSession("http://b.test", client("/me" to (200 to "<html>hi</html>")))
        assertEquals(SessionState.Offline("unreadable /me response"), s.probe())
    }

    @Test fun claimTransportFailureIsOffline() = runTest {
        val s = CookieSession("http://b.test", HttpClient(MockEngine { req ->
            if (req.url.encodedPath == "/me") {
                respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                throw RuntimeException("claim leg is down")
            }
        }))
        assertIs<SessionState.Offline>(s.probe())
    }

    @Test fun networkFailureIsOffline() = runTest {
        val s = CookieSession("http://b.test", HttpClient(MockEngine { throw RuntimeException("boom") }))
        assertIs<SessionState.Offline>(s.probe())
    }
}
