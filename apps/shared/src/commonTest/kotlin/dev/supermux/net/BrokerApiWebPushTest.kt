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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three BROWSER push calls (W3C Web Push, not the relay/FCM path covered by
 * [BrokerApiPushTest]): VAPID key fetch, subscribe, unsubscribe.
 *
 * The body shape is asserted byte-for-byte because the broker validates
 * `endpoint` + `keys.p256dh` + `keys.auth` positionally by name
 * (src/channels/web/index.ts, `POST /push/subscribe`) and 400s otherwise.
 */
class BrokerApiWebPushTest {

    private fun captured(
        body: String = "{}",
        status: HttpStatusCode = HttpStatusCode.OK,
        sink: MutableList<HttpRequestData>,
        token: String = "tok",
    ): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://h", token, HttpClient(engine))
    }

    private fun HttpRequestData.bodyText(): String =
        when (val c = this.body) {
            is io.ktor.http.content.TextContent -> c.text
            is io.ktor.http.content.OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> error("unexpected body type: ${c::class.simpleName}")
        }

    // ── GET /push/vapid-public-key ────────────────────────────────────────────

    @Test fun vapid_key_issues_GET_and_returns_publicKey() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"publicKey":"BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkQ"}""", sink = reqs)
        assertEquals("BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkQ", api.pushVapidPublicKey())
        assertEquals(1, reqs.size)
        assertEquals(HttpMethod.Get, reqs[0].method)
        assertEquals("http://h/push/vapid-public-key", reqs[0].url.toString())
    }

    @Test fun vapid_key_returns_null_on_503() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = "not configured", status = HttpStatusCode.ServiceUnavailable, sink = reqs)
        assertNull(api.pushVapidPublicKey())
    }

    @Test fun vapid_key_returns_null_when_field_absent_or_blank() = runTest {
        assertNull(captured(body = "{}", sink = mutableListOf()).pushVapidPublicKey())
        assertNull(captured(body = """{"publicKey":""}""", sink = mutableListOf()).pushVapidPublicKey())
    }

    // ── POST /push/subscribe ──────────────────────────────────────────────────

    @Test fun subscribe_posts_exact_body() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"ok":true}""", sink = reqs)
        assertTrue(api.pushSubscribe("https://fcm.googleapis.com/x", "p256", "auth1"))
        assertEquals(1, reqs.size)
        assertEquals(HttpMethod.Post, reqs[0].method)
        assertEquals("http://h/push/subscribe", reqs[0].url.toString())
        assertEquals(
            """{"endpoint":"https://fcm.googleapis.com/x","keys":{"p256dh":"p256","auth":"auth1"}}""",
            reqs[0].bodyText(),
        )
    }

    @Test fun subscribe_returns_false_on_non_2xx() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = "push not configured", status = HttpStatusCode.ServiceUnavailable, sink = reqs)
        assertFalse(api.pushSubscribe("e", "p", "a"))
    }

    // ── DELETE /push/subscribe ────────────────────────────────────────────────

    @Test fun unsubscribe_issues_DELETE() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"ok":true}""", sink = reqs)
        assertTrue(api.pushUnsubscribe())
        assertEquals(1, reqs.size)
        assertEquals(HttpMethod.Delete, reqs[0].method)
        assertEquals("http://h/push/subscribe", reqs[0].url.toString())
    }

    @Test fun unsubscribe_returns_false_on_401() = runTest {
        val api = captured(body = "unauthorized", status = HttpStatusCode.Unauthorized, sink = mutableListOf())
        assertFalse(api.pushUnsubscribe())
    }

    // ── no bearer when the token is blank (the browser's cookie carries auth) ──

    @Test fun blank_token_sends_no_authorization_header() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"publicKey":"k","ok":true}""", sink = reqs, token = "")
        api.pushVapidPublicKey()
        api.pushSubscribe("e", "p", "a")
        api.pushUnsubscribe()
        assertEquals(3, reqs.size)
        reqs.forEach { assertNull(it.headers[HttpHeaders.Authorization]) }
    }
}
