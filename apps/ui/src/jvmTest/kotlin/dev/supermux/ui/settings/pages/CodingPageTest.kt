package dev.supermux.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ForgeAccount
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.net.LspServer
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.settings.CodeIntelligenceActions
import dev.supermux.ui.settings.GitHostingActions
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import kotlin.test.Test

/**
 * Coding (#97 spec §2.2, §10.8, §11): GitHub & GitLab and Code intelligence are the machine's, Editor & terminal this
 * device's — each head says whose, and the page has a scope line only with two or more machines.
 */
@OptIn(ExperimentalTestApi::class)
class CodingPageTest {

    private fun anchored(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    private val forges = GitHostingActions(
        forgesLoad = {
            ForgeConnectionsResponse(
                connections = listOf(ForgeConnection(id = "c1", kind = "github", host = "github.com", account = ForgeAccount(login = "alice"))),
            )
        },
    )
    private val lsp = CodeIntelligenceActions(
        lspLoad = { listOf(LspServer(id = "typescript", label = "TypeScript", extensions = listOf(".ts"), enabled = true, state = "ready")) },
    )

    private val mini = KitMachine("m2", "studio-mini", local = false, online = true, status = "Running")
    private val laptop = KitMachine("m1", "laptop", local = true, online = true, status = "Running")

    @Composable
    private fun Page(machines: SettingsMachines?, anchor: String? = null, forges: GitHostingActions = this.forges, lsp: CodeIntelligenceActions = this.lsp) {
        CompositionLocalProvider(LocalSettingsMachines provides machines) {
            CodingPage(forges = forges, lsp = lsp, anchor = anchor)
        }
    }

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun the_three_heads_say_whose_they_are() = runComposeUiTest {
        // A computer that can host: "on this computer".
        val desk = FakePlatform(caps = NO_CAPS.copy(localBroker = true))
        setPlatformContent(platform = desk) { Page(SettingsMachines(listOf(mini), "m2") {}) }
        awaitTag("forge_row_c1")
        onNodeWithText("Coding").assertExists()
        onNode(anchored("github")).assertExists()
        // GitHub & GitLab and Code intelligence.
        onAllNodesWithText("on studio-mini").assertCountEquals(2)
        onNodeWithText("on this computer").assertExists()
        onNode(anchored("code-intelligence")).assertExists()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("lsp_server_row_typescript").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("@alice").assertExists()
        onNodeWithTag("editor_wrap_switch").assertExists()
    }

    @Test fun with_one_machine_there_is_no_page_scope_line() = runComposeUiTest {
        setPlatformContent { Page(SettingsMachines(listOf(mini), "m2") {}) }
        awaitTag("forge_row_c1")
        onNodeWithTag("settings_scope").assertDoesNotExist()
    }

    @Test fun with_two_machines_the_page_says_which_one_its_machine_parts_are_on() = runComposeUiTest {
        setPlatformContent { Page(SettingsMachines(listOf(laptop, mini), "m2") {}) }
        awaitTag("forge_row_c1")
        onNodeWithTag("settings_scope").assertExists()
        onNodeWithText("Machine parts on").assertExists()
    }

    @Test fun an_offline_machine_hides_github_and_code_intelligence_but_keeps_the_editor() = runComposeUiTest {
        val off = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline", lastSeen = "2h ago")
        setPlatformContent {
            Page(
                SettingsMachines(listOf(off), "m2") {},
                forges = GitHostingActions(forgesLoad = { error("must not load for an offline machine") }),
                lsp = CodeIntelligenceActions(lspLoad = { error("must not load for an offline machine") }),
            )
        }
        awaitTag("settings_offline")
        onAllNodesWithTag("settings_offline").assertCountEquals(2)
        onAllNodesWithText("studio-mini is offline · last seen 2h ago.").assertCountEquals(2)
        onNodeWithTag("git_hosting_add_account").assertDoesNotExist()
        onNodeWithTag("lsp_add_toggle").assertDoesNotExist()
        // The heads stay (and their anchors land), and the device's rows keep working.
        onNode(anchored("github")).assertExists()
        onNode(anchored("code-intelligence")).assertExists()
        onNodeWithTag("editor_wrap_switch").assertExists()
        onNodeWithTag("editor_font_value").assertExists()
        onNodeWithTag("terminal_font_value").assertExists()
    }

    @Test fun opened_for_add_language_server_the_fold_is_open() = runComposeUiTest {
        setPlatformContent { Page(null, anchor = "add-language-server") }
        awaitTag("lsp_add_form")
    }
}
