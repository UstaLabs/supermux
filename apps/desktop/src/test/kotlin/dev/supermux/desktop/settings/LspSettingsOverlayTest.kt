package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.desktop.shell.AppShell
import dev.supermux.desktop.shell.ShellStateStore
import dev.supermux.desktop.shell.ShellUiState
import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop-only half of the former `EditorLspScreenTest`: the shared `LspSettingsScreen` (now in
 * `:ui`, tested there by `LspSettingsScreenTest`) wired into AppShell as an overlay — open/escape,
 * shortcut gating, and mutual exclusion with the other overlays.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LspSettingsOverlayTest {

    // ── (5) overlay wiring into AppShell ─────────────────────────────────────────────────────

    private val tempFiles = mutableListOf<Path>()

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("lsp_settings_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    @AfterTest fun cleanup() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** A [HostStore] whose HTTP serves GET /settings/editor. */
    private fun appForLspSettings(): HostStore {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            if (req.method == HttpMethod.Get && req.url.encodedPath == "/settings/editor") {
                respond(
                    """
                    {"lsp":{"servers":[
                      {"id":"typescript","label":"TypeScript","extensions":[".ts",".tsx"],"enabled":true,"state":"ready","installable":true},
                      {"id":"pyright","label":"Pyright","extensions":[".py"],"enabled":false,"state":"missing","installable":true,"installLabel":"Install"}
                    ]}}
                    """.trimIndent(),
                    HttpStatusCode.OK, jsonHeaders,
                )
            } else {
                respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun lsp_settings_overlay_opens_from_ui_and_loads_the_server_list() = runComposeUiTest {
        val ui = ShellUiState().apply { openLspSettings() }
        val app = appForLspSettings()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_settings_overlay").assertIsDisplayed()
        onNodeWithTag("lsp_settings_screen").assertIsDisplayed()
        onNodeWithText("TypeScript").assertIsDisplayed()
        onNodeWithText("Pyright").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun escape_closes_the_lsp_settings_overlay() = runComposeUiTest {
        val ui = ShellUiState().apply { openLspSettings() }
        val app = appForLspSettings()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_settings_overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(ui.lspSettingsOpen)
        onNodeWithTag("lsp_settings_overlay").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun workspace_shortcuts_are_gated_off_while_the_lsp_settings_overlay_is_up() = runComposeUiTest {
        val ui = ShellUiState().apply { openLspSettings() }
        val app = appForLspSettings()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        assertFalse(ui.sidebarCollapsed)
        onNodeWithTag("lsp_settings_screen").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                pressKey(Key.B)
            }
        }
        waitForIdle()
        assertFalse(ui.sidebarCollapsed)
        assertTrue(ui.lspSettingsOpen)
    }

    @Test fun opening_lsp_settings_closes_any_other_open_overlay() {
        val ui = ShellUiState()
        ui.openUsage()
        ui.openLspSettings()
        assertFalse(ui.usageOpen)
        assertTrue(ui.lspSettingsOpen)
    }
}
