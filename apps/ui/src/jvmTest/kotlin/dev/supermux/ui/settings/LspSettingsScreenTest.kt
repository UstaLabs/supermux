package dev.supermux.ui.settings

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AddCustomLspArgs
import dev.supermux.net.LspConfig
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.settings.kit.KitPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coding's Code intelligence section (#97 spec §2.2, §10.8): one item per language server with its toggle, Install
 * (its live log, "Installing…", a failure with Show log) and Remove for your own; and the "Add your own language
 * server" fold with today's seven fields. Pure helpers are tested directly. Absorbs the old LSP settings suite.
 */
@OptIn(ExperimentalTestApi::class)
class LspSettingsScreenTest {

    private fun anchored(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    // ── pure helpers ──────────────────────────────────────────────────────────────────────────

    @Test fun state_label_ready_non_custom_server_is_just_ready() {
        assertEquals("Ready", stateLabel(LspServer(id = "typescript", label = "TypeScript", state = "ready"), "studio"))
    }

    @Test fun state_label_ready_custom_server_includes_its_command() {
        assertEquals("Ready · zls", stateLabel(LspServer(id = "zig", label = "Zig", state = "ready", custom = true, command = "zls"), "studio"))
    }

    @Test fun state_label_prereq_missing_names_the_missing_requirement_and_the_machine() {
        assertEquals("Needs python3 on studio", stateLabel(LspServer(id = "x", label = "X", state = "prereq-missing", requires = "python3"), "studio"))
        assertEquals("Needs its toolchain on studio", stateLabel(LspServer(id = "x", label = "X", state = "prereq-missing"), "studio"))
    }

    @Test fun state_label_missing_is_not_installed_or_not_found() {
        assertEquals("Not installed", stateLabel(LspServer(id = "pyright", label = "Pyright", state = "missing"), "studio"))
        assertEquals("Not found on studio", stateLabel(LspServer(id = "zig", label = "Zig", state = "missing", custom = true), "studio"))
    }

    @Test fun ext_summary_joins_unique_extensions_with_a_trailing_ellipsis_past_six() {
        assertEquals(".a, .b, .c, .d, .e, .f…", extSummary(listOf(".a", ".b", ".c", ".d", ".e", ".f", ".g")))
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

    @Test fun the_mark_is_two_letters() {
        assertEquals("TS", lspMark("TypeScript"))
        assertEquals("Py", lspMark("Pyright"))
        assertEquals("Go", lspMark("Go"))
        assertEquals("Z", lspMark("Z"))
    }

    // ── the section ───────────────────────────────────────────────────────────────────────────

    private fun ts() = LspServer(id = "typescript", label = "TypeScript", extensions = listOf(".ts", ".tsx"), enabled = true, state = "ready", installable = true)
    private fun pyright() = LspServer(id = "pyright", label = "Pyright", extensions = listOf(".py"), enabled = false, state = "missing", installable = true, installLabel = "Install")
    private fun gopls() = LspServer(id = "gopls", label = "Go", extensions = listOf(".go"), enabled = true, state = "prereq-missing", requires = "Go", installable = true)
    private fun zig() = LspServer(id = "zig", label = "Zig", extensions = listOf(".zig"), enabled = true, state = "missing", custom = true, installable = false, command = null)

    private fun ComposeUiTest.section(
        servers: List<LspServer> = listOf(ts(), pyright()),
        lspToggle: suspend (String, Boolean) -> List<LspServer>? = { _, _ -> null },
        lspInstall: suspend (String) -> LspInstallResult? = { null },
        installLog: MutableStateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap()),
        installDone: MutableStateFlow<Map<String, ServerFrame.LspInstallDone>> = MutableStateFlow(emptyMap()),
        lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult? = { null },
        lspRemoveCustom: suspend (String) -> LspMutationResult? = { null },
        anchor: String? = null,
    ) = setPlatformContent {
        KitPage("Coding", anchor = anchor) {
            CodeIntelligenceSection(
                CodeIntelligenceActions(
                    lspLoad = { servers },
                    lspToggle = lspToggle,
                    lspInstall = lspInstall,
                    lspInstallLog = installLog,
                    lspInstallDone = installDone,
                    lspAddCustom = lspAddCustom,
                    lspRemoveCustom = lspRemoveCustom,
                ),
            )
        }
    }

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun servers_render_with_their_extensions_or_what_they_need() = runComposeUiTest {
        section(servers = listOf(ts(), pyright(), gopls()))
        awaitTag("lsp_server_row_typescript")
        onNodeWithTag("lsp_settings_screen").assertExists()
        onNodeWithText("TypeScript").assertExists()
        onNodeWithText(".ts, .tsx").assertExists()
        onNodeWithText("Not installed").assertExists()
        onNodeWithText("Needs Go on this machine").assertExists()
        onNodeWithTag("lsp_toggle_typescript").assertIsOn()
        onNodeWithTag("lsp_toggle_pyright").assertIsOff()
    }

    @Test fun the_toggle_fires_lsp_toggle_with_the_desired_state() = runComposeUiTest {
        var toggled: Pair<String, Boolean>? = null
        section(lspToggle = { id, enabled -> toggled = id to enabled; null })
        awaitTag("lsp_toggle_pyright")
        onNodeWithTag("lsp_toggle_pyright").performClick()
        waitForIdle()
        assertEquals("pyright" to true, toggled)
    }

    @Test fun toggle_updates_the_rows_from_the_returned_server_list() = runComposeUiTest {
        section(lspToggle = { _, enabled -> listOf(pyright().copy(enabled = enabled)) })
        awaitTag("lsp_toggle_pyright")
        onNodeWithTag("lsp_toggle_pyright").performClick()
        waitForIdle()
        onNodeWithTag("lsp_server_row_typescript").assertDoesNotExist()
        onNodeWithTag("lsp_toggle_pyright").assertIsOn()
    }

    @Test fun install_is_offered_only_when_enabled_installable_and_not_ready() = runComposeUiTest {
        section(servers = listOf(ts(), pyright().copy(enabled = true)))
        awaitTag("lsp_server_row_typescript")
        onNodeWithTag("lsp_install_typescript").assertDoesNotExist()
        onNodeWithTag("lsp_install_pyright").assertExists()
        onNodeWithText("Install").assertExists()
    }

    @Test fun installing_says_so_and_streams_the_log() = runComposeUiTest {
        val log = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        val gate = CompletableDeferred<Unit>()
        section(servers = listOf(pyright().copy(enabled = true)), installLog = log, lspInstall = { gate.await(); null })
        awaitTag("lsp_install_pyright")
        onNodeWithTag("lsp_install_pyright").performClick()
        waitForIdle()
        onNodeWithText("Installing…").assertExists()
        onNodeWithTag("lsp_install_pyright").assertIsNotEnabled()
        log.value = mapOf("pyright" to listOf("Fetching pyright…", "npm install -g pyright"))
        waitForIdle()
        onNodeWithTag("lsp_install_log_pyright").assertExists()
        onNodeWithText("Fetching pyright…").assertExists()
        gate.complete(Unit)
        waitForIdle()
    }

    @Test fun a_finished_install_says_so_and_dismiss_clears_it() = runComposeUiTest {
        val done = MutableStateFlow(mapOf("pyright" to ServerFrame.LspInstallDone(serverId = "pyright", ok = true)))
        section(servers = listOf(pyright().copy(enabled = true)), installDone = done)
        awaitTag("lsp_install_result_pyright")
        onNodeWithText("Installed.").assertExists()
        onNodeWithTag("lsp_install_dismiss_pyright").performClick()
        waitForIdle()
        onNodeWithTag("lsp_install_result_pyright").assertDoesNotExist()
    }

    @Test fun a_failed_install_says_so_and_shows_its_log_in_a_sheet() = runComposeUiTest {
        val done = MutableStateFlow(
            mapOf("pyright" to ServerFrame.LspInstallDone(serverId = "pyright", ok = false, error = "network unreachable")),
        )
        val log = MutableStateFlow(mapOf("pyright" to listOf("npm ERR! fetch failed")))
        section(servers = listOf(pyright().copy(enabled = true)), installDone = done, installLog = log)
        awaitTag("lsp_install_result_pyright")
        onNodeWithText("Couldn't install Pyright.").assertExists()
        onNodeWithText("network unreachable").assertDoesNotExist()
        onNodeWithTag("lsp_install_show_log_pyright").performClick()
        awaitTag("lsp_install_log_sheet")
        onNodeWithText("network unreachable").assertExists()
        onNodeWithText("npm ERR! fetch failed").assertExists()
    }

    @Test fun your_own_server_has_remove_and_it_calls_lsp_remove_custom() = runComposeUiTest {
        var removedId: String? = null
        section(
            servers = listOf(ts(), zig()),
            lspRemoveCustom = { id -> removedId = id; LspMutationResult(ok = true, lsp = null) },
        )
        awaitTag("lsp_remove_zig")
        onNodeWithTag("lsp_remove_typescript").assertDoesNotExist()
        onNodeWithText("Not found on this machine").assertExists()
        onNodeWithTag("lsp_remove_zig").performClick()
        waitForIdle()
        assertEquals("zig", removedId)
        onNodeWithTag("lsp_server_row_zig").assertDoesNotExist()
    }

    // ── the add fold ──────────────────────────────────────────────────────────────────────────

    @Test fun the_add_fold_holds_the_seven_fields_relabelled() = runComposeUiTest {
        section(servers = emptyList())
        awaitTag("lsp_add_toggle")
        onNode(anchored("add-language-server")).assertExists()
        onNodeWithText("Add your own language server").assertExists()
        onNodeWithTag("lsp_add_form").assertDoesNotExist()
        onNodeWithTag("lsp_add_toggle").performClick()
        awaitTag("lsp_add_form")
        for (label in listOf(
            "Short name", "Name", "Command", "Arguments", "File extensions",
            "Language name (optional)", "Install command (optional)",
        )) {
            onNodeWithText(label).assertExists()
        }
        onAllNodesWithText("broker", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun opened_for_add_language_server_the_fold_opens_itself() = runComposeUiTest {
        section(servers = emptyList(), anchor = "add-language-server")
        awaitTag("lsp_add_form")
    }

    @Test fun save_validates_the_required_fields() = runComposeUiTest {
        section(servers = emptyList())
        awaitTag("lsp_add_toggle")
        onNodeWithTag("lsp_add_toggle").performClick()
        awaitTag("lsp_add_save")
        onNodeWithTag("lsp_add_save").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_error").assertExists()
        onNodeWithText("Fill in the name, command and file extensions.").assertExists()
    }

    @Test fun a_valid_add_calls_lsp_add_custom_and_closes_the_fold() = runComposeUiTest {
        var submitted: AddCustomLspArgs? = null
        section(
            servers = emptyList(),
            lspAddCustom = { args ->
                submitted = args
                LspMutationResult(ok = true, lsp = LspConfig(servers = listOf(zig())))
            },
        )
        awaitTag("lsp_add_toggle")
        onNodeWithTag("lsp_add_toggle").performClick()
        awaitTag("lsp_add_label")
        onNodeWithTag("lsp_add_label").performTextInput("Zig")
        onNodeWithTag("lsp_add_command").performTextInput("zls")
        onNodeWithTag("lsp_add_args").performTextInput("--stdio")
        onNodeWithTag("lsp_add_extensions").performTextInput(".zig, .zon")
        onNodeWithTag("lsp_add_save").performClick()
        awaitTag("lsp_server_row_zig")
        assertEquals("zig", submitted?.id) // slugged from the name, since the short name was left blank
        assertEquals("Zig", submitted?.label)
        assertEquals(listOf("--stdio"), submitted?.args)
        assertEquals(listOf(".zig", ".zon"), submitted?.extensions)
        onNodeWithTag("lsp_add_form").assertDoesNotExist()
    }

    @Test fun a_failed_add_shows_the_returned_error_and_keeps_the_form() = runComposeUiTest {
        section(servers = emptyList(), lspAddCustom = { LspMutationResult(ok = false, error = "id already exists") })
        awaitTag("lsp_add_toggle")
        onNodeWithTag("lsp_add_toggle").performClick()
        awaitTag("lsp_add_label")
        onNodeWithTag("lsp_add_label").performTextInput("Zig")
        onNodeWithTag("lsp_add_command").performTextInput("zls")
        onNodeWithTag("lsp_add_extensions").performTextInput(".zig")
        onNodeWithTag("lsp_add_save").performClick()
        waitForIdle()
        onNodeWithText("id already exists").assertExists()
        onNodeWithTag("lsp_add_form").assertExists()
    }
}
