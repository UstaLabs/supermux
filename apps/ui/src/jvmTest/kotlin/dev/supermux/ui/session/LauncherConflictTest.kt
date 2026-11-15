package dev.supermux.ui.session

import dev.supermux.net.GitBranches
import dev.supermux.net.GitLocalBranch
import dev.supermux.net.GitOpenIn
import dev.supermux.net.GitRemoteBranch
import dev.supermux.net.OpenPr
import dev.supermux.net.ProjectFolderState
import dev.supermux.net.SessionSourceBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #152 C2 (Pull request) + C3 (the inline ways out). Git keeps a branch in one place, and a pull request's branch
 * with local commits it lacks is never overwritten, so Start waits for a choice. The prototype's sample repo.
 */
class LauncherConflictTest {
    private val repo = GitBranches(
        current = "feat/checkout-v2",
        local = listOf(
            GitLocalBranch("main", isDefault = true, upstream = "origin/main"),
            GitLocalBranch("feat/checkout-v2", upstream = "origin/feat/checkout-v2", openIn = GitOpenIn("this")),
            GitLocalBranch("fix/cart-total", openIn = GitOpenIn("session", "s-2", "Cart total off by one")),
            GitLocalBranch("feat/search-speed", upstream = "origin/feat/search-speed"),
        ),
        remote = listOf(GitRemoteBranch("origin/main", isDefault = true), GitRemoteBranch("origin/feat/login-redirect")),
    )
    private val clean = ProjectFolderState(branch = "feat/checkout-v2")
    private val pr123 = OpenPr(number = 123, head = "feat/login-redirect", base = "main", author = "deniz", headRepo = "acme/storefront")
    private val pr118 = OpenPr(number = 118, head = "feat/checkout-v2", base = "main", headRepo = "acme/storefront")
    private val pr125 = OpenPr(number = 125, head = "feat/search-speed", base = "main", headRepo = "acme/storefront", localAhead = 2)
    private val pr121 = OpenPr(number = 121, head = "deps/sdk-4.2", base = "main", isFork = true, headOwner = "ozan", headRepo = "ozan/storefront", maintainerCanModify = true)

    private fun conflict(pick: WorkPick, folder: ProjectFolderState = clean) =
        workConflict(pick, workTarget(pick, repo, folder.branch, "ahmet/", ""), repo, folder)

    @Test fun a_pull_request_is_worked_on_on_its_own_branch_a_forks_named_after_its_owner() {
        val same = workTarget(WorkPick(mode = BranchMode.PullRequest, pr = pr123), repo, "feat/checkout-v2", "ahmet/", "")
        assertEquals(WorkTarget.OnPr(pr123, "feat/login-redirect"), same)
        assertEquals(SessionSourceBody(kind = "pr", number = 123), sessionSource(same))
        val fork = workTarget(WorkPick(mode = BranchMode.PullRequest, pr = pr121), repo, "feat/checkout-v2", "ahmet/", "")
        assertEquals(WorkTarget.OnPr(pr121, "ozan/deps/sdk-4.2"), fork)
        assertNull(workTarget(WorkPick(mode = BranchMode.PullRequest), repo, "feat/checkout-v2", "ahmet/", ""))
    }

    @Test fun the_folders_branch_offers_local_moving_the_folder_when_it_is_clean_or_a_new_branch() {
        assertEquals(WorkConflict.InFolder("feat/checkout-v2", moveTo = "main", canMove = true), conflict(WorkPick(mode = BranchMode.Existing, branch = "feat/checkout-v2")))
        assertEquals(WorkConflict.InFolder("feat/checkout-v2", moveTo = "main", canMove = false), conflict(WorkPick(mode = BranchMode.Existing, branch = "feat/checkout-v2"), ProjectFolderState(branch = "feat/checkout-v2", clean = false, changed = 1)))
        assertEquals(WorkConflict.InFolder("feat/checkout-v2", moveTo = "main", canMove = true), conflict(WorkPick(mode = BranchMode.PullRequest, pr = pr118)))
        // Moving the folder first frees it: nothing left to choose.
        assertNull(conflict(WorkPick(mode = BranchMode.Existing, branch = "feat/checkout-v2", folderTo = "main")))
    }

    @Test fun a_branch_another_session_has_offers_that_session_or_a_new_branch() {
        assertEquals(WorkConflict.InSession("fix/cart-total", "s-2", "Cart total off by one"), conflict(WorkPick(mode = BranchMode.Existing, branch = "fix/cart-total")))
    }

    @Test fun local_commits_the_pull_request_lacks_wait_for_whose_version_wins() {
        assertEquals(WorkConflict.LocalAhead(pr125, "feat/search-speed", 2), conflict(WorkPick(mode = BranchMode.PullRequest, pr = pr125)))
        val theirs = WorkPick(mode = BranchMode.PullRequest, pr = pr125, diverged = "pr")
        assertNull(conflict(theirs))
        assertEquals(SessionSourceBody(kind = "pr", number = 125, divergedPolicy = "pr"), sessionSource(workTarget(theirs, repo, "feat/checkout-v2", "", "")))
    }

    @Test fun nothing_to_choose_for_a_free_branch_a_new_one_or_local() {
        assertNull(conflict(WorkPick(mode = BranchMode.Existing, branch = "feat/search-speed")))
        assertNull(conflict(WorkPick()))
        assertNull(conflict(WorkPick(workIn = WorkIn.Local)))
        assertNull(conflict(WorkPick(mode = BranchMode.PullRequest, pr = pr123)))
    }

    @Test fun a_new_branch_from_a_held_one_starts_from_it_with_a_free_name() {
        assertEquals(WorkPick(mode = BranchMode.New, base = "feat/checkout-v2", name = "checkout-v2-2"), newBranchFrom(WorkPick(mode = BranchMode.Existing, branch = "feat/checkout-v2"), "feat/checkout-v2"))
    }

    @Test fun the_open_pr_from_a_branch_is_found_by_its_head_forks_aside() {
        assertEquals(pr118, openPrFrom("feat/checkout-v2", listOf(pr123, pr118, pr121)))
        assertNull(openPrFrom("deps/sdk-4.2", listOf(pr121)))
        assertEquals(123, parsePrNumber("https://github.com/acme/storefront/pull/123"))
        assertEquals(7, parsePrNumber("!7"))
        assertNull(parsePrNumber("fix login"))
    }
}
