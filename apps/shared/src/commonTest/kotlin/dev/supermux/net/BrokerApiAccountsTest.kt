package dev.supermux.net

import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accounts wire models (slice A3a): defaults for older brokers and the request shapes. */
class BrokerApiAccountsTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private fun captured(body: String, sink: MutableList<HttpRequestData>): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(content = ByteReadChannel(body), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.bodyText(): String =
        (this.body as? io.ktor.http.content.TextContent)?.text ?: ""

    @Test fun session_info_without_account_fields_still_decodes() {
        val s = json.decodeFromString<SessionInfo>("""{"id":"a","name":"n","workdir":"/w","agent":"claude"}""")
        assertNull(s.account)
        assertNull(s.accountLabel)
        val withAccount = json.decodeFromString<SessionInfo>(
            """{"id":"a","name":"n","workdir":"/w","agent":"codex","account":"codex:system","accountLabel":"System login"}""")
        assertEquals("codex:system", withAccount.account)
        assertEquals("System login", withAccount.accountLabel)
    }

    @Test fun account_frames_decode() {
        assertEquals(ServerFrame.AccountsChanged, json.decodeFromString<ServerFrame>("""{"type":"accounts_changed"}"""))
        val login = json.decodeFromString<ServerFrame>(
            """{"type":"account_login_state","loginId":"l1","agent":"claude","phase":"awaiting_user","url":"https://claude.ai/x","needsCode":true}""",
        ) as ServerFrame.AccountLoginState
        assertEquals("l1", login.loginId)
        assertTrue(login.needsCode)
        assertNull(login.account)
        val state = json.decodeFromString<ServerFrame>(
            """{"type":"session_state","session":"s","account":"k","accountLabel":"Key"}""",
        ) as ServerFrame.SessionState
        assertEquals("k", state.account)
        assertEquals("Key", state.accountLabel)
    }

    @Test fun accounts_list_decodes_views() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val api = captured(
            """{"accounts":[{"id":"claude:system","agent":"claude","method":"system","label":"System login","isolated":false,"system":true,"createdAt":"1970-01-01T00:00:00.000Z","usage":[]},
               |{"id":"k","agent":"claude","method":"api_key","label":"Key","customLabel":"Key","isolated":false,"system":false,"createdAt":"2026-10-04T00:00:00.000Z","usage":[{"name":"five_hour","usedPercent":12.5}]}]}""".trimMargin(),
            sink,
        )
        val list = api.accounts("claude")
        assertEquals(listOf("claude:system", "k"), list.map { it.id })
        assertEquals(12.5, list[1].usage.single().usedPercent)
        assertEquals("http://h/accounts?agent=claude", sink.single().url.toString())
    }

    @Test fun account_requests_have_the_broker_shapes() = runTest {
        val sink = mutableListOf<HttpRequestData>()
        val api = captured("""{"loginId":"l1","agent":"codex","phase":"starting"}""", sink)
        api.startAccountLogin("codex", loginAs = "token", label = "Work")
        assertEquals(HttpMethod.Post, sink.last().method)
        assertEquals("http://h/accounts/login", sink.last().url.toString())
        assertEquals("""{"agent":"codex","as":"token","label":"Work"}""", sink.last().bodyText())
        api.sendAccountLoginCode("l1", "CODE")
        assertEquals("http://h/accounts/login/l1/code", sink.last().url.toString())
        api.addAccount("codex", "api_key", "sk", "K")
        assertEquals("""{"agent":"codex","method":"api_key","secret":"sk","label":"K"}""", sink.last().bodyText())
        api.removeAccount("k", deleteHome = true)
        assertEquals(HttpMethod.Delete, sink.last().method)
        assertEquals("http://h/accounts/k?deleteHome=1", sink.last().url.toString())
        api.setSessionAccount("s1", "k")
        assertEquals("http://h/sessions/s1/account", sink.last().url.toString())
        assertEquals("""{"account":"k"}""", sink.last().bodyText())
    }

    @Test fun spawn_request_account_defaults_to_absent() {
        val encoder = Json { encodeDefaults = false }
        assertTrue(!encoder.encodeToString(SpawnRequest(workdir = "/w")).contains("account"))
        assertTrue(encoder.encodeToString(SpawnRequest(workdir = "/w", account = "k")).contains("\"account\":\"k\""))
    }
}
