package dev.supermux.web

import dev.supermux.net.BrokerApi
import dev.supermux.web.push.WebPushRegistrar
import dev.supermux.web.push.vapidKeyBytes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan 4's push pieces, in a real browser (headless Chrome via Karma).
 *
 * The line between what IS and IS NOT testable here matters: a real `pushManager.subscribe()`
 * needs a push SERVICE (FCM) and a VAPID keypair the test has no way to provide, so the subscribe
 * leg is verified in the browser check, not here. What a unit test can pin down is the pure key
 * decode — which is silent when wrong — and the guard that keeps the registrar from touching the
 * broker (or prompting) before the user has granted the permission.
 */
class WebPushTest {

    // ── vapidKeyBytes ─────────────────────────────────────────────────────────

    /** RFC 4648 §5's own test vectors, base64url-encoded and unpadded, as the broker sends them. */
    @Test fun decodes_rfc4648_vectors() {
        assertEquals("f", vapidKeyBytes("Zg").decodeToString())
        assertEquals("fo", vapidKeyBytes("Zm8").decodeToString())
        assertEquals("foo", vapidKeyBytes("Zm9v").decodeToString())
        assertEquals("foob", vapidKeyBytes("Zm9vYg").decodeToString())
        assertEquals("fooba", vapidKeyBytes("Zm9vYmE").decodeToString())
        assertEquals("foobar", vapidKeyBytes("Zm9vYmFy").decodeToString())
    }

    /** Padding is optional, not forbidden: the same string with `=` decodes identically. */
    @Test fun padding_is_accepted_and_ignored() {
        assertEquals("fo", vapidKeyBytes("Zm8=").decodeToString())
        assertEquals("foob", vapidKeyBytes("Zm9vYg==").decodeToString())
    }

    /** `-` and `_` are the whole point of base64url — and the standard `+`/`/` still decode. */
    @Test fun url_alphabet_maps_to_the_standard_one() {
        assertEquals(vapidKeyBytes("+/8=").toList(), vapidKeyBytes("-_8").toList())
        assertEquals(listOf<Byte>(-5, -1), vapidKeyBytes("-_8").toList().take(2))
    }

    /**
     * A real VAPID application server key: 65 raw bytes, uncompressed P-256 point (leading 0x04).
     * The length is what `subscribe()` actually validates, so it is the assertion that matters.
     */
    @Test fun real_vapid_key_is_65_bytes_starting_with_0x04() {
        val key = "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvtqvTuCT5V2jfZxdxKYkX5rZ0ZYVgn0Gg-FFcqAHF1Iqf_ReAWqkw"
        val bytes = vapidKeyBytes(key)
        assertEquals(65, bytes.size)
        assertEquals(4.toByte(), bytes[0])
    }

    @Test fun garbage_and_empty_decode_to_nothing_rather_than_throwing() {
        assertEquals(0, vapidKeyBytes("").size)
        assertEquals(0, vapidKeyBytes("   ").size)
        assertEquals(0, vapidKeyBytes("not base64 !!").size)
    }

    // ── registerIfPaired() before a grant ─────────────────────────────────────

    /**
     * Headless Chrome starts at `Notification.permission === "default"`, so this is the real
     * pre-grant path — not a simulation of one. The registrar must ask the broker NOTHING: a
     * `GET /push/vapid-public-key` before the user has said yes is a wasted round trip, and a
     * `subscribe()` there would raise the permission prompt the banner is supposed to own.
     */
    @Test fun register_does_not_touch_the_broker_without_permission() = runTest {
        var requests = 0
        val engine = MockEngine { _ ->
            requests++
            respond(ByteReadChannel("{}"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = BrokerApi("http://h", "", HttpClient(engine))
        val registrar = WebPushRegistrar(api = { api }, scope = CoroutineScope(Dispatchers.Default))

        assertEquals("default", registrar.permission.value)
        registrar.register()
        assertEquals(0, requests)
    }

    /** A null broker (no host paired yet) is "nothing to do", never a throw into the composition. */
    @Test fun register_with_no_broker_is_quiet() = runTest {
        val registrar = WebPushRegistrar(api = { null }, scope = CoroutineScope(Dispatchers.Default))
        registrar.register()
    }

    /** Chrome has Notification + service workers + PushManager — the support probe must say so. */
    @Test fun chrome_reports_push_support() {
        val registrar = WebPushRegistrar(api = { null }, scope = CoroutineScope(Dispatchers.Default))
        assertTrue(registrar.supported)
    }
}
