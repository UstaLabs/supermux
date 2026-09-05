package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The shared [GitHostingScreen] (cluster E3) — desktop's suite, moved by name.
 *
 * Covers empty/list/error load, the add form (PAT connect failure + success, CLI import failure),
 * the self-hosted URL validation, the disconnect confirm that keeps the row when the broker
 * rejects it, and the pure helpers — plus the Compact branch Android contributed (its own top bar
 * with the "+" action, and the add form as a bottom sheet). Desktop's `AppShell` hub wiring stays
 * in `:desktop` (`GitHostingHubTest`).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class GitHostingScreenTest {

    /**
     * `setContent` with a [FakePlatform] and an explicit width class. Defaults are the DESKTOP
     * shape, so the moved suite asserts exactly what it always asserted.
     */
    private fun ComposeUiTest.gitContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform = FakePlatform(), pointer = pointer, widthClass = widthClass) {
        content()
    }

    /**
     * The screen under its pre-E3 argument shape.
     *
     * The four suspend lambdas now travel as one [GitHostingActions]; keeping the old names here
     * means every moved test reads exactly as it did on desktop.
     */
    @Composable
    private fun GitHostingScreenUnderTest(
        forgesLoad: suspend () -> ForgeConnectionsResponse?,
        forgeAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean,
        forgeImport: suspend (kind: String, transport: String) -> Boolean,
        forgeRemove: suspend (id: String) -> Boolean,
        /** The Compact suite flips this to false to prove the screen brings Android's own bar. */
        topBarShown: Boolean = true,
        onBack: () -> Unit = {},
    ) = GitHostingScreen(
        actions = GitHostingActions(
            forgesLoad = forgesLoad,
            forgeAdd = forgeAdd,
            forgeImport = forgeImport,
            forgeRemove = forgeRemove,
        ),
        onBack = onBack,
        topBarShown = topBarShown,
    )

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

    @Test fun scopes_hint_github_public_vs_enterprise() {
        assertEquals("Contents + Administration (read & write)", scopesHint("github", ""))
        assertEquals("Contents + Administration (read & write)", scopesHint("github", "github.com"))
        assertEquals("repo, read:org", scopesHint("github", "github.acme.com/api/v3"))
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

    @Test fun empty_state_shows_connect_a_git_host_strings() = runComposeUiTest {
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
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
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
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
                    forgeRemove = { true },
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
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = {
                        loads.incrementAndGet()
                        null
                    },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
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
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
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
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
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
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = {
                        val n = loads.incrementAndGet()
                        if (n == 1) response()
                        else response(listOf(conn(id = "new1", login = "newuser")))
                    },
                    forgeAdd = { kind, token, _, _ ->
                        kind == "github" && token == "good-pat"
                    },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
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
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = { response(listOf(conn(id = "c-rm", login = "gone"))) },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { id -> removed.set(id); true },
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
        waitUntil(timeoutMillis = 5_000) { removed.get() == "c-rm" }
        assertEquals("c-rm", removed.get())
        // Successful remove drops the row.
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_row_c-rm").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Test fun disconnect_failure_keeps_row_and_surfaces_error() = runComposeUiTest {
        val removed = AtomicReference<String?>(null)
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = { response(listOf(conn(id = "c-keep", login = "sticky"))) },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { id ->
                        removed.set(id)
                        false // broker rejected delete
                    },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_disconnect_c-keep").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("forge_disconnect_c-keep").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_disconnect_confirm").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("c-keep", removed.get())
        onNodeWithText("Couldn't disconnect — try again.").assertIsDisplayed()
        // Row must still be present after a failed delete.
        onNodeWithTag("forge_row_c-keep").assertIsDisplayed()
        onNodeWithText("@sticky").assertIsDisplayed()
    }

    @Test fun cli_import_button_shown_when_cli_available() = runComposeUiTest {
        val imported = AtomicBoolean(false)
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
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
                    forgeRemove = { true },
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

    @Test fun cli_import_failure_keeps_dialog_open_with_error() = runComposeUiTest {
        // Opens the add dialog (which has the CLI import path) and forces import to fail —
        // the old bug closed the dialog with no error on a 500.
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = {
                        response(
                            cli = ForgeCliStatus(
                                github = ForgeCliPresence(available = true, login = "cliuser"),
                            ),
                        )
                    },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
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
        // Open add dialog so the in-dialog CLI import button is present.
        onNodeWithTag("git_hosting_manual_github").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_add_dialog").assertIsDisplayed()
        onNodeWithTag("git_hosting_cli_import").assertIsDisplayed()
        onNodeWithTag("git_hosting_cli_import").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_add_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // Dialog stays open; error is visible; token field still there.
        onNodeWithTag("git_hosting_add_dialog").assertIsDisplayed()
        onNodeWithText("Couldn't import from gh — is it logged in?").assertIsDisplayed()
        onNodeWithTag("git_hosting_token").assertIsDisplayed()
    }

    @Test fun invalid_self_host_url_blocks_connect_and_shows_feedback() = runComposeUiTest {
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> true },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
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
        onNodeWithTag("git_hosting_advanced_toggle").performClick()
        waitForIdle()
        onNodeWithTag("git_hosting_host_url").performTextInput("not a url")
        onNodeWithTag("git_hosting_token").performTextInput("pat-xxx")
        waitForIdle()
        // supportingText lives in the unmerged tree under OutlinedTextField.
        onNodeWithTag("git_hosting_host_url_error", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("URL can't contain spaces", useUnmergedTree = true).assertIsDisplayed()
        // Connect stays disabled while the host URL is invalid.
        onNodeWithTag("git_hosting_connect").assertIsNotEnabled()
    }

    @Test fun needs_reconnect_shows_badge_and_reconnect() = runComposeUiTest {
        gitContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = {
                        response(listOf(conn(id = "stale", login = "stale", status = "needs_reconnect")))
                    },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
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

    // ── Compact branch (Android's phone shape) ──────────────────────────────────────────────────

    /**
     * On a phone the hub pushes the detail without a bar, so the screen brings Android's: a title,
     * a working Back and the "+" action that opens the add form — as a bottom sheet, not a dialog.
     */
    @Test fun compact_screen_paints_its_own_top_bar_and_add_action() = runComposeUiTest {
        var backs = 0
        gitContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
                    topBarShown = false,
                    onBack = { backs++ },
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
        onNodeWithText("Git hosting").assertIsDisplayed()
        onNodeWithTag("git_hosting_back").assertIsDisplayed()
        onNodeWithTag("git_hosting_add_action").performClick()
        waitForIdle()
        // Same form, same tags — only the container differs (ModalBottomSheet on a phone).
        onNodeWithTag("git_hosting_add_dialog").assertIsDisplayed()
        onNodeWithTag("git_hosting_token").assertIsDisplayed()
    }

    /** A tablet's rail already painted the title — the screen must not add a second one. */
    @Test fun compact_top_bar_suppressed_when_the_hub_painted_one() = runComposeUiTest {
        gitContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitHostingScreenUnderTest(
                    forgesLoad = { response() },
                    forgeAdd = { _, _, _, _ -> false },
                    forgeImport = { _, _ -> false },
                    forgeRemove = { true },
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("git_hosting_back").assertDoesNotExist()
        onNodeWithTag("git_hosting_screen").assertIsDisplayed()
    }
}
