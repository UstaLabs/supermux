package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ForgeAccount
import dev.supermux.net.ForgeCliPresence
import dev.supermux.net.ForgeCliStatus
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.ui.chat.setPlatformContent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/**
 * GitHub & GitLab (#97 spec §2.2, §10.8): the connected accounts as items, the add sheet (the gh / glab login on the
 * machine, or a pasted token with "Your own server" for either forge) and the disconnect sheet. Driven through
 * [GitHostingScreen], the thin page the setup wizard embeds around [GitHubSection]. Absorbs the old Git hosting suite.
 */
@OptIn(ExperimentalTestApi::class)
class GitHostingScreenTest {

    private fun ComposeUiTest.gitContent(content: @Composable () -> Unit) = setPlatformContent { content() }

    @Composable
    private fun Screen(
        forgesLoad: suspend () -> ForgeConnectionsResponse? = { response() },
        forgeAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean = { _, _, _, _ -> false },
        forgeImport: suspend (kind: String, transport: String) -> Boolean = { _, _ -> false },
        forgeRemove: suspend (id: String) -> Boolean = { true },
    ) = GitHostingScreen(GitHostingActions(forgesLoad, forgeAdd, forgeImport, forgeRemove))

    private fun conn(
        id: String = "c1",
        kind: String = "github",
        login: String = "alice",
        status: String = "ok",
        host: String = "github.com",
        source: String = "pat",
        transport: String = "https",
    ) = ForgeConnection(
        id = id,
        kind = kind,
        host = host,
        account = ForgeAccount(login = login),
        status = status,
        source = source,
        transport = transport,
    )

    private fun response(
        connections: List<ForgeConnection> = emptyList(),
        cli: ForgeCliStatus? = null,
    ) = ForgeConnectionsResponse(connections = connections, cli = cli)

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.openAddSheet() {
        awaitTag("git_hosting_add_account")
        onNodeWithTag("git_hosting_add_account").performClick()
        awaitTag("git_hosting_add_dialog")
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    @Test fun scopes_hint_github_public_vs_enterprise() {
        assertEquals("Contents + Administration (read & write)", scopesHint("github", ""))
        assertEquals("Contents + Administration (read & write)", scopesHint("github", "github.com"))
        assertEquals("repo, read:org", scopesHint("github", "github.acme.com"))
        assertEquals("api", scopesHint("gitlab", ""))
    }

    @Test fun forge_host_url_validation_accepts_hosts_and_https() {
        assertNull(forgeHostUrlError(""))
        assertNull(forgeHostUrlError("  "))
        assertNull(forgeHostUrlError("github.acme.com"))
        assertNull(forgeHostUrlError("github.acme.com/api/v3"))
        assertNull(forgeHostUrlError("https://git.example.com/api/v3"))
        assertNull(forgeHostUrlError("http://localhost:3000"))
        assertTrue(isValidForgeHostUrl("gitlab.corp.internal"))
    }

    @Test fun forge_host_url_validation_rejects_garbage() {
        assertEquals("URL can't contain spaces", forgeHostUrlError("not a url"))
        assertEquals("URL must start with http:// or https://", forgeHostUrlError("ftp://evil.example"))
        assertFalse(isValidForgeHostUrl(":::"))
        assertFalse(isValidForgeHostUrl("just-a-word"))
        assertFalse(isValidForgeHostUrl("http://"))
        assertNotNull(forgeHostUrlError("just-a-word"))
        // supermux adds the API path itself (inventory bug 8): the hint never asks for one.
        assertFalse(forgeHostUrlError("just-a-word")!!.contains("/api"))
    }

    @Test fun importable_kinds_skips_unavailable_and_already_connected() {
        val cli = ForgeCliStatus(
            github = ForgeCliPresence(available = true, login = "alice"),
            gitlab = ForgeCliPresence(available = false),
        )
        assertEquals(listOf("github"), importableKinds(cli, emptyList()))
        val connected = listOf(ForgeConnection(id = "c1", kind = "github", account = ForgeAccount(login = "alice")))
        assertTrue(importableKinds(cli, connected).isEmpty())
        assertTrue(importableKinds(null, emptyList()).isEmpty())
    }

    @Test fun cli_name_maps_kind() {
        assertEquals("gh", cliName("github"))
        assertEquals("glab", cliName("gitlab"))
    }

    // ── the list ──────────────────────────────────────────────────────────────────────────────

    @Test fun with_no_accounts_the_add_item_is_all_there_is() = runComposeUiTest {
        gitContent { Screen() }
        awaitTag("git_hosting_add_account")
        onNodeWithTag("git_hosting_screen").assertExists()
        onNodeWithText("Add a GitHub or GitLab account").assertIsDisplayed()
        onAllNodesWithTag("forge_row_c1").assertCountEquals(0)
    }

    @Test fun connection_list_renders_handle_host_transport_and_disconnect() = runComposeUiTest {
        gitContent {
            Screen(
                forgesLoad = {
                    response(
                        listOf(
                            conn(id = "gh1", login = "alice"),
                            conn(id = "gl1", kind = "gitlab", login = "bob", host = "", transport = "ssh"),
                        ),
                    )
                },
            )
        }
        awaitTag("forge_row_gh1")
        onNodeWithText("@alice").assertIsDisplayed()
        onNodeWithText("github.com · HTTPS").assertIsDisplayed()
        onNodeWithText("@bob").assertIsDisplayed()
        // An empty host is the forge's public one.
        onNodeWithText("gitlab.com · SSH").assertIsDisplayed()
        onNodeWithTag("forge_ok_gh1").assertExists()
        onAllNodesWithText("Connected").assertCountEquals(2)
        onNodeWithTag("forge_disconnect_gh1").assertIsDisplayed()
        onNodeWithTag("git_hosting_add_account").assertIsDisplayed()
    }

    @Test fun a_failed_load_says_so_and_tries_again() = runComposeUiTest {
        val loads = AtomicInteger(0)
        gitContent {
            Screen(forgesLoad = {
                loads.incrementAndGet()
                null
            })
        }
        awaitTag("git_hosting_error")
        onNodeWithText("Couldn't reach this machine.").assertIsDisplayed()
        assertEquals(1, loads.get())
        onNodeWithTag("git_hosting_retry").performClick()
        waitUntil(timeoutMillis = 5_000) { loads.get() >= 2 }
    }

    @Test fun needs_reconnect_offers_reconnect_which_opens_the_sheet_for_that_forge() = runComposeUiTest {
        gitContent {
            Screen(forgesLoad = { response(listOf(conn(id = "stale", kind = "gitlab", login = "stale", status = "needs_reconnect"))) })
        }
        awaitTag("forge_reconnect_stale")
        onNodeWithTag("forge_ok_stale").assertDoesNotExist()
        onNodeWithTag("forge_reconnect_stale").performClick()
        awaitTag("git_hosting_add_dialog")
        onNodeWithText("Connect GitLab").assertExists()
    }

    // ── the add sheet ─────────────────────────────────────────────────────────────────────────

    @Test fun the_add_item_opens_the_sheet_with_a_token_field() = runComposeUiTest {
        gitContent { Screen() }
        openAddSheet()
        onNodeWithContentDescription("Paste a token").assertExists()
        onNodeWithTag("git_hosting_kind_github").assertExists()
        onNodeWithTag("git_hosting_kind_gitlab").assertExists()
        onNodeWithTag("git_hosting_token").assertExists()
        onNodeWithTag("git_hosting_connect").assertIsNotEnabled()
        // No gh / glab on the machine: no "use the login" items.
        onNodeWithTag("git_hosting_import_github").assertDoesNotExist()
        onNodeWithTag("git_hosting_import_gitlab").assertDoesNotExist()
    }

    @Test fun the_gh_and_glab_logins_are_offered_only_where_the_machine_has_them() = runComposeUiTest {
        val imported = AtomicReference<String?>(null)
        gitContent {
            Screen(
                forgesLoad = { response(cli = ForgeCliStatus(github = ForgeCliPresence(available = true, login = "cliuser"))) },
                forgeImport = { kind, _ ->
                    imported.set(kind)
                    true
                },
            )
        }
        openAddSheet()
        onNodeWithText("Use the gh login on this machine").assertExists()
        onNodeWithText("@cliuser, found on that machine").assertExists()
        onNodeWithTag("git_hosting_import_gitlab").assertDoesNotExist()
        onNodeWithTag("git_hosting_import_github").performClick()
        waitUntil(timeoutMillis = 5_000) { imported.get() == "github" }
        // A login that worked closes the sheet.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("git_hosting_add_dialog").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun a_failed_import_keeps_the_sheet_open_with_an_error() = runComposeUiTest {
        gitContent {
            Screen(forgesLoad = { response(cli = ForgeCliStatus(gitlab = ForgeCliPresence(available = true))) })
        }
        openAddSheet()
        onNodeWithTag("git_hosting_import_gitlab").performClick()
        awaitTag("git_hosting_add_error")
        onNodeWithText("Couldn't use the glab login on this machine — is it signed in?").assertExists()
        onNodeWithTag("git_hosting_add_dialog").assertExists()
        onNodeWithTag("git_hosting_token").assertExists()
    }

    @Test fun connect_failure_surfaces_an_error_in_the_sheet() = runComposeUiTest {
        gitContent { Screen() }
        openAddSheet()
        onNodeWithTag("git_hosting_token").performTextInput("bad-token")
        onNodeWithTag("git_hosting_connect").performClick()
        awaitTag("git_hosting_add_error")
        onNodeWithText("Couldn't connect — check your token and try again.").assertExists()
    }

    @Test fun connect_success_closes_the_sheet_and_reloads_the_list() = runComposeUiTest {
        val loads = AtomicInteger(0)
        val sent = AtomicReference<List<String?>>()
        gitContent {
            Screen(
                forgesLoad = {
                    if (loads.incrementAndGet() == 1) response() else response(listOf(conn(id = "new1", login = "newuser")))
                },
                forgeAdd = { kind, token, host, transport ->
                    sent.set(listOf(kind, token, host, transport))
                    true
                },
            )
        }
        openAddSheet()
        onNodeWithTag("git_hosting_kind_gitlab").performClick()
        onNodeWithTag("git_hosting_token").performTextInput("good-pat")
        onNodeWithTag("git_hosting_connect").performClick()
        awaitTag("forge_row_new1")
        assertEquals(listOf("gitlab", "good-pat", null, "https"), sent.get())
        onNodeWithText("@newuser").assertIsDisplayed()
        onNodeWithTag("git_hosting_add_dialog").assertDoesNotExist()
    }

    /** §10.8: the host and HTTPS · SSH apply to both forges, under "Your own server"; no `/api/v3` to type. */
    @Test fun your_own_server_is_there_for_github_and_gitlab() = runComposeUiTest {
        val sent = AtomicReference<List<String?>>()
        gitContent {
            Screen(forgeAdd = { kind, token, host, transport ->
                sent.set(listOf(kind, token, host, transport))
                false
            })
        }
        openAddSheet()
        onNodeWithTag("git_hosting_host_url").assertDoesNotExist()
        onNodeWithText("Your own server").assertExists()
        onNodeWithTag("git_hosting_advanced_toggle").performClick()
        awaitTag("git_hosting_host_url")
        onNodeWithText("github.example.com").assertExists()
        onAllNodesWithText("/api/v3", substring = true).assertCountEquals(0)
        onNodeWithTag("git_hosting_transport_ssh").assertExists()
        // GitHub over SSH is not experimental…
        onNodeWithTag("git_hosting_transport_ssh").performClick()
        onNodeWithText("SSH for GitLab is experimental.").assertDoesNotExist()

        onNodeWithTag("git_hosting_kind_gitlab").performClick()
        waitForIdle()
        // …the same fields stay for GitLab, and SSH there keeps today's note.
        onNodeWithTag("git_hosting_host_url").assertExists()
        onNodeWithText("gitlab.example.com").assertExists()
        onNodeWithText("SSH for GitLab is experimental.").assertExists()

        onNodeWithTag("git_hosting_host_url").performTextInput("git.acme.dev")
        onNodeWithTag("git_hosting_token").performTextInput("glpat-x")
        onNodeWithTag("git_hosting_connect").performClick()
        waitUntil(timeoutMillis = 5_000) { sent.get() != null }
        assertEquals(listOf("gitlab", "glpat-x", "git.acme.dev", "ssh"), sent.get())
    }

    @Test fun an_invalid_server_blocks_connect_and_says_why() = runComposeUiTest {
        gitContent { Screen(forgeAdd = { _, _, _, _ -> true }) }
        openAddSheet()
        onNodeWithTag("git_hosting_advanced_toggle").performClick()
        awaitTag("git_hosting_host_url")
        onNodeWithTag("git_hosting_host_url").performTextInput("not a url")
        onNodeWithTag("git_hosting_token").performTextInput("pat-xxx")
        waitForIdle()
        onNodeWithText("URL can't contain spaces").assertExists()
        onNodeWithTag("git_hosting_connect").assertIsNotEnabled()
    }

    @Test fun cancel_is_refused_while_a_connect_is_in_flight() = runComposeUiTest {
        val gate = CompletableDeferred<Boolean>()
        gitContent { Screen(forgeAdd = { _, _, _, _ -> gate.await() }) }
        openAddSheet()
        onNodeWithTag("git_hosting_token").performTextInput("pat-xxx")
        onNodeWithTag("git_hosting_connect").performClick()
        awaitText("Connecting…")
        onNodeWithTag("git_hosting_add_cancel").assertIsNotEnabled()
        onNodeWithTag("git_hosting_add_dialog").assertExists()
        gate.complete(false)
        awaitTag("git_hosting_add_error")
    }

    // ── disconnect ────────────────────────────────────────────────────────────────────────────

    @Test fun disconnect_confirm_calls_remove_and_drops_the_row() = runComposeUiTest {
        val removed = AtomicReference<String?>(null)
        val loads = AtomicInteger(0)
        gitContent {
            Screen(
                forgesLoad = {
                    if (loads.incrementAndGet() == 1) response(listOf(conn(id = "c-rm", login = "gone"))) else response()
                },
                forgeRemove = { id ->
                    removed.set(id)
                    true
                },
            )
        }
        awaitTag("forge_disconnect_c-rm")
        onNodeWithTag("forge_disconnect_c-rm").performClick()
        awaitTag("git_hosting_disconnect_dialog")
        onNodeWithText("Disconnect @gone?").assertExists()
        onNodeWithTag("git_hosting_disconnect_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { removed.get() == "c-rm" }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("forge_row_c-rm").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("git_hosting_disconnect_dialog").assertDoesNotExist()
    }

    @Test fun disconnect_cancel_keeps_the_account() = runComposeUiTest {
        val removed = AtomicReference<String?>(null)
        gitContent {
            Screen(
                forgesLoad = { response(listOf(conn(id = "c1"))) },
                forgeRemove = { id ->
                    removed.set(id)
                    true
                },
            )
        }
        awaitTag("forge_disconnect_c1")
        onNodeWithTag("forge_disconnect_c1").performClick()
        awaitTag("git_hosting_disconnect_cancel")
        onNodeWithTag("git_hosting_disconnect_cancel").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_disconnect_dialog").assertDoesNotExist()
        onNodeWithTag("forge_row_c1").assertExists()
        assertNull(removed.get())
    }

    @Test fun a_refused_disconnect_keeps_the_row_and_says_so() = runComposeUiTest {
        gitContent {
            Screen(
                forgesLoad = { response(listOf(conn(id = "c-keep", login = "sticky"))) },
                forgeRemove = { false },
            )
        }
        awaitTag("forge_disconnect_c-keep")
        onNodeWithTag("forge_disconnect_c-keep").performClick()
        awaitTag("git_hosting_disconnect_confirm")
        onNodeWithTag("git_hosting_disconnect_confirm").performClick()
        awaitTag("git_hosting_error")
        onNodeWithText("Couldn't disconnect — try again.").assertExists()
        onNodeWithTag("forge_row_c-keep").assertExists()
        onNodeWithText("@sticky").assertExists()
    }
}
