package dev.supermux.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountIdentityDto
import dev.supermux.net.AccountUnavailableDto
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.ClaudeUsage
import dev.supermux.net.ClaudeWindow
import dev.supermux.net.CodexUsage
import dev.supermux.net.CodexWindow
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.UsageResponse
import dev.supermux.state.AccountResult
import dev.supermux.ui.chat.Composer
import dev.supermux.ui.chat.RunSettingsAccount
import dev.supermux.ui.chat.SessionAccountSwitch
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.AgentSettingsScreen
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.ui.usage.UsageScreen
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * Design screenshots of an expired sign-in (#63) on every surface that shows it: the Usage card, the
 * Accounts row, the Providers row and a chat's account notice. No-op unless SIGN_IN_SHOTS_DIR is set.
 */
@OptIn(ExperimentalTestApi::class)
class SignInExpiredShots {
    private val expired = AccountUnavailableDto(
        reason = "Cursor sign-in expired — sign in to Cursor again.", since = "2026-11-04T10:40:58.000Z", code = "account_expired")
    private val claudeSystem = AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true,
        identity = AccountIdentityDto(email = "ahmet@ustalabs.com"), createdAt = "1970-01-01T00:00:00.000Z")
    private val cursorSystem = AccountDto(id = "cursor:system", agent = "cursor", method = "system", label = "System login", system = true,
        createdAt = "1970-01-01T00:00:00.000Z", unavailable = expired)
    private val cursorWork = AccountDto(id = "cursor-work", agent = "cursor", method = "subscription", label = "Work",
        identity = AccountIdentityDto(email = "ahmet@acme.dev"), createdAt = "2026-10-03T00:00:00.000Z", unavailable = expired)

    private val usage = UsageResponse(
        claude = ClaudeUsage(fiveHour = ClaudeWindow(used = 12.0, resetsAt = null), sevenDay = ClaudeWindow(used = 40.0, resetsAt = null)),
        codex = CodexUsage(plan = "pro", windows = listOf(CodexWindow(id = "primary", used = 30.0, resetsAt = null, label = "5-hour window", windowSeconds = 18_000.0))),
        cursor = null,
        errors = mapOf("cursor" to "Cursor sign-in expired — sign in to Cursor again."),
        errorCodes = mapOf("cursor" to "account_expired"),
    )

    private fun shot(dir: File, name: String, width: Int, height: Int, then: ComposeUiTest.() -> Unit = {}, content: @Composable () -> Unit) {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(width = width, height = height) {
            setPlatformContent {
                SupermuxTheme(appearance = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
            waitForIdle()
            then()
            // Popups may be separate roots; the last one is on top.
            val image = onAllNodes(isRoot()).fetchSemanticsNodes().size.let { n -> onAllNodes(isRoot())[n - 1] }.captureToImage()
            ImageIO.write(image.toAwtImage(), "png", File(dir, "${if (dark) "dark" else "light"}-$name.png"))
        }
    }

    @Test
    fun shots() {
        val dir = System.getenv("SIGN_IN_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        shot(dir, "usage", 460, 640) {
            UsageScreen(usage = usage, loading = false, onBack = {}, onRedeem = { null }, onSignIn = { _, _ -> }, topBarShown = true)
        }
        shot(dir, "accounts", 760, 560) {
            AccountsSettingsScreen(
                AccountsActions(accounts = flowOf(listOf(claudeSystem, cursorSystem, cursorWork))),
                topBarShown = true,
                onSignInAgain = {},
            )
        }
        shot(dir, "providers", 760, 520, then = {
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("agent_row_cursor")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
        }) {
            AgentSettingsScreen(
                actions = AgentSettingsActions(
                    agentStatuses = {
                        listOf(
                            AgentInstallStatus(kind = "claude", installed = true, authed = true),
                            AgentInstallStatus(kind = "cursor", installed = true, authed = false, signInExpired = true),
                        )
                    },
                ),
                topBarShown = true,
            )
        }
        shot(dir, "chat", 760, 640, then = {
            onNodeWithTag("run_settings_account").performClick()
            waitForIdle()
        }) {
            val scope = rememberCoroutineScope()
            val switch = remember { SessionAccountSwitch(scope) { AccountResult.Failed(null, null, null) } }
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Spacer(Modifier.weight(1f))
                Composer(
                    draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                    onSend = { _, _ -> }, onInterrupt = {},
                    models = ModelsResponse(agent = "cursor", current = "auto", models = listOf(ModelInfo("auto", "Auto"))),
                    sessionAgent = "cursor",
                    account = RunSettingsAccount.forChat("cursor", null, null, listOf(cursorSystem, cursorWork), switch, onOpenAccounts = {}, onSignIn = {}),
                    openModelPickerNonce = 1L,
                )
            }
        }
    }
}
