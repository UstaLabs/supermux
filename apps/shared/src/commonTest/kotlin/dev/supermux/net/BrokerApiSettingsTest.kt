package dev.supermux.net

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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the Phase-A finish/settings DTOs and — via a capturing [MockEngine] —
 * the exact request shapes [BrokerApi] produces (method, path, body), since the
 * request bodies are file-private and can only be asserted through the public API.
 */
class BrokerApiSettingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Build a BrokerApi whose engine records every request and replies [body]. */
    private fun captured(
        body: String = "{}",
        status: io.ktor.http.HttpStatusCode = io.ktor.http.HttpStatusCode.OK,
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
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    // BrokerApi always sends bodies via setBody(String) (JSON) or setBody(text)
    // for soul — both become TextContent. ByteArrayContent is covered for safety.
    private fun HttpRequestData.bodyText(): String =
        when (val c = this.body) {
            is io.ktor.http.content.TextContent -> c.text
            is io.ktor.http.content.OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> error("unexpected body type: ${c::class.simpleName}")
        }

    // ── pure DTO decode ────────────────────────────────────────────────────────

    @Test fun finish_result_decodes_pr_outcome() {
        val r = json.decodeFromString<FinishResult>(
            """{"status":"pr_opened","branch":"feat/x","prUrl":"https://gh/pr/1","draft":true,"verified":null}""")
        assertEquals("pr_opened", r.status)
        assertEquals("https://gh/pr/1", r.prUrl)
        assertEquals(true, r.draft)
        assertNull(r.verified)
    }

    @Test fun finish_readiness_decodes() {
        val r = json.decodeFromString<FinishReadiness>(
            """{"base":"main","branch":"feat/x","ahead":3,"behind":0,"dirtyFiles":["a.kt"],
               |"filesChanged":2,"insertions":10,"deletions":1,"hasRemote":true,"baseHasUpstream":true,
               |"ghAvailable":true,"conflictPreflight":"clean","recommended":"pr","nothingToLand":false}""".trimMargin())
        assertEquals("main", r.base)
        assertEquals(3, r.ahead)
        assertEquals(listOf("a.kt"), r.dirtyFiles)
        assertEquals("pr", r.recommended)
        assertEquals("clean", r.conflictPreflight)
        assertFalse(r.nothingToLand)
    }

    @Test fun finish_readiness_decodes_prRequiresGreen() {
        val withFlag = json.decodeFromString<FinishReadiness>(
            """{"branch":"b","base":"main","prRequiresGreen":true}"""
        )
        assertTrue(withFlag.prRequiresGreen)
        val omitted = json.decodeFromString<FinishReadiness>(
            """{"branch":"b","base":"main"}"""
        )
        assertFalse(omitted.prRequiresGreen)
    }

    @Test fun verify_results_decode() {
        val s = json.decodeFromString<VerifySuggestResult>("""{"content":"bun test","source":"package.json"}""")
        assertEquals("bun test", s.content)
        assertEquals("package.json", s.source)
        val sv = json.decodeFromString<VerifySaveResult>("""{"ok":false,"reason":"empty"}""")
        assertFalse(sv.ok)
        assertEquals("empty", sv.reason)
    }

    @Test fun agent_install_status_list_decodes() {
        val list = json.decodeFromString<List<AgentInstallStatus>>(
            """[{"kind":"claude","installed":true,"authed":true},{"kind":"codex","installed":true,"authed":false}]""")
        assertEquals(2, list.size)
        assertEquals("claude", list[0].kind)
        assertTrue(list[0].authed)
        assertFalse(list[1].authed)
    }

    @Test fun agent_login_state_decodes_real_fields() {
        val s = json.decodeFromString<AgentLoginState>(
            """{"kind":"claude","phase":"awaiting_user","url":"https://auth","code":"AB-12","needsCode":true}""")
        assertEquals("awaiting_user", s.phase)
        assertEquals("https://auth", s.url)
        assertEquals("AB-12", s.code)
        assertTrue(s.needsCode)
        assertNull(s.error)
    }

    @Test fun agent_install_job_decodes_real_fields() {
        val job = json.decodeFromString<AgentInstallJob>(
            """{"state":"failed","log":"npm error","exitCode":1}""")
        assertEquals("failed", job.state)
        assertEquals("npm error", job.log)
        assertEquals(1, job.exitCode)
    }

    @Test fun opencode_providers_decode_bare_array() {
        val list = json.decodeFromString<List<OpenCodeProvider>>(
            """[{"id":"anthropic","configured":true,"methods":[{"type":"oauth","label":"Login","index":0},
               |{"type":"api","label":"API key","index":1}]}]""".trimMargin())
        assertEquals(1, list.size)
        assertEquals("anthropic", list[0].id)
        assertTrue(list[0].configured)
        assertEquals(2, list[0].methods.size)
        assertEquals("oauth", list[0].methods[0].type)
        assertEquals(1, list[0].methods[1].index)
    }

    @Test fun editor_settings_decode() {
        val r = json.decodeFromString<EditorSettingsResponse>(
            """{"lsp":{"servers":[{"id":"ts","label":"TypeScript","extensions":[".ts",".tsx"],"enabled":true,
               |"state":"ready","installLabel":null,"installable":false,"requires":null,"custom":false}]}}""".trimMargin())
        assertEquals(1, r.lsp.servers.size)
        assertEquals("ts", r.lsp.servers[0].id)
        assertEquals(listOf(".ts", ".tsx"), r.lsp.servers[0].extensions)
        assertTrue(r.lsp.servers[0].enabled)
        assertEquals("ready", r.lsp.servers[0].state)
    }

    @Test fun update_status_decodes_real_shape() {
        val r = json.decodeFromString<UpdateStatus>(
            """{"current":"1.2.3","commit":"abc123","latest":"1.3.0","updateAvailable":true,"notesUrl":"https://n",
               |"mode":"binary","state":"idle","lastChecked":1717200000000,"lastError":null}""".trimMargin())
        assertEquals("1.2.3", r.current)
        assertEquals("abc123", r.commit)
        assertEquals("1.3.0", r.latest)
        assertTrue(r.updateAvailable)
        assertEquals("binary", r.mode)
        assertEquals(1717200000000.0, r.lastChecked)
        assertFalse(r.disabled)
    }

    @Test fun update_status_fallback_disabled() {
        val r = json.decodeFromString<UpdateStatus>(
            """{"current":"1","commit":"c","mode":"source","updateAvailable":false,"latest":null,
               |"notesUrl":null,"state":"idle","lastChecked":null,"lastError":null,"disabled":true}""".trimMargin())
        assertTrue(r.disabled)
        assertNull(r.lastChecked)
    }

    @Test fun run_update_started_decodes() {
        val r = json.decodeFromString<RunUpdateResult>("""{"started":true}""")
        assertTrue(r.started)
        assertNull(r.error)
    }

    @Test fun run_update_busy_decodes() {
        val r = json.decodeFromString<RunUpdateResult>("""{"error":"busy"}""")
        assertFalse(r.started)
        assertEquals("busy", r.error)
    }

    @Test fun run_update_instruction_decodes() {
        val r = json.decodeFromString<RunUpdateResult>(
            """{"error":"self-update not available in source mode","instruction":"Source install — update via git."}""")
        assertFalse(r.started)
        assertEquals("Source install — update via git.", r.instruction)
    }

    // ── request shapes via MockEngine ──────────────────────────────────────────

    @Test fun finish_sends_action_in_body() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"sessionId":"s","status":"running","action":"pr","startedAt":1}""", sink = reqs)
        api.finish("s1", action = "pr", draft = true)
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/sessions/s1/finish", r.url.toString())
        val b = r.bodyText()
        assertTrue(b.contains("\"action\":\"pr\""), "body=$b")
        assertTrue(b.contains("\"draft\":true"), "body=$b")
        // explicitNulls=false → unset optional fields are omitted, not null
        assertFalse(b.contains("\"skipVerify\""), "body=$b")
        assertFalse(b.contains("null"), "body=$b")
    }

    @Test fun save_config_omits_null_fields() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(sink = reqs)
        api.saveConfig(voiceCleanupModel = "claude-haiku")
        val r = reqs.single()
        assertEquals(HttpMethod.Put, r.method)
        assertEquals("http://h/settings/config", r.url.toString())
        val b = r.bodyText()
        assertEquals("""{"voiceCleanupModel":"claude-haiku"}""", b)
        assertFalse(b.contains("paName"))
        assertFalse(b.contains("codexApiKey"))
    }

    @Test fun save_config_sends_token_only() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(sink = reqs)
        api.saveConfig(codexApiKey = "sk-123")
        assertEquals("""{"codexApiKey":"sk-123"}""", reqs.single().bodyText())
    }

    @Test fun save_config_marks_onboarding_complete_without_clobbering_other_fields() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(sink = reqs)
        api.saveConfig(onboarded = true)
        assertEquals("""{"onboarded":true}""", reqs.single().bodyText())
    }

    @Test fun agent_install_starts_and_polls_job() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"state":"running","log":"installing","exitCode":null}""", sink = reqs)

        assertEquals("running", api.startAgentInstall("codex").state)
        assertEquals("running", api.agentInstallState("codex").state)

        assertEquals(HttpMethod.Post, reqs[0].method)
        assertEquals("http://h/agents/codex/install", reqs[0].url.toString())
        assertEquals(HttpMethod.Get, reqs[1].method)
        assertEquals("http://h/agents/codex/install", reqs[1].url.toString())
    }

    @Test fun agent_install_conflict_resumes_existing_job() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"state":"running","log":"already running","exitCode":null}""",
            status = io.ktor.http.HttpStatusCode.Conflict,
            sink = reqs,
        )

        val job = api.startAgentInstall("claude")

        assertEquals("running", job.state)
        assertEquals("already running", job.log)
    }

    @Test fun app_config_decodes_voice_engine() {
        val c = json.decodeFromString<AppConfigDto>(
            """{"voiceCleanupEngine":"opencode-zen","voiceCleanupModel":"deepseek-v4-flash-free"}""")
        assertEquals("opencode-zen", c.voiceCleanupEngine)
        assertEquals("deepseek-v4-flash-free", c.voiceCleanupModel)
    }

    @Test fun app_config_voice_engine_null_when_absent() {
        val c = json.decodeFromString<AppConfigDto>("""{"paName":"x"}""")
        assertNull(c.voiceCleanupEngine)
    }

    @Test fun save_config_sends_voice_engine_and_model() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(sink = reqs)
        api.saveConfig(voiceCleanupEngine = "codex", voiceCleanupModel = "gpt-5.4-mini")
        assertEquals(
            """{"voiceCleanupModel":"gpt-5.4-mini","voiceCleanupEngine":"codex"}""",
            reqs.single().bodyText(),
        )
    }

    @Test fun save_config_empty_model_is_sent_as_reset_sentinel() = runTest {
        // "" is the reset-to-default sentinel; it must reach the broker as an empty
        // string (not be omitted), so switching engines clears a stale model id.
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(sink = reqs)
        api.saveConfig(voiceCleanupEngine = "cursor", voiceCleanupModel = "")
        assertEquals(
            """{"voiceCleanupModel":"","voiceCleanupEngine":"cursor"}""",
            reqs.single().bodyText(),
        )
    }

    @Test fun add_forge_sends_kind_token_host() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"id":"c1","kind":"github"}""", sink = reqs)
        api.addForge(kind = "gitlab", token = "glpat", host = "gl.example.com")
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/forge/connections", r.url.toString())
        val b = r.bodyText()
        assertTrue(b.contains("\"kind\":\"gitlab\""), "body=$b")
        assertTrue(b.contains("\"token\":\"glpat\""), "body=$b")
        assertTrue(b.contains("\"host\":\"gl.example.com\""), "body=$b")
        // encodeDefaults=false: source/transport at their defaults are OMITTED.
        // The broker re-applies source:"pat", transport:"https" for missing fields.
        assertFalse(b.contains("\"source\""), "body=$b")
        assertFalse(b.contains("\"transport\""), "body=$b")
    }

    @Test fun add_forge_sends_non_default_transport() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"id":"c1"}""", sink = reqs)
        api.addForge(kind = "github", token = "pat", transport = "ssh")
        val b = reqs.single().bodyText()
        assertTrue(b.contains("\"transport\":\"ssh\""), "body=$b")
        assertFalse(b.contains("\"host\""), "body=$b") // null host omitted
    }

    @Test fun set_lsp_enabled_sends_nested_patch() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"lsp":{"servers":[]}}""", sink = reqs)
        api.setLspEnabled("ts", false)
        val r = reqs.single()
        assertEquals(HttpMethod.Put, r.method)
        assertEquals("http://h/settings/editor", r.url.toString())
        assertEquals("""{"lsp":{"servers":{"ts":{"enabled":false}}}}""", r.bodyText())
    }

    @Test fun opencode_key_uses_providerId_field() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(sink = reqs)
        api.setOpenCodeKey("anthropic", "sk-x")
        val r = reqs.single()
        assertEquals("http://h/opencode/auth/key", r.url.toString())
        assertEquals("""{"providerId":"anthropic","key":"sk-x"}""", r.bodyText())
    }

    @Test fun opencode_oauth_start_sends_numeric_method() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"url":"https://auth","instructions":"Enter code: AB-12","method":"auto"}""",
            sink = reqs,
        )
        val res = api.startOpenCodeOAuth("anthropic", 0)
        assertEquals("https://auth", res.url)
        assertEquals("Enter code: AB-12", res.instructions)
        assertEquals("auto", res.method)
        assertEquals("""{"providerId":"anthropic","method":0}""", reqs.single().bodyText())
    }

    @Test fun soul_put_uses_text_plain() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(sink = reqs)
        val ok = api.putSoul("# soul")
        assertTrue(ok)
        val r = reqs.single()
        assertEquals(HttpMethod.Put, r.method)
        assertEquals("http://h/settings/soul", r.url.toString())
        assertEquals("# soul", r.bodyText())
        assertTrue(r.body.contentType.toString().startsWith("text/plain"), "ct=${r.body.contentType}")
    }

    @Test fun finish_readiness_GET_and_decode() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"base":"main","branch":"b","ahead":1,"behind":0,"dirtyFiles":[],"filesChanged":1,
               |"insertions":1,"deletions":0,"hasRemote":true,"baseHasUpstream":true,"ghAvailable":false,
               |"conflictPreflight":"unknown","recommended":"merge","nothingToLand":false}""".trimMargin(),
            sink = reqs,
        )
        val r = api.finishReadiness("s1")
        assertEquals(HttpMethod.Get, reqs.single().method)
        assertEquals("http://h/sessions/s1/finish/readiness", reqs.single().url.toString())
        assertEquals("merge", r.recommended)
    }
}
