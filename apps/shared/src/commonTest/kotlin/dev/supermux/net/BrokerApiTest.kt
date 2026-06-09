package dev.supermux.net

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
