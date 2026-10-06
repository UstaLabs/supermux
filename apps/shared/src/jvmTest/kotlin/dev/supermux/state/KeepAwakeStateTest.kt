package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.KeepAwakeState
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `keep_awake` and `GET|PUT /settings/keep-awake` land in [HostStore.keepAwake]. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAwakeStateTest {

    private var status = HttpStatusCode.OK
    private var body = "{}"

    private fun store(): HostStore {
        val engine = MockEngine { req ->
            val (s, b) = if (req.url.encodedPath == "/settings/keep-awake") status to body else HttpStatusCode.OK to "{}"
            respond(ByteReadChannel(b), s, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    @Test fun the_frame_sets_and_replaces_the_state() {
        val s = store()
        assertNull(s.keepAwake.value)
        val on = KeepAwakeState(enabled = true, onBattery = true, active = true, supported = true)
        s.reduce(ServerFrame.KeepAwakeChanged(on))
        assertEquals(on, s.keepAwake.value)
        val released = on.copy(onBattery = false, active = false, reason = "Released while on battery")
        s.reduce(ServerFrame.KeepAwakeChanged(released))
        assertEquals(released, s.keepAwake.value)
    }

    @Test fun get_and_set_update_the_flow() = runTest {
        val s = store()
        body = """{"enabled":true,"onBattery":true,"active":true,"supported":true}"""
        assertEquals(true, s.getKeepAwake()!!.active)
        assertEquals(true, s.keepAwake.value!!.active)
        body = """{"enabled":false,"onBattery":true,"active":false,"supported":true}"""
        assertEquals(false, s.setKeepAwake(enabled = false)!!.enabled)
        assertEquals(false, s.keepAwake.value!!.enabled)
    }

    @Test fun a_refused_set_returns_null_and_keeps_the_last_state() = runTest {
        val s = store()
        val on = KeepAwakeState(enabled = true, active = true)
        s.reduce(ServerFrame.KeepAwakeChanged(on))
        status = HttpStatusCode.Forbidden
        body = """{"error":"only on this computer"}"""
        assertNull(s.setKeepAwake(enabled = false))
        assertEquals(on, s.keepAwake.value)
    }
}
