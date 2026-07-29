package dev.supermux.desktop.usage

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
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
import dev.supermux.net.ClaudeExtraUsage
import dev.supermux.net.ClaudeUsage
import dev.supermux.net.ClaudeWindow
import dev.supermux.net.CodexCredits
import dev.supermux.net.CodexResetResult
import dev.supermux.net.CodexUsage
import dev.supermux.net.CodexWindow
import dev.supermux.net.CursorUsage
import dev.supermux.net.UsageResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M4f Task 2 — the UsageScreen overlay, its three provider cards (ClaudeUsageCard/CodexUsageCard/
 * CursorUsageCard) fed from a typed `UsageResponse`, the Codex banked-reset redeem flow, and the
 * overlay wiring into WorkspaceRoot (`ui.usageOpen`). Pure reset-formatter tests live in
 * [UsageResetFormatTest]; this file exercises the composables + the overlay via [runComposeUiTest],
 * mirroring [dev.supermux.desktop.session.ArchivedScreenTest]'s two-layer shape.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UsageScreenTest {

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────
    // `resetsAt` values deliberately kept out of the reset-formatter's tested branches (or null) —
    // this file asserts labels/percentages/structure, not reset-line wall-clock text (that's
    // UsageResetFormatTest's job).

    private fun fixtureUsage(
        codexResetCredits: Int = 3,
        codexWindows: List<CodexWindow> = listOf(
            CodexWindow(id = "primary", used = 30.0, resetsAt = null, label = "5-hour window", windowSeconds = 18_000.0),
            CodexWindow(id = "secondary", used = 60.0, resetsAt = null, label = "7-day window", windowSeconds = 604_800.0),
        ),
        sevenDayFable: ClaudeWindow? = ClaudeWindow(used = 5.0, resetsAt = null),
        errors: Map<String, String> = emptyMap(),
    ) = UsageResponse(
        claude = ClaudeUsage(
            fiveHour = ClaudeWindow(used = 12.0, resetsAt = null),
            sevenDay = ClaudeWindow(used = 40.0, resetsAt = null),
            sevenDaySonnet = ClaudeWindow(used = 8.0, resetsAt = null),
            sevenDayFable = sevenDayFable,
            extraUsage = ClaudeExtraUsage(enabled = true, monthlyLimit = 100.0, usedCredits = 10.0, currency = "USD"),
        ),
        codex = CodexUsage(
            plan = "pro",
            windows = codexWindows,
            credits = CodexCredits(hasCredits = true, balance = "5.00"),
            limitReached = false,
            resetCredits = codexResetCredits,
        ),
        cursor = CursorUsage(
            totalPercentUsed = 20.0,
            totalSpendCents = 500.0,
            includedCents = 2000.0,
            limitCents = 2500.0,
            spendAvailable = true,
            billingCycleEnd = null,
        ),
        errors = errors,
    )

    // ── (1) UsageScreen: loading / unable-to-load / the three cards ─────────────────────────────────

    @Test fun loading_and_usage_null_shows_a_spinner_not_the_unable_to_load_text() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = null, loading = true, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("usage_spinner").assertIsDisplayed()
        onNodeWithText("Unable to load usage data.").assertDoesNotExist()
    }

    @Test fun usage_null_and_not_loading_shows_the_unable_to_load_text() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = null, loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithText("Unable to load usage data.").assertIsDisplayed()
        onNodeWithTag("usage_spinner").assertDoesNotExist()
    }

    @Test fun renders_all_three_provider_cards_from_a_representative_usage_response() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(), loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        // `assertExists` (not `assertIsDisplayed`): the Column is scrollable and the headless test
        // canvas is short enough that the Cursor card sits below the fold — it's composed either
        // way, which is what these assertions are checking (structural rendering, not scroll
        // position).
        onNodeWithTag("usage_card_claude").assertExists()
        onNodeWithTag("usage_card_codex").assertExists()
        onNodeWithTag("usage_card_cursor").assertExists()
        // Row labels + percentages, ported verbatim from Android's MoreScreens.kt cards. "5-hour
        // window"/"7-day window" are used by BOTH the Claude and Codex cards (per Android's
        // ClaudeUsageCard/CodexUsageCard) — assert the count, not a unique match.
        onAllNodesWithText("5-hour window").assertCountEquals(2)
        onNodeWithText("12% used").assertExists()
        onAllNodesWithText("7-day window").assertCountEquals(2)
        onNodeWithText("7-day Sonnet").assertExists()
        onNodeWithText("7-day Fable").assertExists()
        onNodeWithText("Extra usage").assertExists()
        onNodeWithText("$10.00 / $100.00").assertExists()
        onNodeWithText("Credits balance").assertExists()
        onNodeWithText("5.00 credits").assertExists()
        onNodeWithText("🎟️ Resets banked").assertExists()
        onNodeWithText("Spend").assertExists()
        onNodeWithText("$5.00 / $20.00 included").assertExists()
    }

    @Test fun back_button_fires_on_back() = runComposeUiTest {
        var backCalled = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(), loading = false, onBack = { backCalled = true }, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("usage_back").performClick()
        assertTrue(backCalled)
    }

    @Test fun codex_renders_only_the_duration_label_returned_by_the_broker() = runComposeUiTest {
        val currentWindow = CodexWindow(
            id = "primary",
            used = 25.0,
            resetsAt = null,
            label = "7-day window",
            windowSeconds = 604_800.0,
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(codexWindows = listOf(currentWindow)),
                    loading = false,
                    onBack = {},
                    onRedeem = { null },
                )
            }
        }
        waitForIdle()
        // Claude owns the only 5-hour row; Codex contributes only the live 7-day window.
        onAllNodesWithText("5-hour window").assertCountEquals(1)
        onAllNodesWithText("7-day window").assertCountEquals(2)
        onNodeWithText("25% used").assertExists()
    }

    @Test fun cursor_hides_spend_when_the_provider_does_not_return_it() = runComposeUiTest {
        val fixture = fixtureUsage()
        val usage = fixture.copy(cursor = requireNotNull(fixture.cursor).copy(spendAvailable = false))
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = usage, loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithText("Spend").assertDoesNotExist()
        onNodeWithText("$5.00 / $20.00 included").assertDoesNotExist()
    }

    // ── (2) null sevenDayFable hides the row; a present one shows it ────────────────────────────────

    @Test fun null_seven_day_fable_hides_that_row() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(sevenDayFable = null), loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithText("7-day Fable").assertDoesNotExist()
        // Sonnet stays present — only Fable was nulled.
        onNodeWithText("7-day Sonnet").assertIsDisplayed()
    }

    @Test fun present_seven_day_fable_shows_the_row() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(sevenDayFable = ClaudeWindow(used = 5.0, resetsAt = null)),
                    loading = false, onBack = {}, onRedeem = { null },
                )
            }
        }
        waitForIdle()
        onNodeWithText("7-day Fable").assertIsDisplayed()
    }

    // ── (3) a provider present in `errors` shows its error text, not a blank card ───────────────────

    @Test fun a_provider_in_errors_shows_its_error_text_not_a_blank_card() = runComposeUiTest {
        val usage = UsageResponse(
            claude = null,
            codex = null,
            cursor = null,
            // One entry per card UsageScreen actually renders, or the ones left out
            // fall back to "Not available" and the assertion below fails. Note there
            // is no OpenCode card on desktop even though UsageResponse has the field.
            errors = mapOf(
                "claude" to "not configured",
                "codex" to "no api key",
                "cursor" to "request timed out",
                "grok" to "no credits",
            ),
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = usage, loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithText("not configured").assertIsDisplayed()
        onNodeWithText("no api key").assertIsDisplayed()
        onNodeWithText("request timed out").assertIsDisplayed()
        onNodeWithText("Not available").assertDoesNotExist()
    }

    @Test fun a_provider_absent_from_usage_and_errors_falls_back_to_not_available() = runComposeUiTest {
        val usage = UsageResponse(claude = null, codex = null, cursor = null, errors = emptyMap())
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = usage, loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        // Every card falls back to "Not available" — assert the count, not a single node
        // (onNodeWithText requires a UNIQUE match). One per card UsageScreen renders:
        // claude, codex, cursor, grok. Bump this when a provider card is added.
        onAllNodesWithText("Not available").assertCountEquals(4)
    }

    // ── (4) the Codex "Use a reset" button + confirm dialog ─────────────────────────────────────────

    @Test fun redeem_button_shown_only_when_reset_credits_positive() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(codexResetCredits = 3), loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("codex_redeem_button").assertIsDisplayed()
    }

    @Test fun redeem_button_hidden_when_reset_credits_is_zero() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(codexResetCredits = 0), loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("codex_redeem_button").assertDoesNotExist()
    }

    @Test fun firing_the_redeem_button_confirms_then_calls_on_redeem() = runComposeUiTest {
        var redeemCalled = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(codexResetCredits = 3),
                    loading = false,
                    onBack = {},
                    onRedeem = { redeemCalled = true; CodexResetResult(code = "reset", windowsReset = 1) },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("codex_redeem_button").performClick()
        waitForIdle()
        onNodeWithText("Use a banked reset?").assertIsDisplayed()
        assertFalse(redeemCalled) // opening the confirm dialog does not redeem yet
        onNodeWithTag("codex_redeem_confirm").performClick()
        waitForIdle()
        assertTrue(redeemCalled)
        onNodeWithTag("codex_redeem_note").assertIsDisplayed()
        onNodeWithText("✓ Reset — cleared 1 window").assertIsDisplayed()
    }

    @Test fun canceling_the_redeem_dialog_does_not_call_on_redeem() = runComposeUiTest {
        var redeemCalled = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(codexResetCredits = 3),
                    loading = false,
                    onBack = {},
                    onRedeem = { redeemCalled = true; null },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("codex_redeem_button").performClick()
        waitForIdle()
        onNodeWithTag("codex_redeem_cancel").performClick()
        waitForIdle()
        assertFalse(redeemCalled)
        onNodeWithText("Use a banked reset?").assertDoesNotExist()
    }

    // ── (5) codexResetNote — every code branch ───────────────────────────────────────────────────────

    @Test fun codex_reset_note_covers_every_code() {
        assertEquals("Reset failed", codexResetNote(null))
        assertEquals("✓ Reset — cleared 3 windows", codexResetNote(CodexResetResult(code = "reset", windowsReset = 3)))
        assertEquals("✓ Reset — cleared 1 window", codexResetNote(CodexResetResult(code = "reset", windowsReset = 1)))
        assertEquals("Nothing to reset right now", codexResetNote(CodexResetResult(code = "nothing_to_reset")))
        assertEquals("No banked resets left", codexResetNote(CodexResetResult(code = "no_credit")))
        assertEquals("That reset was already redeemed", codexResetNote(CodexResetResult(code = "already_redeemed")))
        assertEquals("Reset request completed", codexResetNote(CodexResetResult(code = "something_else")))
    }

    // ── (6) overlay wiring into WorkspaceRoot ───────────────────────────────────────────────────────

    private val tempFiles = mutableListOf<java.nio.file.Path>()

    private fun tempPath(name: String): java.nio.file.Path {
        val f = Files.createTempFile("usage_screen_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    @AfterTest fun cleanup() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** A [DesktopAppState] whose HTTP serves GET /usage + POST /usage/codex/reset. */
    private fun appForUsage(initialResetCredits: Int = 3, redeemedResetCredits: Int = 2): DesktopAppState {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                req.method == HttpMethod.Get && req.url.encodedPath == "/usage" -> respond(
                    """
                    {
                      "claude": {"fiveHour": {"used": 12.0}, "sevenDay": {"used": 40.0}},
                      "codex": {
                        "plan": "pro",
                        "windows": [
                          {"used": 30.0, "label": "5-hour window", "windowSeconds": 18000.0},
                          {"used": 60.0, "label": "7-day window", "windowSeconds": 604800.0}
                        ],
                        "limitReached": false,
                        "resetCredits": $initialResetCredits
                      },
                      "cursor": {"totalPercentUsed": 20.0, "totalSpendCents": 500.0, "includedCents": 2000.0, "limitCents": 2500.0, "spendAvailable": true}
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK, jsonHeaders,
                )
                req.method == HttpMethod.Post && req.url.encodedPath == "/usage/codex/reset" -> respond(
                    """
                    {
                      "code": "reset",
                      "windowsReset": 1,
                      "codex": {
                        "plan": "pro",
                        "windows": [
                          {"used": 0.0, "label": "5-hour window", "windowSeconds": 18000.0},
                          {"used": 1.0, "label": "7-day window", "windowSeconds": 604800.0}
                        ],
                        "limitReached": false,
                        "resetCredits": $redeemedResetCredits
                      }
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
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

    @Test fun overlay_opens_from_ui_usage_open_and_loads_the_usage_data() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { usageOpen = true }
        val app = appForUsage()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        onNodeWithTag("usage_overlay").assertIsDisplayed()
        onNodeWithTag("usage_screen").assertIsDisplayed()
        onNodeWithTag("usage_card_codex").assertIsDisplayed()
        onNodeWithText("30% used").assertIsDisplayed()
        onNodeWithText("🎟️ Resets banked").assertIsDisplayed()
        onNodeWithText("3").assertIsDisplayed()
    }

    @Test fun escape_closes_the_usage_overlay() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { usageOpen = true }
        val app = appForUsage()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        onNodeWithTag("usage_overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(ui.usageOpen)
        onNodeWithTag("usage_overlay").assertDoesNotExist()
    }

    @Test fun workspace_shortcuts_are_gated_off_while_the_usage_overlay_is_up() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { usageOpen = true } // sidebarCollapsed defaults false
        val app = appForUsage()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed)
        onNodeWithTag("usage_screen").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed) // NOT toggled — the chord never reached the layout
        assertTrue(ui.usageOpen)                // ...and the overlay stayed up
    }

    @Test fun a_successful_redeem_updates_the_codex_card_in_place() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { usageOpen = true }
        val app = appForUsage(initialResetCredits = 3, redeemedResetCredits = 2)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(app, ui, WorkspaceStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
            }
        }
        waitForIdle()
        // Before redeeming: the fetched snapshot's resetCredits (3) and first window used% (30%).
        onNodeWithText("30% used").assertIsDisplayed()
        onNodeWithTag("codex_redeem_button").performClick()
        waitForIdle()
        onNodeWithTag("codex_redeem_confirm").performClick()
        waitForIdle()
        // After a code=="reset" redeem: WorkspaceRoot swapped in the refreshed CodexUsage — the
        // window resets to 0% used and the banked-reset count drops from 3 to 2, all WITHOUT a
        // second GET /usage (the card updated "in place"). The inline note survives the swap too.
        onNodeWithText("0% used").assertIsDisplayed()
        onNodeWithText("2").assertIsDisplayed()
        onNodeWithText("30% used").assertDoesNotExist()
        onNodeWithText("✓ Reset — cleared 1 window").assertIsDisplayed()
    }
}
