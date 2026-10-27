package dev.supermux.ui.session

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.PathValidation
import dev.supermux.state.LauncherDraft
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Design screenshots of the launcher's first run. No-op unless FIRSTRUN_SHOTS_DIR is set. */
@OptIn(ExperimentalTestApi::class)
class FirstRunShots {
    private fun noAgents(fail: Boolean = false): AgentSettingsActions {
        val started = mutableSetOf<String>()
        return AgentSettingsActions(
            agentStatuses = {
                listOf("claude", "codex", "opencode", "cursor", "grok", "antigravity").map { AgentInstallStatus(kind = it) }
            },
            agentStartInstall = { started += it; AgentInstallJob(state = "running") },
            agentPollInstall = { kind ->
                when {
                    kind !in started -> null
                    fail && kind == "codex" -> AgentInstallJob(state = "failed", exitCode = 1, error = "npm is not installed")
                    else -> AgentInstallJob(state = "running")
                }
            },
        )
    }

    @Test
    fun shots() {
        val dir = System.getenv("FIRSTRUN_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val variants = listOf(Triple("desktop", 1100, true), Triple("phone", 390, false))
        for (dark in listOf(true, false)) for ((name, w, pointer) in variants) {
            for (scene in listOf("start", "open", "create", "agent", "installing", "failed")) {
                runDesktopComposeUiTest(width = w, height = if (pointer) 760 else 844) {
                    setPlatformContent(
                        platform = FakePlatform(),
                        pointer = pointer,
                        widthClass = if (pointer) WindowWidthClass.Expanded else WindowWidthClass.Compact,
                        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
                    ) {
                        SupermuxTheme(appearance = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT) {
                            SessionLauncherScreen(
                                sessions = emptyList(),
                                home = "/Users/ahmet",
                                onBack = {},
                                actions = LauncherActions(
                                    listProjectsOrNull = { emptyList() },
                                    validatePath = { PathValidation(ok = true, path = "/Users/ahmet/code/app") },
                                    createLocalRepo = { "/Users/ahmet/.mux/state/projects/local/$it" },
                                    agentSetup = noAgents(fail = scene == "failed"),
                                    isThisComputer = { pointer },
                                ),
                                loadDraft = { if (scene in setOf("agent", "installing", "failed")) LauncherDraft(workdir = "/Users/ahmet/code/my-app") else LauncherDraft() },
                                onSubmit = { _, _, _, _, _, _, _, _, _, _, _ -> null },
                            )
                        }
                    }
                    waitForIdle()
                    when (scene) {
                        "open" -> onNodeWithTag(if (pointer) LauncherFirstRunTags.TYPE_PATH else LauncherFirstRunTags.OPEN_FOLDER).performClick()
                        "installing" -> onNodeWithTag(LauncherFirstRunTags.agentInstall("claude")).performClick()
                        "failed" -> onNodeWithTag(LauncherFirstRunTags.agentInstall("codex")).performClick()
                        "create" -> onNodeWithTag(LauncherFirstRunTags.CREATE_PROJECT).performClick()
                    }
                    if (scene == "failed") waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(LauncherFirstRunTags.agentError("codex"))).fetchSemanticsNodes().isNotEmpty() }
                    mainClock.advanceTimeBy(400)
                    val tag = if (dark) "dark" else "light"
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name-$scene-$tag.png"))
                }
            }
        }
    }
}
