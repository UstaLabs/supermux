package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountIdentityDto
import dev.supermux.net.AccountUnavailableDto
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.PermissionModeInfo
import dev.supermux.state.AccountResult
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Design screenshots of a running chat's account in the run-settings panel (desktop popover): the
 * panel, the Account list, and both while the account is refused. No-op unless COMPOSER_SHOTS_DIR
 * is set.
 */
@OptIn(ExperimentalTestApi::class)
class RunSettingsAccountShots {
    private val models = ModelsResponse(
        agent = "claude",
        current = "opus",
        models = listOf(ModelInfo("opus", "Opus 5.5"), ModelInfo("sonnet", "Sonnet 5.5"), ModelInfo("haiku", "Haiku 5.5")),
    )
    private val reasoning = ReasoningResponse(
        agent = "claude", current = "medium",
        levels = listOf(ReasoningLevel("low"), ReasoningLevel("medium"), ReasoningLevel("high"), ReasoningLevel("max")),
        visible = true,
    )
    private val perms = listOf(
        PermissionModeInfo("default", "Ask", "Ask before edits", default = true),
        PermissionModeInfo("bypass", "Bypass", "Never ask"),
    )
    private val system = AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true,
        identity = AccountIdentityDto(email = "ahmet@ustalabs.com"), createdAt = "1970-01-01T00:00:00.000Z",
        usage = listOf(AccountUsageWindowDto("five_hour", 41.0)))
    private val work = AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work (Max)",
        identity = AccountIdentityDto(email = "ahmet@acme.dev"), createdAt = "2026-10-01T00:00:00.000Z",
        usage = listOf(AccountUsageWindowDto("five_hour", 62.0), AccountUsageWindowDto("seven_day", 18.0)))
    private val ci = AccountDto(id = "claude-ci", agent = "claude", method = "token", label = "CI token", isolated = true,
        createdAt = "2026-10-02T00:00:00.000Z", usage = listOf(AccountUsageWindowDto("five_hour", 94.0)))
    private val refused = work.copy(unavailable = AccountUnavailableDto(
        reason = "Your organization has disabled Claude subscription access for Claude Code", since = "2026-10-11T09:08:27.000Z"))

    @Test
    fun shots() {
        val dir = System.getenv("COMPOSER_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val states = listOf(
            "account-list" to listOf(system, work, ci),
            "account-refused" to listOf(system, refused, ci),
        )
        for (dark in listOf(false, true)) for ((state, accounts) in states) for (step in listOf("panel", "list")) {
            runDesktopComposeUiTest(width = 760, height = 720) {
                setPlatformContent {
                    SupermuxTheme(appearance = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT) {
                        val scope = rememberCoroutineScope()
                        val switch = remember { SessionAccountSwitch(scope) { AccountResult.Failed(null, null, null) } }
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Spacer(Modifier.weight(1f))
                            Composer(
                                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                                onSend = { _, _ -> }, onInterrupt = {},
                                models = models, reasoning = reasoning, sessionAgent = "claude",
                                permissionModes = perms, sessionPermissionMode = "default",
                                account = RunSettingsAccount.forChat("claude", "claude-work", "Work (Max)", accounts, switch, onOpenAccounts = {}),
                                openModelPickerNonce = 1L,
                            )
                        }
                    }
                }
                waitForIdle()
                if (step == "list") {
                    onNodeWithTag("run_settings_account").performClick()
                    waitForIdle()
                }
                // Popups may be separate roots; the panel is the last one.
                val image = onAllNodes(isRoot()).fetchSemanticsNodes().size.let { n -> onAllNodes(isRoot())[n - 1] }.captureToImage()
                ImageIO.write(image.toAwtImage(), "png", File(dir, "desktop-${if (dark) "dark" else "light"}-$state-$step.png"))
            }
        }
    }
}
