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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the three push-registration methods added to [BrokerApi]:
 *   - [BrokerApi.pushRelayUrl]
 *   - [BrokerApi.registerPushDevice]
 *   - [BrokerApi.registerPushTokenWithRelay]
 *
 * Uses [MockEngine] to assert exact request shape (method, URL, body) without
 * making real HTTP calls. Follows the harness pattern in [BrokerApiSettingsTest].
 */
class BrokerApiPushTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Build a [BrokerApi] backed by a [MockEngine] that records requests and replies [body]. */
    private fun captured(
        body: String = "{}",
        status: HttpStatusCode = HttpStatusCode.OK,
        sink: MutableList<HttpRequestData>,
    ): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://broker.example", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.bodyText(): String =
        when (val c = this.body) {
            is io.ktor.http.content.TextContent -> c.text
            is io.ktor.http.content.OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> error("unexpected body type: ${c::class.simpleName}")
        }

    // ── MeResponse DTO ────────────────────────────────────────────────────────

    @Test fun me_response_parses_relayUrl() {
        val r = json.decodeFromString<MeResponse>(
            """{"paired":true,"device":"iphone","relayUrl":"https://relay.example"}"""
        )
        assertEquals(true, r.paired)
        assertEquals("iphone", r.device)
        assertEquals("https://relay.example", r.relayUrl)
    }

    @Test fun me_response_null_relayUrl_when_absent() {
        val r = json.decodeFromString<MeResponse>("""{"paired":true,"device":"iphone"}""")
        assertNull(r.relayUrl)
    }

    @Test fun me_response_ignores_extra_fields() {
        val r = json.decodeFromString<MeResponse>("""{"paired":false,"extra":"ignored"}""")
        assertEquals(false, r.paired)
        assertNull(r.device)
        assertNull(r.relayUrl)
    }

    // ── pushRelayUrl() ────────────────────────────────────────────────────────

    @Test fun pushRelayUrl_issues_GET_me_and_returns_relayUrl() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"paired":true,"device":"iphone","relayUrl":"https://relay.example"}""",
            sink = reqs,
        )
        val result = api.pushRelayUrl()
        val r = reqs.single()
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("http://broker.example/me", r.url.toString())
        assertEquals("https://relay.example", result)
    }

    @Test fun pushRelayUrl_returns_null_when_field_absent() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"paired":true,"device":"iphone"}""", sink = reqs)
        val result = api.pushRelayUrl()
        assertNull(result)
    }

    // ── registerPushDevice() ─────────────────────────────────────────────────

    @Test fun registerPushDevice_issues_POST_push_device_with_exact_body() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = "{}", sink = reqs)
        api.registerPushDevice(
            platform = "ios",
            routingToken = "rt-abc123",
            pubkey = "PUBKEY_BASE64",
        )
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://broker.example/push/device", r.url.toString())
        val b = r.bodyText()
        assertTrue(b.contains("\"platform\":\"ios\""), "body=$b")
        assertTrue(b.contains("\"routingToken\":\"rt-abc123\""), "body=$b")
        assertTrue(b.contains("\"pubkey\":\"PUBKEY_BASE64\""), "body=$b")
    }

    @Test fun registerPushDevice_sends_android_platform() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(sink = reqs)
        api.registerPushDevice(
            platform = "android",
            routingToken = "rt-xyz",
            pubkey = "PK",
        )
        val b = reqs.single().bodyText()
        assertTrue(b.contains("\"platform\":\"android\""), "body=$b")
        assertTrue(b.contains("\"routingToken\":\"rt-xyz\""), "body=$b")
        assertTrue(b.contains("\"pubkey\":\"PK\""), "body=$b")
    }

    // ── registerPushTokenWithRelay() ──────────────────────────────────────────

    @Test fun registerPushTokenWithRelay_issues_POST_relay_register_with_exact_body() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"status":"pending","routingToken":"rt-from-relay"}""",
            status = HttpStatusCode.Accepted,
            sink = reqs,
        )
        val rt = api.registerPushTokenWithRelay(
            relayUrl = "https://push.supermux.dev",
            platform = "ios",
            pushToken = "APNS_TOKEN_HEX",
        )
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("https://push.supermux.dev/register", r.url.toString())
        val b = r.bodyText()
        assertTrue(b.contains("\"platform\":\"ios\""), "body=$b")
        assertTrue(b.contains("\"pushToken\":\"APNS_TOKEN_HEX\""), "body=$b")
        assertEquals("rt-from-relay", rt)
    }

    @Test fun registerPushTokenWithRelay_trims_trailing_slash_from_relayUrl() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"status":"pending","routingToken":"rt"}""",
            status = HttpStatusCode.Accepted,
            sink = reqs,
        )
        api.registerPushTokenWithRelay(
            relayUrl = "https://push.supermux.dev/",
            platform = "android",
            pushToken = "FCM_TOKEN",
        )
        val r = reqs.single()
        assertEquals("https://push.supermux.dev/register", r.url.toString())
    }

    @Test fun registerPushTokenWithRelay_returns_routingToken_from_202_body() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"status":"pending","routingToken":"rt-abc"}""",
            status = HttpStatusCode.Accepted,
            sink = reqs,
        )
        val rt = api.registerPushTokenWithRelay("https://relay.example", "ios", "TOKEN")
        assertEquals("rt-abc", rt)
        assertEquals(1, reqs.size)
    }

    @Test fun registerPushTokenWithRelay_returns_null_when_old_relay_omits_token() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"status":"pending"}""",
            status = HttpStatusCode.Accepted,
            sink = reqs,
        )
        val rt = api.registerPushTokenWithRelay("https://relay.example", "android", "TOKEN")
        assertNull(rt)
    }
}
