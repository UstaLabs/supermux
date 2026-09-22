package dev.supermux.desktop.usage

import dev.supermux.desktop.testDeps

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.desktop.shell.TestAppShell
import dev.supermux.ui.shell.ShellUiState
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Usage popover's wiring into [AppShell] — what stays in `:desktop` after cluster E6 moved
 * [dev.supermux.ui.usage.UsageScreen] and [dev.supermux.ui.usage.UsagePopover] to `:ui`: it opens
 * from `ui.usageOpen`, Escape closes it, workspace chords are gated off while it is up, and a
 * successful Codex redeem updates the card in place (now through `UsageActions`, whose HostStore
 * builder owns the `applyUsage` swap this file used to assert against AppShell's own lambda).
 *
 * The screen's own behaviour is `:ui`'s `UsageScreenTest` / `UsageResetFormatTest`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UsageHubTest {

    // ── (6) overlay wiring into AppShell ───────────────────────────────────────────────────────

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

    /** GETs of /usage this engine has served — how the post-redeem re-fetch is pinned. */
    private var usageGets = 0

    /**
     * A [HostStore] whose HTTP serves GET /usage + POST /usage/codex/reset.
     *
     * GET /usage is STATEFUL since cluster E6: the screen re-fetches after a successful redeem
     * (`UsageActions.load`, restoring Android's post-redeem refresh for the providers the in-place
     * Codex swap does not touch), so the second GET answers with the redeemed numbers the way a
     * real broker would.
     *
     * [failUsageAfterRedeem] serves that second GET as a 500 instead. `HostStore.usage()` leaves
     * the held snapshot alone when the call fails, so the card can only be showing the redeemed
     * numbers because `UsageActions.redeem` swapped them in — which is what the in-place test
     * needs to assert, and what a stateful GET serving those same numbers cannot distinguish.
     */
    private fun appForUsage(
        initialResetCredits: Int = 3,
        redeemedResetCredits: Int = 2,
        failUsageAfterRedeem: Boolean = false,
    ): HostStore {
        var redeemed = false
        usageGets = 0
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Get && req.url.encodedPath == "/usage") usageGets++
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                req.method == HttpMethod.Get && req.url.encodedPath == "/usage" &&
                    redeemed && failUsageAfterRedeem ->
                    respond(ByteReadChannel("boom"), HttpStatusCode.InternalServerError)
                req.method == HttpMethod.Get && req.url.encodedPath == "/usage" && redeemed -> respond(
                    """
                    {
                      "claude": {"fiveHour": {"used": 12.0}, "sevenDay": {"used": 40.0}},
                      "codex": {
                        "plan": "pro",
                        "windows": [
                          {"used": 0.0, "label": "5-hour window", "windowSeconds": 18000.0},
                          {"used": 1.0, "label": "7-day window", "windowSeconds": 604800.0}
                        ],
                        "limitReached": false,
                        "resetCredits": $redeemedResetCredits
                      },
                      "cursor": {"totalPercentUsed": 20.0, "totalSpendCents": 500.0, "includedCents": 2000.0, "limitCents": 2500.0, "spendAvailable": true}
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK, jsonHeaders,
                )
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
                req.method == HttpMethod.Post && req.url.encodedPath == "/usage/codex/reset" -> {
                    redeemed = true
                    respond(
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
                }
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
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

    @Test fun overlay_opens_from_ui_usage_open_and_loads_the_usage_data() = runComposeUiTest {
        val ui = ShellUiState().apply { openUsage() }
        val app = appForUsage()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(app, ui)
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
        val ui = ShellUiState().apply { openUsage() }
        val app = appForUsage()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(app, ui)
            }
        }
        waitForIdle()
        onNodeWithTag("usage_overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(ui.usageOpen)
        onNodeWithTag("usage_overlay").assertDoesNotExist()
    }

    @Test fun workspace_shortcuts_are_gated_off_while_the_usage_overlay_is_up() = runComposeUiTest {
        val ui = ShellUiState().apply { openUsage() } // sidebarCollapsed defaults false
        val app = appForUsage()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(app, ui)
            }
        }
        waitForIdle()
        assertFalse(ui.sidebarCollapsed)
        onNodeWithTag("usage_screen").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        waitForIdle()
        assertFalse(ui.sidebarCollapsed) // NOT toggled — the chord never reached the layout
        assertTrue(ui.usageOpen)                // ...and the popover stayed up
    }

    @Test fun a_successful_redeem_updates_the_codex_card_in_place() = runComposeUiTest {
        val ui = ShellUiState().apply { openUsage() }
        // The post-redeem GET fails, so nothing but `UsageActions.redeem`'s in-place swap can
        // explain the new numbers below (a GET serving them too would prove nothing).
        val app = appForUsage(
            initialResetCredits = 3,
            redeemedResetCredits = 2,
            failUsageAfterRedeem = true,
        )
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(app, ui)
            }
        }
        waitForIdle()
        // Before redeeming: the fetched snapshot's resetCredits (3) and first window used% (30%).
        onNodeWithText("30% used").assertIsDisplayed()
        onNodeWithTag("codex_redeem_button").performClick()
        waitForIdle()
        onNodeWithTag("codex_redeem_confirm").performClick()
        waitForIdle()
        // After a code=="reset" redeem: `UsageActions.redeem` swapped in the refreshed CodexUsage
        // — the window resets to 0% used and the banked-reset count drops from 3 to 2, in place
        // and before the follow-up GET /usage lands. The inline note survives both.
        onNodeWithText("0% used").assertIsDisplayed()
        onNodeWithText("2").assertIsDisplayed()
        onNodeWithText("30% used").assertDoesNotExist()
        onNodeWithText("✓ Reset — cleared 1 window").assertIsDisplayed()
    }

    /**
     * The other half of the same wiring: the redeem is followed by a fresh GET /usage, so the
     * providers the Codex swap does not touch (Claude, Cursor) are not left stale. Asserted on the
     * request count rather than on numbers, which the swap could also explain.
     */
    @Test fun a_successful_redeem_refetches_usage_for_the_other_providers() = runComposeUiTest {
        val ui = ShellUiState().apply { openUsage() }
        val app = appForUsage(initialResetCredits = 3, redeemedResetCredits = 2)
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(app, ui)
            }
        }
        waitForIdle()
        val before = usageGets
        assertTrue(before >= 1, "the screen should have loaded usage once")
        onNodeWithTag("codex_redeem_button").performClick()
        waitForIdle()
        onNodeWithTag("codex_redeem_confirm").performClick()
        waitForIdle()
        assertTrue(usageGets > before, "redeem should re-fetch /usage (was $before, now $usageGets)")
        // ...and the refreshed body is what is on screen.
        onNodeWithText("0% used").assertIsDisplayed()
    }
}
