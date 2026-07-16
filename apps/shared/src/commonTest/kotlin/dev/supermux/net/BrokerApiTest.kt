package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrokerApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun models_response_parses() {
        val r = json.decodeFromString<ModelsResponse>(
            """{"agent":"claude","current":"opus-4.8","models":[{"id":"opus-4.8","displayName":"Opus 4.8"}]}"""
        )
        assertEquals("opus-4.8", r.current)
        assertEquals(1, r.models.size)
    }

    @Test
    fun reasoning_response_parses_with_visible() {
        val r = json.decodeFromString<ReasoningResponse>(
            """{"agent":"claude","levels":[{"id":"high"}],"visible":false}"""
        )
        assertEquals(false, r.visible)
    }

    @Test
    fun codex_usage_parses_reset_credits() {
        val u = json.decodeFromString<CodexUsage>("""{"plan":"plus","resetCredits":3}""")
        assertEquals(3, u.resetCredits)
        // absent → 0
        val u0 = json.decodeFromString<CodexUsage>("""{"plan":"plus"}""")
        assertEquals(0, u0.resetCredits)
    }

    @Test
    fun claude_usage_parses_per_model_caps_and_tolerates_absence() {
        val u = json.decodeFromString<ClaudeUsage>(
            """{"fiveHour":{"used":12.0},"sevenDay":{"used":4.0},"sevenDaySonnet":{"used":3.0,"resetsAt":"2026-07-13T12:00:00Z"},"sevenDayFable":{"used":7.0,"resetsAt":"2026-07-13T12:00:00Z"}}"""
        )
        assertEquals(3.0, u.sevenDaySonnet?.used)
        assertEquals(7.0, u.sevenDayFable?.used)

        // absent per-model caps → null (row hidden), matching the broker sending null
        val u0 = json.decodeFromString<ClaudeUsage>("""{"fiveHour":{"used":1.0},"sevenDay":{"used":1.0}}""")
        assertEquals(null, u0.sevenDaySonnet)
        assertEquals(null, u0.sevenDayFable)
    }

    @Test
    fun codex_reset_result_parses() {
        val r = json.decodeFromString<CodexResetResult>(
            """{"code":"reset","windowsReset":2,"codex":{"plan":"plus","resetCredits":2}}"""
        )
        assertEquals("reset", r.code)
        assertEquals(2, r.windowsReset)
        assertEquals(2, r.codex?.resetCredits)
    }

    @Test
    fun httpBase_strips_ws_scheme() {
        // Access internal field to verify URL conversion logic.
        // Dummy HttpClient — we only test the URL derivation, no real HTTP calls.
        val api = BrokerApi("ws://h:1", "tok", io.ktor.client.HttpClient())
        assertEquals("http://h:1", api.httpBase)
    }

    @Test
    fun httpBase_strips_wss_scheme() {
        val api = BrokerApi("wss://h", "tok", io.ktor.client.HttpClient())
        assertEquals("https://h", api.httpBase)
    }

    @Test
    fun httpBase_trims_trailing_slash() {
        val api = BrokerApi("ws://h:1/", "tok", io.ktor.client.HttpClient())
        assertEquals("http://h:1", api.httpBase)
    }

    @Test
    fun spawn_request_roundtrips() {
        val req = SpawnRequest(workdir = "/home/user", name = "my-session", agent = "claude", model = "opus-4.8")
        val encoded = Json.encodeToString(SpawnRequest.serializer(), req)
        val decoded = json.decodeFromString<SpawnRequest>(encoded)
        assertEquals(req, decoded)
    }

    @Test
    fun spawn_response_roundtrips() {
        val resp = SpawnResponse(name = "my-session", workdir = "/home/user", agent = "claude", model = "opus-4.8")
        val encoded = Json.encodeToString(SpawnResponse.serializer(), resp)
        val decoded = json.decodeFromString<SpawnResponse>(encoded)
        assertEquals(resp, decoded)
    }

    @Test
    fun spawn_times_out_instead_of_leaving_the_launcher_pending_forever() = runTest {
        val engine = MockEngine {
            delay(60_000)
            respond(ByteReadChannel("{}"))
        }
        val api = BrokerApi("http://h", "tok", HttpClient(engine)).also {
            it.spawnTimeoutMillis = 10
        }

        assertFailsWith<TimeoutCancellationException> {
            api.spawn(SpawnRequest(workdir = "/home/user"))
        }
    }

    @Test
    fun app_config_dto_roundtrips() {
        val cfg = AppConfigDto(
            paName = "my-assistant",
            paWorkdir = "/home/user",
            webPublicUrl = "https://example.com",
            telegramConfigured = true,
            claudeConfigured = true,
            anthropicConfigured = false,
            codexConfigured = false,
            cursorConfigured = false,
            onboarded = true,
        )
        val encoded = Json.encodeToString(AppConfigDto.serializer(), cfg)
        val decoded = json.decodeFromString<AppConfigDto>(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun app_config_dto_ignores_extra_fields() {
        // Broker may return more fields; ignoreUnknownKeys must handle them
        val raw = """{"paName":"pa","paWorkdir":"/home","webPublicUrl":"","webPort":9898,
            |"exposureMode":"local","wildcardBaseDomain":"","onboarded":true,
            |"telegramConfigured":false,"claudeConfigured":true,"anthropicConfigured":false,
            |"codexConfigured":false,"cursorConfigured":false}""".trimMargin()
        val cfg = json.decodeFromString<AppConfigDto>(raw)
        assertEquals("pa", cfg.paName)
        assertEquals(true, cfg.onboarded)
        assertEquals(true, cfg.claudeConfigured)
    }

    @Test
    fun device_dto_roundtrips() {
        val d = DeviceDto(name = "pixel-8", created_at = "2024-01-01T00:00:00Z", last_seen_at = "2024-06-01T12:00:00Z")
        val encoded = Json.encodeToString(DeviceDto.serializer(), d)
        val decoded = json.decodeFromString<DeviceDto>(encoded)
        assertEquals(d, decoded)
    }

    @Test
    fun device_dto_nullable_fields() {
        val raw = """{"name":"my-phone"}"""
        val d = json.decodeFromString<DeviceDto>(raw)
        assertEquals("my-phone", d.name)
        assertEquals(null, d.created_at)
        assertEquals(null, d.last_seen_at)
    }

    @Test
    fun archived_dto_roundtrips() {
        val a = ArchivedDto(id = "abc123", name = "old-session", workdir = "/home/user", agent = "codex", killed_at = "2024-05-01T10:00:00Z")
        val encoded = Json.encodeToString(ArchivedDto.serializer(), a)
        val decoded = json.decodeFromString<ArchivedDto>(encoded)
        assertEquals(a, decoded)
    }

    @Test
    fun archived_dto_defaults() {
        val raw = """{"id":"x","name":"s"}"""
        val a = json.decodeFromString<ArchivedDto>(raw)
        assertEquals("x", a.id)
        assertEquals("claude", a.agent)
        assertEquals("", a.workdir)
        assertEquals(null, a.killed_at)
    }

    @Test
    fun proxy_dto_roundtrips() {
        val p = ProxyDto(
            domain = "abc.example.com",
            sessionName = "my-session",
            port = 8080,
            createdAt = "2024-01-01T00:00:00Z",
            isPublic = true,
        )
        val encoded = Json.encodeToString(ProxyDto.serializer(), p)
        val decoded = json.decodeFromString<ProxyDto>(encoded)
        assertEquals(p, decoded)
    }

    @Test
    fun proxy_dto_defaults() {
        val raw = """{"domain":"x.example.com"}"""
        val p = json.decodeFromString<ProxyDto>(raw)
        assertEquals("x.example.com", p.domain)
        assertEquals("", p.sessionName)
        assertEquals(0, p.port)
        assertEquals(null, p.createdAt)
        assertEquals(false, p.isPublic)
    }

    @Test
    fun proxy_dto_ignores_extra_fields() {
        val raw = """{"domain":"x.example.com","sessionName":"s","port":3000,"isPublic":false,"extra":"ignored"}"""
        val p = json.decodeFromString<ProxyDto>(raw)
        assertEquals("x.example.com", p.domain)
        assertEquals("s", p.sessionName)
        assertEquals(3000, p.port)
    }

    @Test
    fun create_proxy_response_roundtrips() {
        val r = CreateProxyResponse(url = "https://abc.example.com", domain = "abc.example.com", port = 3000)
        val encoded = Json.encodeToString(CreateProxyResponse.serializer(), r)
        val decoded = json.decodeFromString<CreateProxyResponse>(encoded)
        assertEquals(r, decoded)
    }

    @Test
    fun launcher_commands_parse() {
        val r = json.decodeFromString<LauncherCommands>(
            """{"commands":[{"id":"agent:review","family":"project","name":"review","insertText":"/review "}],"resolved":true}"""
        )
        assertEquals(true, r.resolved)
        assertEquals(1, r.commands.size)
        assertEquals("review", r.commands[0].name)
        assertEquals("/", r.commands[0].sigil)
        assertEquals("/review ", r.commands[0].insertText)
    }

    @Test
    fun launcher_commands_defaults_when_unresolved() {
        val r = json.decodeFromString<LauncherCommands>("""{"resolved":false}""")
        assertEquals(false, r.resolved)
        assertEquals(0, r.commands.size)
    }

    @Test
    fun terminal_list_response_parses() {
        val r = json.decodeFromString<TerminalListResponse>(
            """{"terminals":[{"id":"main","createdAt":1700000000000},{"id":"abc","createdAt":1700000001000}]}"""
        )
        assertEquals(2, r.terminals.size)
        assertEquals("main", r.terminals[0].id)
        assertEquals(1700000000000L, r.terminals[0].createdAt)
    }

    @Test
    fun terminal_list_response_defaults_empty() {
        val r = json.decodeFromString<TerminalListResponse>("""{}""")
        assertEquals(0, r.terminals.size)
    }

    @Test
    fun spawn_request_with_worktree_roundtrips() {
        val req = SpawnRequest(workdir = "/home/user", agent = "claude", worktree = true, baseBranch = "dev")
        val decoded = json.decodeFromString<SpawnRequest>(Json.encodeToString(SpawnRequest.serializer(), req))
        assertEquals(req, decoded)
        assertEquals(true, decoded.worktree)
        assertEquals("dev", decoded.baseBranch)
    }

    @Test
    fun spawn_request_omits_worktree_by_default() {
        val req = SpawnRequest(workdir = "/home/user")
        assertEquals(null, req.worktree)
        assertEquals(null, req.baseBranch)
    }

    @Test
    fun repo_info_parses_eligible_with_branches() {
        val r = json.decodeFromString<RepoInfo>(
            """{"isGitRepo":true,"eligible":true,"repoRoot":"/home/user/app","currentBranch":"dev",
               |"branches":{"local":["dev","main"],"remote":["origin/feature"]}}""".trimMargin()
        )
        assertEquals(true, r.eligible)
        assertEquals("dev", r.currentBranch)
        assertEquals(listOf("dev", "main"), r.branches?.local)
        assertEquals(listOf("origin/feature"), r.branches?.remote)
    }

    @Test
    fun repo_info_defaults_when_not_a_repo() {
        val r = json.decodeFromString<RepoInfo>("""{"isGitRepo":false,"eligible":false}""")
        assertEquals(false, r.isGitRepo)
        assertEquals(null, r.repoRoot)
        assertEquals(null, r.branches)
    }

    @Test
    fun forge_connections_parse_with_account() {
        val r = json.decodeFromString<ForgeConnectionsResponse>(
            """{"connections":[{"id":"c1","kind":"github","host":"github.com","apiBase":"https://api.github.com",
               |"label":"GitHub","account":{"login":"octocat","name":"Octo"},"source":"cli","transport":"ssh",
               |"status":"ok"}],"cli":{"github":{"available":true,"login":"octocat"},"gitlab":{"available":false}}}""".trimMargin()
        )
        assertEquals(1, r.connections.size)
        assertEquals("github", r.connections[0].kind)
        assertEquals("octocat", r.connections[0].account.login)
        assertEquals(true, r.cli?.github?.available)
        assertEquals(false, r.cli?.gitlab?.available)
    }

    @Test
    fun forge_connections_default_empty() {
        val r = json.decodeFromString<ForgeConnectionsResponse>("""{}""")
        assertEquals(0, r.connections.size)
        assertEquals(null, r.cli)
    }

    @Test
    fun forge_search_response_parses_repos_and_errors() {
        val r = json.decodeFromString<ForgeSearchResponse>(
            """{"repos":[{"connectionId":"c1","kind":"github","host":"github.com","owner":"octocat",
               |"name":"hello","fullName":"octocat/hello","private":false,"defaultBranch":"main",
               |"cloneUrl":"https://github.com/octocat/hello.git","webUrl":"https://github.com/octocat/hello"}],
               |"errors":[{"connectionId":"c2","code":"rate_limited","message":"slow down"}]}""".trimMargin()
        )
        assertEquals(1, r.repos.size)
        assertEquals("octocat/hello", r.repos[0].fullName)
        assertEquals("main", r.repos[0].defaultBranch)
        assertEquals("rate_limited", r.errors[0].code)
    }

    @Test
    fun resolved_and_created_repo_parse() {
        assertEquals("/home/user/cloned", json.decodeFromString<ResolvedRepo>("""{"localPath":"/home/user/cloned"}""").localPath)
        val created = json.decodeFromString<CreatedRepo>(
            """{"repo":{"fullName":"me/new"},"localPath":"/home/user/new"}"""
        )
        assertEquals("/home/user/new", created.localPath)
        assertEquals("me/new", created.repo?.fullName)
    }
}
