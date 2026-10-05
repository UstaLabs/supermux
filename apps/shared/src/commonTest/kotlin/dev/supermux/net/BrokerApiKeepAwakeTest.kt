package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** "Keep the computer awake while hosting": `GET /host`'s keepAwake and `GET|PUT /settings/keep-awake`. */
class BrokerApiKeepAwakeTest {

    private val seen = mutableListOf<Triple<HttpMethod, String, String>>()

    private fun api(body: String, status: HttpStatusCode = HttpStatusCode.OK): BrokerApi {
        val engine = MockEngine { req ->
            seen += Triple(req.method, req.url.encodedPath, req.body.toByteArray().decodeToString())
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    @Test fun host_parses_keep_awake() = runTest {
        val host = api(
            """{"hostId":"h1","name":"n","protocolVersion":1,
               "keepAwake":{"enabled":true,"onBattery":false,"active":false,"supported":true,"reason":"Released while on battery"}}""",
        ).getHost()
        assertEquals(KeepAwakeState(enabled = true, onBattery = false, active = false, supported = true, reason = "Released while on battery"), host.keepAwake)
    }

    @Test fun keep_awake_carries_the_reason_code_and_hint() = runTest {
        val host = api(
            """{"hostId":"h1","name":"n","protocolVersion":1,
               "keepAwake":{"enabled":true,"onBattery":true,"active":false,"supported":true,
                 "reason":"This computer's desktop didn't allow supermux to keep it awake.","reasonCode":"denied","hint":"Add a polkit rule"}}""",
        ).getHost()
        assertEquals(KeepAwakeState.REASON_DENIED, host.keepAwake!!.reasonCode)
        assertEquals("Add a polkit rule", host.keepAwake!!.hint)
        assertEquals(false, host.keepAwake!!.retrying) // absent: false
    }

    @Test fun an_older_broker_has_no_keep_awake() = runTest {
        assertNull(api("""{"hostId":"h1","name":"n","protocolVersion":1}""").getHost().keepAwake)
    }

    @Test fun get_keep_awake() = runTest {
        val s = api("""{"enabled":false,"onBattery":true,"active":false,"supported":false,"reason":"systemd-inhibit was not found"}""").getKeepAwake()
        assertEquals(false, s.supported)
        assertEquals("systemd-inhibit was not found", s.reason)
        assertEquals(HttpMethod.Get to "/settings/keep-awake", seen.single().first to seen.single().second)
    }

    @Test fun set_keep_awake_sends_only_the_given_fields() = runTest {
        val a = api("""{"enabled":true,"onBattery":false,"active":true,"supported":true}""")
        val s = a.setKeepAwake(onBattery = false)
        assertEquals(true, s.active)
        val (method, path, body) = seen.single()
        assertEquals(HttpMethod.Put, method)
        assertEquals("/settings/keep-awake", path)
        assertEquals("""{"onBattery":false}""", body)
    }

    @Test fun set_keep_awake_from_another_device_is_refused() = runTest {
        assertFailsWith<CancellationException> {
            api("""{"error":"only on this computer"}""", HttpStatusCode.Forbidden).setKeepAwake(enabled = false)
        }
    }
}
