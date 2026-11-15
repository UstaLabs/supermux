package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.net.BranchPrefixSetting
import dev.supermux.net.WorktreeListDto
import dev.supermux.net.WorktreeOwnerDto
import dev.supermux.net.WorktreeSummaryDto
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.WorktreesSettingsActions
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** #152: the Worktrees page's branch prefix in each of its states. No-op unless WORKTREES_SHOTS_DIR is set. */
@OptIn(ExperimentalTestApi::class)
class WorktreesPrefixShots {
    private val machine = KitMachine("m2", "studio-mini", local = false, online = true, status = "Running")
    private val trees = listOf("checkout-v2", "login-redirect").map { id ->
        WorktreeSummaryDto(
            id = id, path = "/wt/storefront/$id", repoRoot = "/src/storefront", repoName = "storefront", branch = "ahmet/$id",
            owners = listOf(WorktreeOwnerDto("s-$id", "Chat $id", "archived")), mtime = 0, uncommitted = 0, unmerged = 0,
            hasChanges = false, bytes = 1L shl 30,
        )
    }

    @Test
    fun shots() {
        val dir = System.getenv("WORKTREES_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val variants = listOf(Triple("desktop", 1100, true), Triple("phone", 390, false))
        for (dark in listOf(true, false)) for ((name, w, pointer) in variants) {
            for (scene in listOf("automatic", "custom", "none", "refused", "no-answer")) {
                runDesktopComposeUiTest(width = w, height = if (pointer) 760 else 844) {
                    val kept = when (scene) {
                        "custom" -> "team/"
                        "none" -> ""
                        else -> null
                    }
                    val actions = WorktreesSettingsActions(
                        load = { WorktreeListDto("/wt", trees) },
                        branchPrefix = { BranchPrefixSetting(kept, "ahmet/") },
                        setBranchPrefix = {
                            when (scene) {
                                "refused" -> BranchPrefixSetting(refused = true)
                                "no-answer" -> null
                                else -> BranchPrefixSetting(it, "ahmet/")
                            }
                        },
                    )
                    setPlatformContent(
                        platform = FakePlatform(),
                        pointer = pointer,
                        widthClass = if (pointer) WindowWidthClass.Expanded else WindowWidthClass.Compact,
                        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
                    ) {
                        SupermuxTheme(appearance = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT) {
                            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(machine), machine.id) {}) {
                                WorktreesPage(actions)
                            }
                        }
                    }
                    waitUntil(timeoutMillis = 5_000) { onAllNodes(androidx.compose.ui.test.hasTestTag("wt_prefix_field")).fetchSemanticsNodes().isNotEmpty() }
                    when (scene) {
                        "refused" -> {
                            onNodeWithTag("wt_prefix_field").performTextInput("a..b")
                            onNodeWithTag("wt_prefix_field").performImeAction()
                        }
                        "no-answer" -> onNodeWithTag("wt_prefix_none").performClick()
                    }
                    waitForIdle()
                    mainClock.advanceTimeBy(400)
                    val tag = if (dark) "dark" else "light"
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name-$scene-$tag.png"))
                }
            }
        }
    }
}
