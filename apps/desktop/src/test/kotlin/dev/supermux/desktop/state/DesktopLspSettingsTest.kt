package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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
 * M4g-4 Task 1: the `DesktopAppState` LSP-settings wrappers (lspLoad/lspToggle/lspInstall/
 * lspAddCustom/lspRemoveCustom). Mirrors [DesktopDiffReviewTest]'s MockEngine layer: BrokerApi is a
 * final concrete class, so the `apiOverride` seam takes a real instance constructed against a ktor
 * [MockEngine] HttpClient — no live broker required. Each wrapper is asserted for its exact HTTP
 * method + path + (where relevant) request body, that a 2xx response decodes into the real DTO, and
 * that a 5xx degrades gracefully via [DesktopAppState.runApi] — AppViewModel.kt:736-747 parity
 * (there via `runCatching{}.getOrNull()`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLspSettingsTest {

    private data class Rec(val method: HttpMethod, val path: String, val body: String)

    private fun bodyText(content: Any?): String = when (content) {
        is TextContent -> content.text
        else -> ""
    }

    /** DesktopAppState whose BrokerApi answers every request with [body]/[status], recording
     *  each request's method + path + raw body into [recorded]. */
    private fun appRecording(
        recorded: MutableList<Rec>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"status":"ok"}""",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            recorded.add(Rec(req.method, req.url.encodedPath, bodyText(req.body)))
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            respond(ByteReadChannel(body), status, jsonHeaders)
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

    // ── lspLoad ─────────────────────────────────────────────────────────────────────

    @Test fun lsp_load_gets_the_settings_editor_path_and_decodes_the_server_list() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """
                {"lsp":{"servers":[
                  {"id":"typescript","label":"TypeScript","extensions":[".ts",".tsx"],"enabled":true,"state":"ready","installable":true},
                  {"id":"pyright","label":"Pyright","extensions":[".py"],"enabled":false,"state":"missing","installable":true,"installLabel":"Install"}
                ]}}
                """.trimIndent(),
        )

        val result = app.lspLoad()

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/settings/editor", rec.path)
        assertEquals(listOf("typescript", "pyright"), result.map { it.id })
        assertEquals("ready", result.first { it.id == "typescript" }.state)
    }

    @Test fun lsp_load_returns_an_empty_list_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.lspLoad()

        assertTrue(result.isEmpty())
    }

    // ── lspToggle ───────────────────────────────────────────────────────────────────

    @Test fun lsp_toggle_puts_the_enable_patch_and_decodes_the_updated_server_list() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{"lsp":{"servers":[{"id":"pyright","label":"Pyright","extensions":[".py"],"enabled":true,"state":"missing"}]}}""",
        )

        val result = app.lspToggle("pyright", true)

        val rec = recorded.single()
        assertEquals(HttpMethod.Put, rec.method)
        assertEquals("/settings/editor", rec.path)
        assertTrue(rec.body.contains("\"pyright\":{\"enabled\":true}"))
        assertEquals(true, result?.single()?.enabled)
    }

    @Test fun lsp_toggle_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.lspToggle("pyright", false))
    }

    // ── lspInstall ──────────────────────────────────────────────────────────────────

    @Test fun lsp_install_posts_to_the_install_path_and_decodes_the_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"ok":true,"lines":["Fetching…","Installed pyright@1.2.3"]}""")

        val result = app.lspInstall("pyright")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/settings/editor/lsp/pyright/install", rec.path)
        assertEquals(true, result?.ok)
        assertEquals(listOf("Fetching…", "Installed pyright@1.2.3"), result?.lines)
    }

    @Test fun lsp_install_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.lspInstall("pyright"))
    }

    // ── lspAddCustom ────────────────────────────────────────────────────────────────

    @Test fun lsp_add_custom_posts_the_body_and_decodes_the_mutation_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{"ok":true,"lsp":{"servers":[{"id":"zig","label":"Zig","extensions":[".zig"],"enabled":true,"state":"missing"}]}}""",
        )

        val result = app.lspAddCustom(
            id = "zig", label = "Zig", command = "zls", extensions = listOf(".zig", ".zon"),
            args = listOf("--stdio"), languageId = "zig", installCmd = "apt install -y zls",
        )

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/settings/editor/lsp/custom", rec.path)
        assertTrue(rec.body.contains("\"id\":\"zig\""))
        assertTrue(rec.body.contains("\"command\":\"zls\""))
        assertTrue(rec.body.contains("\"installCmd\":\"apt install -y zls\""))
        assertEquals(true, result?.ok)
        assertEquals(listOf("zig"), result?.lsp?.servers?.map { it.id })
    }

    @Test fun lsp_add_custom_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.lspAddCustom(id = "zig", label = "Zig", command = "zls", extensions = listOf(".zig")))
    }

    // ── lspRemoveCustom ─────────────────────────────────────────────────────────────

    @Test fun lsp_remove_custom_deletes_the_custom_path_and_decodes_the_mutation_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"ok":true,"lsp":{"servers":[]}}""")

        val result = app.lspRemoveCustom("zig")

        val rec = recorded.single()
        assertEquals(HttpMethod.Delete, rec.method)
        assertEquals("/settings/editor/lsp/custom/zig", rec.path)
        assertEquals(true, result?.ok)
        assertTrue(result?.lsp?.servers.orEmpty().isEmpty())
    }

    @Test fun lsp_remove_custom_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.lspRemoveCustom("zig"))
    }
}
