package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.workspace.WorkspaceRoot
import dev.supermux.desktop.workspace.WorkspaceStateStore
import dev.supermux.desktop.workspace.WorkspaceUiState
import dev.supermux.net.BrokerApi
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M4g-4 Task 2: [LspSettingsScreen] + [LspServerRow]/[AddLspForm], a port of Android
 * `EditorLspScreen.kt`. Pure helpers ([stateLabel]/[extSummary]/[slugId]) are tested directly;
 * the composables are tested via [runComposeUiTest] with faked lspLoad/lspToggle/lspInstall/
 * lspAddCustom/lspRemoveCustom suspend lambdas + a controllable installLog/installDone
 * MutableStateFlow — no broker, no WorkspaceRoot (that's the overlay-wiring section below).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class EditorLspScreenTest {

    // ── (1) pure helpers ──────────────────────────────────────────────────────────────────────

    @Test fun state_label_ready_non_custom_server_is_just_ready() {
        val s = LspServer(id = "typescript", label = "TypeScript", state = "ready")
        assertEquals("Ready", stateLabel(s))
    }

    @Test fun state_label_ready_custom_server_includes_its_command() {
        val s = LspServer(id = "zig", label = "Zig", state = "ready", custom = true, command = "zls")
        assertEquals("Ready · zls", stateLabel(s))
    }

    @Test fun state_label_prereq_missing_names_the_missing_requirement() {
        val s = LspServer(id = "x", label = "X", state = "prereq-missing", requires = "python3")
        assertEquals("Needs python3", stateLabel(s))
    }

    @Test fun state_label_prereq_missing_defaults_to_toolchain_when_requires_is_null() {
        val s = LspServer(id = "x", label = "X", state = "prereq-missing", requires = null)
        assertEquals("Needs toolchain", stateLabel(s))
    }

    @Test fun state_label_missing_non_custom_is_not_installed() {
        val s = LspServer(id = "pyright", label = "Pyright", state = "missing")
        assertEquals("Not installed", stateLabel(s))
    }

    @Test fun state_label_missing_custom_is_binary_not_found() {
        val s = LspServer(id = "zig", label = "Zig", state = "missing", custom = true)
        assertEquals("Binary not found on broker", stateLabel(s))
    }

    @Test fun ext_summary_joins_unique_extensions_with_a_trailing_ellipsis_past_six() {
        val exts = listOf(".a", ".b", ".c", ".d", ".e", ".f", ".g")
        assertEquals(".a, .b, .c, .d, .e, .f…", extSummary(exts))
    }

    @Test fun ext_summary_dedupes_and_does_not_truncate_six_or_fewer() {
        assertEquals(".ts, .tsx", extSummary(listOf(".ts", ".tsx", ".ts")))
    }

    @Test fun slug_id_lowercases_and_hyphenates_non_alphanumerics() {
        assertEquals("my-cool-server", slugId("My Cool Server!!"))
    }

    @Test fun slug_id_falls_back_to_server_when_the_label_has_no_alphanumerics() {
        assertEquals("server", slugId("!!!"))
    }

    // ── (2) LspSettingsScreen: load, toggle, badges ───────────────────────────────────────────────

    private fun ts() = LspServer(id = "typescript", label = "TypeScript", extensions = listOf(".ts", ".tsx"), enabled = true, state = "ready", installable = true)
    private fun pyright() = LspServer(id = "pyright", label = "Pyright", extensions = listOf(".py"), enabled = false, state = "missing", installable = true, installLabel = "Install")
    private fun zig() = LspServer(id = "zig", label = "Zig", extensions = listOf(".zig"), enabled = true, state = "missing", custom = true, installable = false, command = null)

    private fun screen(
        servers: List<LspServer> = listOf(ts(), pyright()),
        lspToggle: suspend (String, Boolean) -> List<LspServer>? = { _, _ -> null },
        lspInstall: suspend (String) -> LspInstallResult? = { null },
        installLog: MutableStateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap()),
        installDone: MutableStateFlow<Map<String, ServerFrame.LspInstallDone>> = MutableStateFlow(emptyMap()),
        lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult? = { null },
        lspRemoveCustom: suspend (String) -> LspMutationResult? = { null },
        onBack: () -> Unit = {},
    ) = @Composable {
        LspSettingsScreen(
            lspLoad = { servers },
            lspToggle = lspToggle,
            lspInstall = lspInstall,
            lspInstallLog = installLog,
            lspInstallDone = installDone,
            lspAddCustom = lspAddCustom,
            lspRemoveCustom = lspRemoveCustom,
            onBack = onBack,
        )
    }

    @Test fun servers_render_from_a_fake_lsp_load_list() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        onNodeWithTag("lsp_server_row_typescript").assertIsDisplayed()
        onNodeWithTag("lsp_server_row_pyright").assertIsDisplayed()
        onNodeWithText("TypeScript").assertIsDisplayed()
        onNodeWithText("Ready").assertIsDisplayed()
        onNodeWithText("Not installed").assertIsDisplayed()
    }

    @Test fun the_enable_switch_fires_lsp_toggle_with_the_desired_state() = runComposeUiTest {
        var toggled: Pair<String, Boolean>? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(lspToggle = { id, enabled -> toggled = id to enabled; null })()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_toggle_pyright").performClick()
        waitForIdle()
        assertEquals("pyright" to true, toggled)
    }

    @Test fun toggle_updates_the_row_from_the_returned_server_list() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(lspToggle = { id, enabled -> listOf(pyright().copy(enabled = enabled)) })()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_toggle_pyright").performClick()
        waitForIdle()
        // The returned list REPLACES the rendered rows — typescript's row is gone, only pyright remains.
        onNodeWithTag("lsp_server_row_typescript").assertDoesNotExist()
        onNodeWithTag("lsp_server_row_pyright").assertIsDisplayed()
    }

    @Test fun install_button_only_shown_when_enabled_installable_and_not_ready() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(servers = listOf(ts(), pyright().copy(enabled = true)))()
            }
        }
        waitForIdle()
        // typescript: enabled but already ready -> no install button.
        onNodeWithTag("lsp_install_typescript").assertDoesNotExist()
        // pyright: enabled, installable, not ready -> install button shown, using its installLabel.
        onNodeWithTag("lsp_install_pyright").assertIsDisplayed()
        onNodeWithText("Install").assertIsDisplayed()
    }

    @Test fun install_button_streams_log_lines_from_the_install_log_state_flow() = runComposeUiTest {
        val log = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        // lspInstall suspends until we explicitly release it, so `installing` stays true WHILE we
        // push log lines — without this gate, a fake lspInstall that returns immediately would let
        // the row's `installing` flip back to false before the test ever sets `log.value`, and the
        // log Column (rendered only `if (installing && installLines.isNotEmpty())`) would never
        // appear — this is the realistic shape of a long-running install, not a test artifact.
        val installGate = CompletableDeferred<Unit>()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    servers = listOf(pyright().copy(enabled = true)),
                    installLog = log,
                    lspInstall = { installGate.await(); null },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_install_pyright").performClick()
        waitForIdle()
        log.value = mapOf("pyright" to listOf("Fetching pyright…", "npm install -g pyright"))
        waitForIdle()
        onNodeWithTag("lsp_install_log_pyright").assertIsDisplayed()
        onNodeWithText("Fetching pyright…").assertIsDisplayed()
        onNodeWithText("npm install -g pyright").assertIsDisplayed()
        installGate.complete(Unit) // let the install coroutine finish so it doesn't leak past the test
        waitForIdle()
    }

    @Test fun install_done_shows_ok_result_and_dismiss_clears_it() = runComposeUiTest {
        val done = MutableStateFlow<Map<String, ServerFrame.LspInstallDone>>(
            mapOf("pyright" to ServerFrame.LspInstallDone(serverId = "pyright", ok = true)),
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(servers = listOf(pyright().copy(enabled = true)), installDone = done)()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_install_result_pyright").assertIsDisplayed()
        onNodeWithTag("lsp_install_dismiss_pyright").performClick()
        waitForIdle()
        onNodeWithTag("lsp_install_result_pyright").assertDoesNotExist()
    }

    @Test fun install_done_shows_the_error_result_when_not_ok() = runComposeUiTest {
        val done = MutableStateFlow<Map<String, ServerFrame.LspInstallDone>>(
            mapOf("pyright" to ServerFrame.LspInstallDone(serverId = "pyright", ok = false, error = "network unreachable")),
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(servers = listOf(pyright().copy(enabled = true)), installDone = done)()
            }
        }
        waitForIdle()
        onNodeWithText("network unreachable").assertIsDisplayed()
    }

    @Test fun custom_server_shows_a_remove_button_and_firing_it_calls_lsp_remove_custom() = runComposeUiTest {
        var removedId: String? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    servers = listOf(zig()),
                    lspRemoveCustom = { id -> removedId = id; LspMutationResult(ok = true, lsp = null) },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_remove_zig").assertIsDisplayed()
        onNodeWithTag("lsp_remove_typescript").assertDoesNotExist() // non-custom server has no remove button
        onNodeWithTag("lsp_remove_zig").performClick()
        waitForIdle()
        assertEquals("zig", removedId)
        onNodeWithTag("lsp_server_row_zig").assertDoesNotExist() // ok=true, lsp=null -> falls back to filtering it out locally
    }

    // ── (3) the add-custom-server form ────────────────────────────────────────────────────────────

    @Test fun add_form_toggle_reveals_the_form_and_save_validates_required_fields() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen(servers = emptyList())() } }
        waitForIdle()
        onNodeWithTag("lsp_add_form").assertDoesNotExist()
        onNodeWithTag("lsp_add_toggle").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_form").assertIsDisplayed()
        onNodeWithTag("lsp_add_save").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_error").assertIsDisplayed()
        onNodeWithText("Fill in display name, command, and extensions").assertIsDisplayed()
    }

    @Test fun submitting_a_valid_add_form_calls_lsp_add_custom_and_closes_the_form_on_success() = runComposeUiTest {
        var submitted: AddCustomLspArgs? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    servers = emptyList(),
                    lspAddCustom = { args ->
                        submitted = args
                        LspMutationResult(ok = true, lsp = dev.supermux.net.LspConfig(servers = listOf(zig())))
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_add_toggle").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_label").performTextInput("Zig")
        onNodeWithTag("lsp_add_command").performTextInput("zls")
        onNodeWithTag("lsp_add_extensions").performTextInput(".zig, .zon")
        onNodeWithTag("lsp_add_save").performClick()
        waitForIdle()
        assertEquals("zig", submitted?.id) // auto-slugged from the label since Server id was left blank
        assertEquals("Zig", submitted?.label)
        assertEquals(listOf(".zig", ".zon"), submitted?.extensions)
        onNodeWithTag("lsp_add_form").assertDoesNotExist() // closed on success
        onNodeWithTag("lsp_server_row_zig").assertIsDisplayed() // rendered from the returned lsp.servers
    }

    @Test fun a_failed_add_shows_the_returned_error_and_keeps_the_form_open() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(servers = emptyList(), lspAddCustom = { LspMutationResult(ok = false, error = "id already exists") })()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_add_toggle").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_label").performTextInput("Zig")
        onNodeWithTag("lsp_add_command").performTextInput("zls")
        onNodeWithTag("lsp_add_extensions").performTextInput(".zig")
        onNodeWithTag("lsp_add_save").performClick()
        waitForIdle()
        onNodeWithText("id already exists").assertIsDisplayed()
        onNodeWithTag("lsp_add_form").assertIsDisplayed() // stays open on failure
    }

    // ── (4) back ───────────────────────────────────────────────────────────────────────────────────

    @Test fun back_button_fires_on_back() = runComposeUiTest {
        var backCalled = false
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen(onBack = { backCalled = true })() } }
        waitForIdle()
        onNodeWithTag("lsp_settings_back").performClick()
        assertTrue(backCalled)
    }

    // ── (5) overlay wiring into WorkspaceRoot ─────────────────────────────────────────────────────

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

    /** A [DesktopAppState] whose HTTP serves GET /settings/editor. */
    private fun appForLspSettings(): DesktopAppState {
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
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun lsp_settings_overlay_opens_from_ui_and_loads_the_server_list() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { lspSettingsOpen = true }
        val app = appForLspSettings()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state")),
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
        val ui = WorkspaceUiState().apply { lspSettingsOpen = true }
        val app = appForLspSettings()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state")),
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
        val ui = WorkspaceUiState().apply { lspSettingsOpen = true }
        val app = appForLspSettings()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed)
        onNodeWithTag("lsp_settings_screen").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                pressKey(Key.B)
            }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed)
        assertTrue(ui.lspSettingsOpen)
    }

    @Test fun opening_lsp_settings_closes_any_other_open_overlay() {
        val ui = WorkspaceUiState()
        ui.openUsage()
        ui.openLspSettings()
        assertFalse(ui.usageOpen)
        assertTrue(ui.lspSettingsOpen)
    }
}
