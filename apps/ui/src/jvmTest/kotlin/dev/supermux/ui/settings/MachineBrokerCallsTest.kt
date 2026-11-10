package dev.supermux.ui.settings

import dev.supermux.net.BrokerApi
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The broker calls behind the machine page's actions holders, through a real [HostStore] over a mocked broker:
 * what today's System, Devices and Shared links screen suites pinned with their `desktop_app_state_*` tests (#97
 * Step 2 moved the screens into the machine page; these calls are unchanged).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineBrokerCallsTest {

    private class Broker(val app: HostStore, val calls: CopyOnWriteArrayList<Pair<HttpMethod, String>>, val client: HttpClient)

    /** [answer] maps a request to (status, body); null answers `{}` with 200. */
    private fun broker(answer: (HttpMethod, String) -> Pair<HttpStatusCode, String>?): Broker {
        val calls = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = Dispatchers.Unconfined
                addHandler { req ->
                    val path = req.url.encodedPath
                    calls.add(req.method to path)
                    val (status, body) = answer(req.method, path) ?: (HttpStatusCode.OK to "{}")
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = HostStoreDeps(httpFactory = { client }, settings = FakeSettingsStore(), clock = FixedClock()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return Broker(app, calls, client)
    }

    private fun <T> withBroker(answer: (HttpMethod, String) -> Pair<HttpStatusCode, String>?, block: suspend (Broker) -> T): T {
        val b = broker(answer)
        try {
            return runBlocking { block(b) }
        } finally {
            b.client.close()
        }
    }

    private val ok = HttpStatusCode.OK
    private val broken = HttpStatusCode.InternalServerError

    // ── Status, updates and restart (the header) ──────────────────────────────────────────────────

    private val statusJson = """{"current":"1.2.3","commit":"abc12345","latest":null,"updateAvailable":false,"notesUrl":"https://n","mode":"binary","state":"idle","lastChecked":1717200000000,"lastError":null,"disabled":false}"""
    private val checkJson = """{"current":"1.2.3","commit":"abc12345","latest":"1.3.0","updateAvailable":true,"notesUrl":"https://n","mode":"binary","state":"idle","lastChecked":1717200001000,"lastError":null,"disabled":false}"""

    @Test fun update_status_decodes() = withBroker({ m, p -> if (m == HttpMethod.Get && p == "/api/update/status") ok to statusJson else null }) {
        val s = it.app.updateStatus()!!
        assertEquals("1.2.3", s.current)
        assertEquals("abc12345", s.commit)
        assertEquals("binary", s.mode)
    }

    @Test fun update_status_is_null_on_a_broker_error() = withBroker({ _, p -> if (p == "/api/update/status") broken to "{}" else null }) {
        assertNull(it.app.updateStatus())
    }

    @Test fun check_update_posts_and_returns_the_status() = withBroker({ m, p -> if (m == HttpMethod.Post && p == "/api/update/check") ok to checkJson else null }) {
        val s = it.app.checkUpdate()!!
        assertEquals("1.3.0", s.latest)
        assertTrue(s.updateAvailable)
        assertEquals(1, it.calls.count { c -> c == HttpMethod.Post to "/api/update/check" })
    }

    @Test fun run_update_started() = withBroker({ _, p -> if (p == "/api/update/run") HttpStatusCode.Accepted to """{"started":true}""" else null }) {
        assertTrue(it.app.runUpdate()!!.started)
    }

    @Test fun run_update_carries_the_instruction_on_400() = withBroker({ _, p ->
        if (p == "/api/update/run") HttpStatusCode.BadRequest to """{"error":"self-update not available","instruction":"Use git pull."}""" else null
    }) {
        val r = it.app.runUpdate()!!
        assertFalse(r.started)
        assertEquals("Use git pull.", r.instruction)
    }

    @Test fun run_update_500_with_an_empty_body_has_an_error() = withBroker({ _, p -> if (p == "/api/update/run") broken to "{}" else null }) {
        val r = it.app.runUpdate()!!
        assertFalse(r.started)
        assertEquals("HTTP 500", r.error)
    }

    @Test fun restart_posts() = withBroker({ _, _ -> null }) {
        assertTrue(it.app.restartBroker())
        assertEquals(1, it.calls.count { c -> c == HttpMethod.Post to "/system/restart" })
    }

    @Test fun restart_is_false_on_5xx() = withBroker({ _, p -> if (p == "/system/restart") broken to "{}" else null }) {
        assertFalse(it.app.restartBroker())
    }

    // ── Devices ───────────────────────────────────────────────────────────────────────────────────

    private val devicesJson = """[{"name":"pixel-8","created_at":"2024-01-01T00:00:00Z","last_seen_at":"2024-06-01T12:00:00Z"}]"""

    @Test fun devices_decode() = withBroker({ m, p -> if (m == HttpMethod.Get && p == "/devices") ok to devicesJson else null }) {
        assertEquals(listOf("pixel-8"), it.app.devices()!!.map { d -> d.name })
    }

    @Test fun devices_are_null_on_a_broker_error() = withBroker({ _, p -> if (p == "/devices") broken to "{}" else null }) {
        assertNull(it.app.devices())
    }

    @Test fun add_device_posts_and_returns_the_link() = withBroker({ m, p ->
        if (m == HttpMethod.Post && p == "/devices") ok to """{"url":"https://pair.example/tok","name":"new-phone"}""" else null
    }) {
        val r = it.app.addDevice("new-phone")!!
        assertEquals("https://pair.example/tok", r.url)
        assertEquals("new-phone", r.name)
    }

    @Test fun add_device_is_null_on_non_2xx() = withBroker({ m, p -> if (m == HttpMethod.Post && p == "/devices") broken to "{}" else null }) {
        assertNull(it.app.addDevice("x"))
    }

    @Test fun revoke_deletes_the_device_by_name() = withBroker({ _, _ -> null }) {
        assertTrue(it.app.revokeDevice("pixel-8"))
        assertTrue(HttpMethod.Delete to "/devices/pixel-8" in it.calls, "${it.calls}")
    }

    @Test fun revoke_is_false_on_500_and_on_404() {
        withBroker({ m, _ -> if (m == HttpMethod.Delete) broken to "{}" else null }) { assertFalse(it.app.revokeDevice("pixel-8")) }
        withBroker({ m, _ -> if (m == HttpMethod.Delete) HttpStatusCode.NotFound to "{}" else null }) { assertFalse(it.app.revokeDevice("already-gone")) }
    }

    // ── Shared links ──────────────────────────────────────────────────────────────────────────────

    @Test fun links_decode() = withBroker({ m, p ->
        if (m == HttpMethod.Get && p == "/proxies") ok to """[{"domain":"app.example.local","sessionName":"web","port":3000,"isPublic":false}]""" else null
    }) {
        val l = it.app.proxiesForSettings()!!
        assertEquals(listOf("app.example.local"), l.map { p -> p.domain })
    }

    @Test fun links_are_null_on_a_broker_error() = withBroker({ _, p -> if (p == "/proxies") broken to "{}" else null }) {
        assertNull(it.app.proxiesForSettings())
    }

    @Test fun a_refused_create_public_or_remove_says_so() = withBroker({ m, p ->
        if (p.startsWith("/proxies") && m != HttpMethod.Get) broken to "{}" else null
    }) {
        assertNull(it.app.createProxy("web", 4000, null))
        assertFalse(it.app.setProxyPublic("app.example.local", true))
        assertFalse(it.app.removeProxy("app.example.local"))
        assertTrue(it.calls.any { c -> c.first == HttpMethod.Patch }, "${it.calls}")
        assertTrue(it.calls.any { c -> c.first == HttpMethod.Delete }, "${it.calls}")
    }
}
