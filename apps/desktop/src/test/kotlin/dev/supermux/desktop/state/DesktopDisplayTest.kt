package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.DisplayStream
import dev.supermux.net.VncClient
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
import kotlin.test.assertTrue

/**
 * M5-2 Task 1: [DesktopAppState]'s display-stream state-wiring — the `displays` StateFlow, the
 * `display_added`/`display_removed` reducer branches (AppViewModel:286-289 parity), and the
 * `listDisplays`/`connectVnc`/`startDisplay`/`stopDisplay` wrappers (AppViewModel:446-470 parity).
 * Reducer tests mirror [DesktopAppStateReducerTest] (no MockEngine); wrapper tests mirror
 * [DesktopArchivedTest]'s MockEngine layer (BrokerApi is a final concrete class, so `apiOverride`
 * takes a real instance built against a ktor [MockEngine] — no live broker required).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDisplayTest {

    private fun reducerState() = DesktopAppState(
        baseUrl = "ws://test:9898",
        token = "t",
        scope = TestScope(UnconfinedTestDispatcher()),
        connectOnInit = false,
    )

    private fun stream(id: String, session: String = "demo", status: String = "running") =
        DisplayStream(id = id, sessionName = session, provider = "linux-xvfb", transport = "vnc", status = status)

    // ── reducer ─────────────────────────────────────────────────────────────────────

    @Test fun display_added_frame_adds_a_stream_to_the_displays_flow() {
        val s = reducerState()

        s.reduce(ServerFrame.DisplayAdded(stream("d1")))

        assertEquals(listOf("d1"), s.displays.value.map { it.id })
    }

    @Test fun display_added_frame_replaces_an_existing_stream_with_the_same_id() {
        val s = reducerState()
        s.reduce(ServerFrame.DisplayAdded(stream("d1", status = "running")))

        s.reduce(ServerFrame.DisplayAdded(stream("d1", status = "errored")))

        assertEquals(1, s.displays.value.size)
        assertEquals("errored", s.displays.value.single().status)
    }

    @Test fun display_removed_frame_removes_the_matching_stream() {
        val s = reducerState()
        s.reduce(ServerFrame.DisplayAdded(stream("d1")))
        s.reduce(ServerFrame.DisplayAdded(stream("d2")))

        s.reduce(ServerFrame.DisplayRemoved("d1"))

        assertEquals(listOf("d2"), s.displays.value.map { it.id })
    }

    // ── wrappers (MockEngine) ──────────────────────────────────────────────────────

    private data class Rec(val method: HttpMethod, val path: String)

    private fun appRecording(
        recorded: MutableList<Rec>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "[]",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            recorded.add(Rec(req.method, req.url.encodedPath))
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            apiOverride = api,
        )
    }

    @Test fun list_displays_gets_the_displays_path_and_seeds_the_flow() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """[{"id":"d1","sessionName":"demo","provider":"linux-xvfb","transport":"vnc","status":"running"}]""",
        )

        val result = app.listDisplays()

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/displays", rec.path)
        assertEquals(listOf("d1"), result.map { it.id })
        assertEquals(listOf("d1"), app.displays.value.map { it.id })
    }

    @Test fun list_displays_keeps_the_previous_flow_value_on_a_5xx() = runTest {
        // A stateful MockEngine: the FIRST call succeeds (seeds the flow), the SECOND 5xxs — proves
        // the flow is left untouched (not clobbered to empty) rather than testing a fresh instance.
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                respond(
                    ByteReadChannel("""[{"id":"d1","sessionName":"demo","status":"running"}]"""),
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(ByteReadChannel(""), HttpStatusCode.InternalServerError)
            }
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        val app = DesktopAppState(
            baseUrl = "ws://test:9898", token = "t",
            scope = TestScope(UnconfinedTestDispatcher()), connectOnInit = false, apiOverride = api,
        )
        app.listDisplays() // seed once successfully

        val result = app.listDisplays() // now 5xxs

        assertEquals(listOf("d1"), result.map { it.id }) // unchanged, not clobbered to empty
        assertEquals(listOf("d1"), app.displays.value.map { it.id })
    }

    @Test fun start_display_posts_to_the_displays_path_and_decodes_the_stream() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{"id":"d2","sessionName":"demo","provider":"linux-xvfb","transport":"vnc","status":"running"}""",
        )

        val result = app.startDisplay("demo")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/displays", rec.path)
        assertEquals("d2", result?.id)
    }

    @Test fun start_display_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.startDisplay("demo"))
    }

    @Test fun stop_display_deletes_the_display_path() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = "")

        app.stopDisplay("d1")

        val rec = recorded.single()
        assertEquals(HttpMethod.Delete, rec.method)
        assertEquals("/displays/d1", rec.path)
    }

    @Test fun connect_vnc_returns_a_vnc_client() {
        val app = DesktopAppState(
            baseUrl = "ws://test:9898", token = "t",
            scope = TestScope(UnconfinedTestDispatcher()), connectOnInit = false,
        )

        val client = app.connectVnc("stream-1")

        assertTrue(client is VncClient)
    }
}
