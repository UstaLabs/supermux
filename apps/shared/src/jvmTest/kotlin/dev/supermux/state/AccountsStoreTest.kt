package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.SpawnRequest
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accounts store glue (slice A3b): typed refusals, lazy list, frames, and SpawnRequest.account. */
class AccountsStoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun broker_refusal_keeps_status_code_and_message() {
        val f = accountFailure(
            kotlinx.coroutines.CancellationException(
                """BrokerApi request unavailable: HTTP 409 {"error":"Session has an outstanding lifecycle operation","code":"session_busy"}""",
            ),
        )
        assertEquals(409, f.status)
        assertEquals("session_busy", f.code)
        assertEquals("Session has an outstanding lifecycle operation", f.message)
        // A body cut at 200 chars still yields its code when the field made it in.
        val cut = accountFailure(kotlinx.coroutines.CancellationException("""BrokerApi request unavailable: HTTP 409 {"code":"session_busy","error":"aaaaaaaaaa"""))
        assertEquals("session_busy", cut.code)
        assertNull(accountFailure(RuntimeException("socket closed")).status)
    }

    @Test fun login_phases_are_ordered() {
        assertTrue(accountLoginPhaseRank("starting") < accountLoginPhaseRank("awaiting_user"))
        assertTrue(accountLoginPhaseRank("awaiting_user") < accountLoginPhaseRank("verifying"))
        assertTrue(accountLoginPhaseRank("verifying") < accountLoginPhaseRank("failed"))
    }

    private data class Rec(val method: String, val path: String, val body: String)

    private fun store(recorded: MutableList<Rec>): HostStore {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            recorded.add(Rec(req.method.value, path, (req.body as? TextContent)?.text ?: ""))
            val h = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path == "/accounts" -> respond(
                    """{"accounts":[{"id":"claude:system","agent":"claude","method":"system","label":"System login","system":true},
                       |{"id":"claude-work","agent":"claude","method":"subscription","label":"Work"}]}""".trimMargin(),
                    HttpStatusCode.OK, h,
                )
                path.endsWith("/account") -> respond(
                    """{"error":"Session has an outstanding lifecycle operation","code":"session_busy"}""", HttpStatusCode.Conflict, h,
                )
                path == "/paths/validate" -> respond("""{"ok":true,"path":"/w"}""", HttpStatusCode.OK, h)
                path == "/sessions" -> respond("""{"id":"s1","name":"n","workdir":"/w","agent":"claude"}""", HttpStatusCode.OK, h)
                else -> respond("{}", HttpStatusCode.OK, h)
            }
        }
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = CoroutineScope(Dispatchers.Default),
            deps = testDeps(),
            connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    @Test fun accounts_are_fetched_only_once_asked_then_follow_accounts_changed() = runBlocking {
        val rec = Collections.synchronizedList(mutableListOf<Rec>())
        val app = store(rec)
        app.reduce(ServerFrame.AccountsChanged)
        delay(100)
        assertTrue(rec.none { it.path == "/accounts" }, "no GET /accounts before a screen asks")
        app.ensureAccounts()
        val list = withTimeout(5_000) { app.accounts.first { it != null } }
        assertEquals(listOf("claude:system", "claude-work"), list!!.map { it.id })
        app.reduce(ServerFrame.AccountsChanged)
        withTimeout(5_000) { while (rec.count { it.path == "/accounts" } < 2) delay(20) }
    }

    @Test fun login_frames_and_settings_frames_reach_the_flows() = runBlocking {
        val app = store(mutableListOf())
        val got = async { withTimeout(5_000) { app.accountLogins.first() } }
        delay(50)
        app.reduce(ServerFrame.AccountLoginState(loginId = "l1", agent = "claude", phase = "awaiting_user", url = "https://u", needsCode = true))
        val st = got.await()
        assertEquals("l1", st.loginId)
        assertTrue(st.needsCode)
        app.reduce(ServerFrame.AccountsSettings(autoSwitch = true))
        assertEquals(true, app.accountsAutoSwitch.value)
    }

    @Test fun a_busy_session_switch_is_a_typed_refusal() = runBlocking {
        val rec = mutableListOf<Rec>()
        val app = store(rec)
        val r = app.setSessionAccount("s1", "claude-work")
        val failed = assertIs<AccountResult.Failed>(r)
        assertEquals(409, failed.status)
        assertEquals("session_busy", failed.code)
        assertEquals("/sessions/s1/account", rec.last().path)
        assertEquals("""{"account":"claude-work"}""", rec.last().body)
    }

    @Test fun spawn_sends_the_chosen_account() = runBlocking {
        val rec = mutableListOf<Rec>()
        val app = store(rec)
        app.createSessionWithFirstMessageOrThrow("/w", "claude", null, null, "hi", emptyList(), false, null, account = "claude-work")
        val req = json.decodeFromString<SpawnRequest>(rec.first { it.path == "/sessions" }.body)
        assertEquals("claude-work", req.account)
        app.createSessionWithFirstMessageOrThrow("/w", "claude", null, null, "hi", emptyList(), false, null)
        val plain = json.decodeFromString<SpawnRequest>(rec.last { it.path == "/sessions" }.body)
        assertNull(plain.account)
    }
}
