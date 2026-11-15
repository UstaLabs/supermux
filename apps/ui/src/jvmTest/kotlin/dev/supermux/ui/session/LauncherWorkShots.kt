package dev.supermux.ui.session

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.net.GitBranches
import dev.supermux.net.GitLocalBranch
import dev.supermux.net.GitOpenIn
import dev.supermux.net.GitRemoteBranch
import dev.supermux.net.OpenPr
import dev.supermux.net.OpenPrList
import dev.supermux.net.ProjectFolderState
import dev.supermux.net.ProjectPrefs
import dev.supermux.net.RepoBranches
import dev.supermux.net.RepoInfo
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.state.LauncherDraft
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.coroutines.flow.flowOf
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * #152: the launcher's row in the prototype's states, on the prototype's sample repo, for the side-by-side check
 * against ~/.mux/prototypes/152-new-session. No-op unless LAUNCHER_SHOTS_DIR is set. A popover or a sheet is its own
 * window, so each shot is the screen with every open window drawn where it sits.
 */
@OptIn(ExperimentalTestApi::class)
class LauncherWorkShots {
    private val dir = "/Users/ahmet/code/storefront"
    private val now = System.currentTimeMillis()
    private fun ago(minutes: Long) = now - minutes * 60_000

    private val storefront = GitBranches(
        current = "feat/checkout-v2",
        local = listOf(
            GitLocalBranch("main", isDefault = true, upstream = "origin/main", author = "deniz", at = ago(120)),
            GitLocalBranch("feat/checkout-v2", upstream = "origin/feat/checkout-v2", openIn = GitOpenIn("this"), author = "you", at = ago(25)),
            GitLocalBranch("fix/cart-total", openIn = GitOpenIn("session", "s-2", "Cart total off by one"), author = "you", at = ago(60)),
            GitLocalBranch("feat/search-speed", upstream = "origin/feat/search-speed", ahead = 2, author = "you", at = ago(24 * 60)),
            GitLocalBranch("release/2.4", upstream = "origin/release/2.4", author = "ci", at = ago(2 * 24 * 60)),
        ),
        remote = listOf(
            GitRemoteBranch("origin/main", isDefault = true, author = "deniz", at = ago(120)),
            GitRemoteBranch("origin/feat/checkout-v2", author = "you", at = ago(25)),
            GitRemoteBranch("origin/feat/login-redirect", author = "deniz", at = ago(180)),
            GitRemoteBranch("origin/feat/search-speed", author = "you", at = ago(24 * 60)),
            GitRemoteBranch("origin/release/2.4", author = "ci", at = ago(2 * 24 * 60)),
            GitRemoteBranch("origin/docs/readme-setup", author = "ozan", at = ago(5 * 24 * 60)),
        ),
    )
    private val repo = RepoInfo(
        isGitRepo = true, eligible = true, repoRoot = dir, currentBranch = "feat/checkout-v2",
        branches = RepoBranches(local = storefront.local.map { it.name }, remote = storefront.remote.map { it.name }),
    )
    private val clean = ProjectFolderState(branch = "feat/checkout-v2")
    private fun prList(gitlab: Boolean) = OpenPrList(
        status = "ok", forge = if (gitlab) "gitlab" else "github", repo = if (gitlab) "gitlab.com/acme/storefront" else "github.com/acme/storefront",
        prs = listOf(
            OpenPr(number = 123, title = "Fix login redirect loop", head = "feat/login-redirect", base = "main", author = "deniz", headRepo = "acme/storefront", checks = "ok"),
            OpenPr(number = 118, title = "Checkout v2", head = "feat/checkout-v2", base = "main", author = "you", headRepo = "acme/storefront", draft = true, checks = "run"),
            OpenPr(number = 121, title = "Bump payments SDK to 4.2", head = "deps/sdk-4.2", base = "main", author = "ozan", headRepo = "ozan/storefront", isFork = true, headOwner = "ozan", maintainerCanModify = true, checks = "ok"),
            OpenPr(number = 125, title = "Speed up product search", head = "feat/search-speed", base = "main", author = "you", headRepo = "acme/storefront", checks = "ok", localAhead = 2),
            OpenPr(number = 109, title = "Add Turkish translations", head = "i18n-tr", base = "main", author = "selin", headRepo = "selin/storefront", isFork = true, headOwner = "selin", maintainerCanModify = false, checks = "bad"),
        ).map { if (gitlab) it.copy(forge = "gitlab") else it },
    )
    private val dirty = ProjectFolderState(branch = "feat/checkout-v2", clean = false, changed = 2)

    private class Scene(
        val name: String,
        val prefs: ProjectPrefs? = ProjectPrefs("worktree", "ahmet/"),
        val folder: ProjectFolderState? = null,
        val message: String = "",
        val notGit: Boolean = false,
        val gitlab: Boolean = false,
        val act: ComposeUiTest.() -> Unit = {},
    )

    private fun ComposeUiTest.open(tag: String) { onNodeWithTag(tag).performClick(); waitForIdle() }

    private val scenes = listOf(
        Scene("01-default"),
        Scene("02-named", message = "Fix the login redirect loop on Safari"),
        Scene("03-where-menu") { open(WorkRowTags.WORK_IN) },
        Scene("04-pop-new", message = "Fix the login redirect loop on Safari") { open(WorkRowTags.BRANCH) },
        Scene("05-pop-new-base") { open(WorkRowTags.BRANCH); open(WorkRowTags.BASE) },
        Scene("06-pop-existing") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_EXISTING) },
        Scene("07-existing-remote") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_EXISTING); open(WorkRowTags.row("origin/feat/login-redirect")) },
        Scene("08-local", prefs = ProjectPrefs("local", "ahmet/")),
        Scene("09-local-pop", prefs = ProjectPrefs("local", "ahmet/")) { open(WorkRowTags.LOCAL_BRANCH) },
        Scene("10-local-switch", prefs = ProjectPrefs("local", "ahmet/")) { open(WorkRowTags.LOCAL_BRANCH); open(WorkRowTags.row("main")) },
        Scene("11-local-dirty-pop", prefs = ProjectPrefs("local", "ahmet/"), folder = dirty) { open(WorkRowTags.LOCAL_BRANCH) },
        Scene("12-local-reuse", prefs = ProjectPrefs("local", "ahmet/")) { open(WorkRowTags.LOCAL_BRANCH); open(WorkRowTags.row("fix/cart-total")) },
        Scene("13-not-git", notGit = true),
        Scene("14-older-broker", prefs = null) { open(WorkRowTags.BRANCH) },
        Scene("15-pop-pr") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_PR) },
        Scene("16-pr123") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_PR); open(WorkRowTags.pr(123)) },
        Scene("17-pr118-in-folder") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_PR); open(WorkRowTags.pr(118)) },
        Scene("18-fork-edits") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_PR); open(WorkRowTags.pr(121)) },
        Scene("19-fork-review-only") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_PR); open(WorkRowTags.pr(109)) },
        Scene("20-diverged") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_PR); open(WorkRowTags.pr(125)) },
        Scene("21-diverged-done") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_PR); open(WorkRowTags.pr(125)); open(WorkRowTags.CARD_USE_PR) },
        Scene("22-existing-in-session") { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_EXISTING); open(WorkRowTags.row("fix/cart-total")) },
        Scene("23-gitlab-pop", gitlab = true) { open(WorkRowTags.BRANCH); open(WorkRowTags.TAB_PR) },
    )

    @Test
    fun shots() {
        val out = System.getenv("LAUNCHER_SHOTS_DIR")?.let(::File) ?: return
        out.mkdirs()
        val devices = listOf(Triple("desktop", 1100, true), Triple("phone", 390, false))
        for (dark in listOf(true, false)) for ((device, w, pointer) in devices) for (scene in scenes) {
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
                                listProjectsOrNull = { listOf(dir) },
                                projectCatalog = flowOf(listOf(ProjectDto("p1", "Storefront", locations = listOf(ProjectLocationDto("l1", dir))))),
                                launcherRepoInfo = { _, _ -> if (scene.notGit) RepoInfo() else repo },
                                projectPrefs = { scene.prefs },
                                projectBranches = { storefront },
                                folderState = { scene.folder ?: clean },
                                setProjectPrefs = { _, wi -> scene.prefs?.copy(workIn = wi) },
                                projectPrs = { _, _ -> if (scene.prefs == null) null else prList(scene.gitlab) },
                            ),
                            loadDraft = { LauncherDraft(workdir = dir, text = scene.message) },
                            onSubmit = { _, _, _, _, _, _, _, _, _, _, _, _ -> null },
                            standalone = !pointer,
                        )
                    }
                }
                waitForIdle()
                scene.act(this)
                mainClock.advanceTimeBy(600)
                waitForIdle()
                ImageIO.write(screen(), "png", File(out, "$device-${scene.name}-${if (dark) "dark" else "light"}.png"))
            }
        }
    }

    /** The main window with every popup window (popover, sheet) drawn on it where it sits. */
    private fun ComposeUiTest.screen(): BufferedImage {
        val roots = onAllNodes(isRoot())
        val nodes = roots.fetchSemanticsNodes()
        val base = roots[0].captureToImage().toAwtImage()
        val g = base.createGraphics()
        for (i in 1 until nodes.size) {
            val b = nodes[i].boundsInWindow
            g.drawImage(roots[i].captureToImage().toAwtImage(), b.left.toInt(), b.top.toInt(), null)
        }
        g.dispose()
        return base
    }
}
