package dev.supermux.desktop.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.workspace.SettingsSection
import dev.supermux.desktop.workspace.WorkspaceRoot
import dev.supermux.desktop.workspace.WorkspaceStateStore
import dev.supermux.desktop.workspace.WorkspaceUiState
import dev.supermux.net.BrokerApi
import dev.supermux.net.ForgeAccount
import dev.supermux.net.ForgeCliPresence
import dev.supermux.net.ForgeCliStatus
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop-parity Task 4: [GitHostingScreen] accounts UI + Settings hub wiring.
 *
 * Covers empty/list/error load, add dialog (PAT connect failure + success), disconnect confirm,
 * CLI import, pure helpers, and MockEngine-backed DesktopAppState forge wrappers.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class GitHostingScreenTest {

    // ── pure helpers ────────────────────────────────────────────────────────────────────────────

    @Test fun scopes_hint_github_public_vs_enterprise() {
        assertEquals("Contents + Administration (read & write)", scopesHint("github", ""))
        assertEquals("Contents + Administration (read & write)", scopesHint("github", "github.com"))
        assertEquals("repo, read:org", scopesHint("github", "github.acme.com/api/v3"))
        assertEquals("api", scopesHint("gitlab", ""))
    }

    @Test fun importable_kinds_skips_unavailable_and_already_connected() {
        val cli = ForgeCliStatus(
            github = ForgeCliPresence(available = true, login = "alice"),
            gitlab = ForgeCliPresence(available = false),
        )
        assertEquals(listOf("github"), importableKinds(cli, emptyList()))
        val connected = listOf(
            ForgeConnection(
                id = "c1", kind = "github",
                account = ForgeAccount(login = "alice"),
            ),
        )
        assertTrue(importableKinds(cli, connected).isEmpty())
        assertTrue(importableKinds(null, emptyList()).isEmpty())
    }

    @Test fun cli_name_maps_kind() {
        assertEquals("gh", cliName("github"))
        assertEquals("glab", cliName("gitlab"))
    }

    // ── screen harness ──────────────────────────────────────────────────────────────────────────

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

    @Test fun empty_state_shows_connect_a_git_host_strings() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_empty_title").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Connect a Git host").assertIsDisplayed()
        onNodeWithText("Connect manually").assertIsDisplayed()
        onNodeWithTag("git_hosting_manual_github").assertIsDisplayed()
        onNodeWithTag("git_hosting_manual_gitlab").assertIsDisplayed()
    }

    @Test fun connection_list_renders_login_and_disconnect() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = {
                        response(
                            listOf(
                                conn(id = "gh1", login = "alice"),
                                conn(id = "gl1", kind = "gitlab", login = "bob", host = "gitlab.com"),
                            ),
                        )
                    },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_row_gh1").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("@alice").assertIsDisplayed()
        onNodeWithText("@bob").assertIsDisplayed()
        onNodeWithTag("git_hosting_add_account").assertIsDisplayed()
        onNodeWithTag("forge_disconnect_gh1").assertIsDisplayed()
    }

    @Test fun load_failure_shows_error_with_retry() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = {
                        loads.incrementAndGet()
                        null
                    },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Couldn't load connections").assertIsDisplayed()
        assertTrue(loads.get() >= 1)
        onNodeWithTag("git_hosting_retry").performClick()
        waitForIdle()
        assertTrue(loads.get() >= 2)
    }

    @Test fun manual_github_opens_add_dialog() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_manual_github").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("git_hosting_manual_github").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_add_dialog").assertIsDisplayed()
        onNodeWithText("Add a Git account").assertIsDisplayed()
        onNodeWithTag("git_hosting_token").assertIsDisplayed()
        onNodeWithTag("git_hosting_connect").assertIsDisplayed()
    }

    @Test fun connect_failure_surfaces_error_in_dialog() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_manual_github").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("git_hosting_manual_github").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_token").performTextInput("bad-token")
        onNodeWithTag("git_hosting_connect").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_add_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Couldn't connect — check your token and try again.").assertIsDisplayed()
    }

    @Test fun connect_success_closes_dialog_and_reloads_list() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = {
                        val n = loads.incrementAndGet()
                        if (n == 1) response()
                        else response(listOf(conn(id = "new1", login = "newuser")))
                    },
                    forgeAdd = { kind, token, _, _ ->
                        kind == "github" && token == "good-pat"
                    },
                    forgeImport = { _, _ -> false },
                    forgeRemove = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_manual_github").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("git_hosting_manual_github").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_token").performTextInput("good-pat")
        onNodeWithTag("git_hosting_connect").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_row_new1").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("@newuser").assertIsDisplayed()
        onNodeWithTag("git_hosting_add_dialog").assertDoesNotExist()
    }

    @Test fun disconnect_confirm_calls_remove() = runComposeUiTest {
        val removed = AtomicReference<String?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = { response(listOf(conn(id = "c-rm", login = "gone"))) },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { removed.set(it) },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_disconnect_c-rm").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("forge_disconnect_c-rm").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_disconnect_dialog").assertIsDisplayed()
        onNodeWithText("Disconnect @gone?").assertIsDisplayed()
        onNodeWithTag("git_hosting_disconnect_confirm").performClick()
        waitForIdle()
        assertEquals("c-rm", removed.get())
    }

    @Test fun cli_import_button_shown_when_cli_available() = runComposeUiTest {
        val imported = AtomicBoolean(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = {
                        response(
                            cli = ForgeCliStatus(
                                github = ForgeCliPresence(available = true, login = "cliuser"),
                            ),
                        )
                    },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { kind, _ ->
                        if (kind == "github") imported.set(true)
                        true
                    },
                    forgeRemove = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_import_github").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Import from gh (@cliuser)").assertIsDisplayed()
        onNodeWithText("or connect manually").assertIsDisplayed()
        onNodeWithTag("git_hosting_import_github").performClick()
        waitForIdle()
        assertTrue(imported.get())
    }

    @Test fun needs_reconnect_shows_badge_and_reconnect() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreen(
                    forgesLoad = {
                        response(listOf(conn(id = "stale", login = "stale", status = "needs_reconnect")))
                    },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_row_stale").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("reconnect").assertIsDisplayed()
        onNodeWithTag("forge_reconnect_stale").assertIsDisplayed()
        onNodeWithTag("forge_reconnect_stale").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_add_dialog").assertIsDisplayed()
    }

    // ── hub + MockEngine wiring ─────────────────────────────────────────────────────────────────

    private val tempFiles = mutableListOf<Path>()

    @AfterTest fun cleanup() {
        tempFiles.forEach { Files.deleteIfExists(it) }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("git_hosting_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun appWithForges(body: String): DesktopAppState {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                req.url.encodedPath == "/forge/connections" && req.method == HttpMethod.Get ->
                    respond(ByteReadChannel(body), HttpStatusCode.OK, jsonHeaders)
                req.url.encodedPath == "/agents/status" ->
                    respond("[]", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    @Test fun settings_hub_git_hosting_section_loads_real_broker_payload() = runComposeUiTest {
        val body = """
            {"connections":[{"id":"live1","kind":"github","host":"github.com","account":{"login":"liveuser"},"source":"pat","transport":"https","status":"ok"}],"cli":null}
        """.trimIndent()
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.GitHosting) }
        val app = appWithForges(body)
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
        onNodeWithTag("settings_hub").assertIsDisplayed()
        onNodeWithTag("settings_section_githosting").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_screen").assertIsDisplayed()
                onNodeWithTag("forge_row_live1").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("@liveuser").assertIsDisplayed()
        assertEquals(SettingsSection.GitHosting, ui.settingsSection)
    }

    @Test fun rail_can_switch_to_git_hosting_from_agents() = runComposeUiTest {
        val body = """{"connections":[],"cli":null}"""
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.Agents) }
        val app = appWithForges(body)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state-rail")),
                    LauncherStore(tempPath("launcher-rail")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_section_githosting").performClick()
        waitForIdle()
        assertEquals(SettingsSection.GitHosting, ui.settingsSection)
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_screen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Connect a Git host").assertIsDisplayed()
    }
}
