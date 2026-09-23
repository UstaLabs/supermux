package dev.supermux.ui.usage

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.ClaudeExtraUsage
import dev.supermux.net.ClaudeResetGrant
import dev.supermux.net.ClaudeResetResult
import dev.supermux.net.ClaudeResets
import dev.supermux.net.ClaudeUsage
import dev.supermux.net.ClaudeWindow
import dev.supermux.net.CodexCredits
import dev.supermux.net.CodexModelUsage
import dev.supermux.net.CodexResetResult
import dev.supermux.net.CodexUsage
import dev.supermux.net.CodexWindow
import dev.supermux.net.CursorUsage
import dev.supermux.net.GrokProductUsage
import dev.supermux.net.GrokUsage
import dev.supermux.net.UsageResponse
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared [UsageScreen] (cluster E6) — desktop's suite, moved by name.
 *
 * Covers the provider cards fed from a typed `UsageResponse`, the per-provider "as of"/refreshing
 * captions, and the Codex banked-reset redeem flow. Pure reset-formatter tests live in
 * [UsageResetFormatTest]; the AppShell popover wiring stays in `:desktop` (`UsageHubTest`).
 *
 * New here: the Compact branch Android contributed — the `TopAppBar` with Back + Refresh that a
 * phone route paints for itself, the `standalone` gate that keeps it at every width, and the
 * stateful `UsageActions` overload that owns the snapshot and the fetches.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UsageScreenTest {

    private fun ComposeUiTest.usageContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(
        platform = FakePlatform(),
        pointer = pointer,
        widthClass = widthClass,
        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
    ) {
        content()
    }

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
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = null, loading = true, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("usage_spinner").assertIsDisplayed()
        onNodeWithText("Unable to load usage data.").assertDoesNotExist()
    }

    @Test fun usage_null_and_not_loading_shows_the_unable_to_load_text() = runComposeUiTest {
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = null, loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithText("Unable to load usage data.").assertIsDisplayed()
        onNodeWithTag("usage_spinner").assertDoesNotExist()
    }

    @Test fun renders_all_three_provider_cards_from_a_representative_usage_response() = runComposeUiTest {
        usageContent {
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

    @Test fun codex_card_lists_the_per_model_gates() = runComposeUiTest {
        val codex = CodexUsage(
            plan = "plus",
            windows = listOf(CodexWindow(id = "primary", used = 43.0, resetsAt = null, label = "5-hour window")),
            models = listOf(
                CodexModelUsage(id = "gpt-6-astra", label = "GPT-6 Astra", available = false, creditsWouldEnable = true),
                CodexModelUsage(id = "gpt-6-codex", label = "GPT-6 Codex", available = true),
            ),
        )
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                CodexUsageCard(codex = codex, error = null)
            }
        }
        waitForIdle()
        onNodeWithText("GPT-6 Astra").assertExists()
        onNodeWithText("locked · credits would unlock").assertExists()
        onNodeWithText("GPT-6 Codex").assertExists()
        onNodeWithText("available").assertExists()
    }

    @Test fun grok_card_labels_a_weekly_window_and_splits_multi_product_usage() = runComposeUiTest {
        val grok = GrokUsage(
            plan = "GrokPro",
            percentUsed = 70.0,
            periodType = "weekly",
            products = listOf(
                GrokProductUsage(product = "GrokBuild", percentUsed = 70.0),
                GrokProductUsage(product = "GrokChat", percentUsed = 12.0),
            ),
        )
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GrokUsageCard(grok = grok, error = null)
            }
        }
        waitForIdle()
        onNodeWithText("Weekly credits").assertExists()
        onNodeWithText("GrokBuild").assertExists()
        onNodeWithText("GrokChat").assertExists()
        onNodeWithText("12% used").assertExists()
        onNodeWithText("Monthly credits").assertDoesNotExist()
    }

    @Test fun grok_card_hides_the_product_rows_when_there_is_only_one_product() = runComposeUiTest {
        val grok = GrokUsage(
            plan = "GrokPro",
            percentUsed = 70.0,
            periodType = "weekly",
            products = listOf(GrokProductUsage(product = "GrokBuild", percentUsed = 70.0)),
        )
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GrokUsageCard(grok = grok, error = null)
            }
        }
        waitForIdle()
        onNodeWithText("GrokBuild").assertDoesNotExist()
    }

    @Test fun back_button_fires_on_back() = runComposeUiTest {
        var backCalled = false
        usageContent {
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
        usageContent {
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
        usageContent {
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
        usageContent {
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
        usageContent {
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
            // fall back to "Not available" and the assertion below fails.
            errors = mapOf(
                "claude" to "not configured",
                "codex" to "no api key",
                "cursor" to "request timed out",
                "opencode" to "no local usage",
                "grok" to "no credits",
            ),
        )
        usageContent {
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
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = usage, loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        // Every card falls back to "Not available" — assert the count, not a single node
        // (onNodeWithText requires a UNIQUE match). One per card UsageScreen renders:
        // claude, codex, cursor, opencode, grok. Bump this when a card is added.
        onAllNodesWithText("Not available").assertCountEquals(5)
    }

    @Test fun fetched_at_renders_a_per_provider_as_of_caption() = runComposeUiTest {
        val now = java.time.Instant.parse("2026-07-09T12:00:00Z").toEpochMilli()
        val usage = fixtureUsage().copy(
            fetchedAt = mapOf("claude" to "2026-07-09T11:55:00Z", "codex" to "2026-07-09T10:00:00Z"),
        )
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = usage, loading = false, onBack = {}, onRedeem = { null }, now = now)
            }
        }
        waitForIdle()
        onNodeWithTag("usage_as_of_claude").assertExists()
        onNodeWithText("as of 5m ago").assertExists()
        onNodeWithTag("usage_as_of_codex").assertExists()
        onNodeWithText("as of 2h ago").assertExists()
        onNodeWithTag("usage_as_of_cursor").assertDoesNotExist()
    }

    @Test fun refreshing_providers_show_a_progress_indicator() = runComposeUiTest {
        val usage = fixtureUsage().copy(refreshing = listOf("claude", "cursor"))
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = usage, loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("usage_refreshing_claude").assertExists()
        onNodeWithTag("usage_refreshing_cursor").assertExists()
        onNodeWithTag("usage_refreshing_codex").assertDoesNotExist()
    }

    @Test fun refresh_button_calls_on_refresh() = runComposeUiTest {
        var refreshed = false
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(),
                    loading = false,
                    onBack = {},
                    onRedeem = { null },
                    onRefresh = { refreshed = true },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("usage_refresh").performClick()
        waitForIdle()
        assertTrue(refreshed)
    }

    // ── (4) the Codex "Use a reset" button + confirm dialog ─────────────────────────────────────────

    @Test fun redeem_button_shown_only_when_reset_credits_positive() = runComposeUiTest {
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(codexResetCredits = 3), loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("codex_redeem_button").assertIsDisplayed()
    }

    @Test fun redeem_button_hidden_when_reset_credits_is_zero() = runComposeUiTest {
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(codexResetCredits = 0), loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("codex_redeem_button").assertDoesNotExist()
    }

    @Test fun firing_the_redeem_button_confirms_then_calls_on_redeem() = runComposeUiTest {
        var redeemCalled = false
        usageContent {
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
        usageContent {
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

    // ── (5b) the Claude banked limit resets ─────────────────────────────────────────────────────────

    private fun claudeResets(
        nextGrantId: String? = "opus55",
        useRequiresLimit: Boolean = false,
        atLimit: Boolean = false,
    ) = ClaudeResets(
        eligible = true,
        atLimit = atLimit,
        grants = listOf(
            ClaudeResetGrant(
                id = "opus55",
                label = "Opus 5.5 launch",
                resetsTotal = 1,
                resetsLeft = 1,
                endsAtIso = "2026-10-22T12:00:00Z",
                clears = listOf("five_hour", "seven_day", "seven_day_overage_included"),
                usableNow = nextGrantId != null,
                useRequiresLimit = useRequiresLimit,
            ),
        ),
        nextGrantId = nextGrantId,
        resetsLeft = 1,
    )

    private fun usageWithClaudeResets(resets: ClaudeResets?) =
        fixtureUsage().let { it.copy(claude = it.claude!!.copy(resets = resets)) }

    @Test fun claude_card_hides_the_resets_row_without_a_banked_reset() = runComposeUiTest {
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = usageWithClaudeResets(null), loading = false, onBack = {}, onRedeem = { null }, onRedeemClaude = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("claude_redeem_button").assertDoesNotExist()
    }

    @Test fun claude_reset_confirms_then_redeems_and_shows_the_note() = runComposeUiTest {
        var redeemCalled = false
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = usageWithClaudeResets(claudeResets()),
                    loading = false,
                    onBack = {},
                    onRedeem = { null },
                    onRedeemClaude = { redeemCalled = true; ClaudeResetResult(result = "reset", resetsLeft = 0) },
                )
            }
        }
        waitForIdle()
        onNodeWithText("1 · use by Oct 22").assertIsDisplayed()
        onNodeWithTag("claude_redeem_button").performClick()
        waitForIdle()
        onNodeWithText("Refills your 5-hour and 7-day limits now. Spends 1 of 1; your weekly reset day stays the same.")
            .assertIsDisplayed()
        assertFalse(redeemCalled)
        onNodeWithTag("claude_redeem_confirm").performClick()
        waitForIdle()
        assertTrue(redeemCalled)
        onNodeWithText("✓ Limits reset · 0 left").assertIsDisplayed()
    }

    @Test fun claude_reset_that_needs_a_limit_shows_a_hint_instead_of_the_button() = runComposeUiTest {
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = usageWithClaudeResets(claudeResets(nextGrantId = null, useRequiresLimit = true)),
                    loading = false,
                    onBack = {},
                    onRedeem = { null },
                    onRedeemClaude = { null },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("claude_redeem_button").assertDoesNotExist()
        onNodeWithText("Usable once you hit a limit").assertIsDisplayed()
    }

    @Test fun claude_reset_note_covers_every_result() {
        assertEquals("Reset failed", claudeResetNote(null))
        assertEquals("✓ Limits reset · 2 left", claudeResetNote(ClaudeResetResult(result = "reset", resetsLeft = 2)))
        assertEquals("✓ Limits reset", claudeResetNote(ClaudeResetResult(result = "reset")))
        assertEquals("Your limits were already clear — nothing was used", claudeResetNote(ClaudeResetResult(result = "not_limited")))
        assertEquals("That reset was already used", claudeResetNote(ClaudeResetResult(result = "already_used")))
        assertEquals("No reset available to use", claudeResetNote(ClaudeResetResult(result = "no_reset")))
        assertEquals("Reset request completed", claudeResetNote(ClaudeResetResult(result = "future_code")))
    }

    @Test fun claude_reset_clears_names_the_known_windows() {
        assertEquals("5-hour and 7-day limits", claudeResetClears(listOf("five_hour", "seven_day", "seven_day_overage_included")))
        assertEquals("5-hour, 7-day and 7-day Sonnet limits", claudeResetClears(listOf("five_hour", "seven_day", "seven_day_sonnet")))
        assertEquals("7-day limit", claudeResetClears(listOf("seven_day")))
        assertEquals("usage limits", claudeResetClears(emptyList()))
    }

    // ── (6) NEW: the Compact / standalone chrome Android contributed ────────────────────────────

    @Test fun compact_paints_a_top_bar_with_back_and_refresh() = runComposeUiTest {
        var backCalled = false
        var refreshed = false
        usageContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(),
                    loading = false,
                    onBack = { backCalled = true },
                    onRedeem = { null },
                    onRefresh = { refreshed = true },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("usage_screen").assertExists()
        // The bar's title says "Usage" — and so does Cursor's window row, so match on the tags.
        onNodeWithTag("usage_refresh").performClick()
        waitForIdle()
        assertTrue(refreshed)
        onNodeWithTag("usage_back").performClick()
        assertTrue(backCalled)
    }

    @Test fun compact_renders_every_provider_card() = runComposeUiTest {
        usageContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(), loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("usage_card_claude").assertExists()
        onNodeWithTag("usage_card_codex").assertExists()
        onNodeWithTag("usage_card_cursor").assertExists()
        onNodeWithTag("usage_card_opencode").assertExists()
        onNodeWithTag("usage_card_grok").assertExists()
    }

    @Test fun standalone_keeps_the_top_bar_above_compact() = runComposeUiTest {
        usageContent(pointer = false, widthClass = WindowWidthClass.Medium) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(), loading = false, onBack = {}, onRedeem = { null },
                    standalone = true,
                )
            }
        }
        waitForIdle()
        // The bar's Back exists at Medium — the popover's in-card close row would not have been
        // painted here, since this width is not Compact.
        onNodeWithTag("usage_back").assertExists()
        onNodeWithTag("usage_screen").assertExists()
    }

    @Test fun a_bar_painted_above_suppresses_this_screens_own() = runComposeUiTest {
        usageContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(), loading = false, onBack = {}, onRedeem = { null },
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        // Falls back to the in-card header row (which still carries close + refresh tags).
        onNodeWithTag("usage_screen").assertExists()
        onNodeWithTag("usage_back").assertExists()
    }

    @Test fun a_pointer_host_that_owns_its_chrome_gets_no_bar_even_when_compact() = runComposeUiTest {
        // Desktop narrowed below 600dp: the popover already paints the ✕ and the host picker, so
        // the screen must stay on its in-card header instead of growing Android's TopAppBar.
        usageContent(pointer = true, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(), loading = false, onBack = {}, onRedeem = { null },
                    onRefresh = {},
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        // The in-card header row is the one that carries a Close (not Back) description.
        onNodeWithContentDescription("Close").assertExists()
        onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test fun a_window_with_no_label_falls_back_to_usage_window() = runComposeUiTest {
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(
                    usage = fixtureUsage(
                        codexWindows = listOf(
                            CodexWindow(id = "primary", used = 30.0, resetsAt = null, label = "", windowSeconds = null),
                        ),
                    ),
                    loading = false, onBack = {}, onRedeem = { null },
                )
            }
        }
        waitForIdle()
        onNodeWithText("Usage window").assertExists()
    }

    @Test fun the_actions_overload_re_fetches_after_a_redeem() = runComposeUiTest {
        val snapshot = MutableStateFlow<UsageResponse?>(fixtureUsage())
        var loads = 0
        val actions = UsageActions(
            snapshot = snapshot,
            load = { loads++; snapshot.value },
            refresh = { snapshot.value },
            redeem = { CodexResetResult(code = "reset", windowsReset = 1) },
        )
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(actions = actions, onBack = {})
            }
        }
        waitForIdle()
        assertEquals(1, loads) // the load-on-open
        onNodeWithTag("codex_redeem_button").performClick()
        waitForIdle()
        onNodeWithTag("codex_redeem_confirm").performClick()
        waitForIdle()
        // The holder's redeem only swaps the Codex block in place; the other providers pick their
        // post-redeem numbers up from this re-fetch (Android's behaviour).
        assertEquals(2, loads)
        onNodeWithTag("codex_redeem_note").assertIsDisplayed()
    }

    // ── (8) NEW: the pure 2-decimal formatter behind money()/dollars() ──────────────────────────

    @Test fun fixed_matches_the_locale_us_percent_f_output_it_replaced() {
        assertEquals("0.00", fixed(0.0, 2))
        assertEquals("5.00", fixed(5.0, 2))
        assertEquals("0.10", fixed(0.1, 2))
        assertEquals("12.35", fixed(12.345678, 2))
        assertEquals("-3.50", fixed(-3.5, 2))
        assertEquals("1000000.00", fixed(1_000_000.0, 2))
        // HALF_UP on the magnitude, the rule `String.format(Locale.US, "%.2f", …)` used: an exact
        // binary midpoint rounds AWAY from zero, not to even.
        assertEquals("0.13", fixed(0.125, 2))
        assertEquals("-0.13", fixed(-0.125, 2))
        // One decimal — the token abbreviations.
        assertEquals("1.2", fixed(1.234, 1))
        // The rule is HALF_UP applied to the value SCALED IN BINARY, which is not always what
        // Java's `%.1f` (HALF_UP over the double's exact decimal expansion) says: 34.55 is really
        // 34.549999999999997, but `* 10` rounds to exactly 345.5, so this goes up where
        // `String.format` went down. One ulp on a token abbreviation; the alternative is decimal
        // arithmetic in commonMain for no visible gain.
        assertEquals("34.6", fixed(34.55, 1))
        // A zero that rounds from a negative keeps no sign.
        assertEquals("0.00", fixed(-0.001, 2))
    }

    @Test fun touch_header_actions_are_at_least_48dp() = runComposeUiTest {
        usageContent(pointer = false) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(usage = fixtureUsage(), loading = false, onBack = {}, onRedeem = { null })
            }
        }
        waitForIdle()
        val h = onNodeWithTag("usage_back").fetchSemanticsNode().size.height
        val minPx = with(density) { 48.dp.toPx() }
        assertTrue(h >= minPx, "touch close target was ${h}px, want >= ${minPx}px")
    }

    // ── (7) NEW: the stateful UsageActions overload ─────────────────────────────────────────────

    @Test fun the_actions_overload_paints_the_held_snapshot_and_loads_once() = runComposeUiTest {
        val snapshot = MutableStateFlow<UsageResponse?>(fixtureUsage())
        var loads = 0
        val actions = UsageActions(
            snapshot = snapshot,
            load = { loads++; snapshot.value },
            refresh = { snapshot.value },
            redeem = { null },
        )
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(actions = actions, onBack = {})
            }
        }
        waitForIdle()
        // The held snapshot painted without waiting for the fetch, and the fetch ran exactly once.
        onNodeWithTag("usage_card_codex").assertExists()
        onNodeWithTag("usage_spinner").assertDoesNotExist()
        assertEquals(1, loads)
    }

    @Test fun the_actions_overload_shows_a_spinner_until_the_first_snapshot_lands() = runComposeUiTest {
        val snapshot = MutableStateFlow<UsageResponse?>(null)
        val actions = UsageActions(
            snapshot = snapshot,
            load = { snapshot.value = fixtureUsage(); snapshot.value },
            refresh = { snapshot.value },
            redeem = { null },
        )
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(actions = actions, onBack = {})
            }
        }
        waitForIdle()
        // Resolved: the load filled the snapshot, so the cards replaced the spinner.
        onNodeWithTag("usage_spinner").assertDoesNotExist()
        onNodeWithTag("usage_card_claude").assertExists()
    }

    @Test fun the_actions_overload_refresh_button_calls_refresh() = runComposeUiTest {
        val snapshot = MutableStateFlow<UsageResponse?>(fixtureUsage())
        var refreshes = 0
        val actions = UsageActions(
            snapshot = snapshot,
            load = { snapshot.value },
            refresh = { refreshes++; snapshot.value },
            redeem = { null },
        )
        usageContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                UsageScreen(actions = actions, onBack = {})
            }
        }
        waitForIdle()
        onNodeWithTag("usage_refresh").performClick()
        waitForIdle()
        assertEquals(1, refreshes)
    }
}
