package dev.supermux.ui.session

import dev.supermux.net.GitBranches
import dev.supermux.net.GitLocalBranch
import dev.supermux.net.GitOpenIn
import dev.supermux.net.GitRemoteBranch
import dev.supermux.net.SessionSourceBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #152: the launcher's row as data, on the prototype's sample repo. The folder is on feat/checkout-v2, a session has
 * fix/cart-total in its own copy, and origin has feat/login-redirect, which no local branch tracks.
 */
class LauncherWorkTest {
    private val repo = GitBranches(
        current = "feat/checkout-v2",
        local = listOf(
            GitLocalBranch("main", isDefault = true, upstream = "origin/main", author = "deniz", at = 7_000),
            GitLocalBranch("feat/checkout-v2", openIn = GitOpenIn("this"), author = "you", at = 9_000),
            GitLocalBranch("fix/cart-total", openIn = GitOpenIn("session", "s-2", "Cart total off by one"), author = "you", at = 8_000),
            GitLocalBranch("feat/search-speed", ahead = 2, author = "you", at = 3_000),
        ),
        remote = listOf(
            GitRemoteBranch("origin/main", isDefault = true, author = "deniz", at = 7_000),
            GitRemoteBranch("origin/feat/login-redirect", author = "deniz", at = 6_000),
            GitRemoteBranch("origin/docs/readme-setup", author = "ozan", at = 1_000),
        ),
    )

    @Test fun rows_list_the_default_first_then_the_newest_and_only_origins_branches_no_local_one_has() {
        val (local, remote) = branchRows(repo)
        assertEquals(listOf("main", "feat/checkout-v2", "fix/cart-total", "feat/search-speed"), local.map { it.name })
        assertEquals(listOf("origin/feat/login-redirect", "origin/docs/readme-setup"), remote.map { it.name })
        assertEquals(true, local[1].inFolder)
        assertEquals(true, local[2].inSession)
        assertEquals(listOf("origin/docs/readme-setup"), filterRows(remote, "README").map { it.name })
    }

    @Test fun the_automatic_name_is_the_prefix_and_the_messages_first_four_words() {
        assertEquals("ahmet/fix-the-login-redirect", autoBranchName("ahmet/", "Fix the login redirect loop on Safari"))
        assertEquals("ahmet/giris-dongusunu-duzelt", autoBranchName("ahmet/", "Giriş döngüsünü düzelt!"))
        assertEquals("ahmet/session", autoBranchName("ahmet/", "  "))
        assertEquals("fix-it", autoBranchName("", "fix it"))
    }

    @Test fun a_new_branch_is_named_from_the_message_until_one_is_typed_and_starts_from_the_default() {
        val auto = workTarget(WorkPick(), repo, "feat/checkout-v2", "ahmet/", "Fix the login redirect")
        assertEquals(WorkTarget.NewBranch("ahmet/fix-the-login-redirect", auto = true, base = "main"), auto)
        assertEquals(SessionSourceBody(kind = "new", base = "main", name = "ahmet/fix-the-login-redirect", auto = true), sessionSource(auto))
        val typed = workTarget(WorkPick(name = "cart-fix", base = "release/2.4"), repo, "feat/checkout-v2", "ahmet/", "x")
        assertEquals(SessionSourceBody(kind = "new", base = "release/2.4", name = "ahmet/cart-fix"), sessionSource(typed))
    }

    @Test fun an_existing_branch_is_used_itself_and_one_only_origin_has_gets_a_tracking_branch() {
        val local = workTarget(WorkPick(mode = BranchMode.Existing, branch = "feat/search-speed"), repo, null, "", "")
        assertEquals(SessionSourceBody(kind = "existing", branch = "feat/search-speed"), sessionSource(local))
        val remote = workTarget(WorkPick(mode = BranchMode.Existing, branch = "origin/feat/login-redirect"), repo, null, "", "")
        assertEquals(WorkTarget.OnBranch("feat/login-redirect", track = "origin/feat/login-redirect"), remote)
        assertEquals(SessionSourceBody(kind = "existing", branch = "origin/feat/login-redirect"), sessionSource(remote))
        assertNull(workTarget(WorkPick(mode = BranchMode.Existing), repo, null, "", ""))
        assertNull(sessionSource(null))
    }

    @Test fun a_branch_the_folder_or_another_session_has_is_held() {
        val inFolder = workTarget(WorkPick(mode = BranchMode.Existing, branch = "feat/checkout-v2"), repo, "feat/checkout-v2", "", "")
        assertEquals("this", heldBranch(inFolder, repo)?.kind)
        val inSession = workTarget(WorkPick(mode = BranchMode.Existing, branch = "fix/cart-total"), repo, "feat/checkout-v2", "", "")
        assertEquals("s-2", heldBranch(inSession, repo)?.id)
        assertNull(heldBranch(workTarget(WorkPick(mode = BranchMode.Existing, branch = "main"), repo, "feat/checkout-v2", "", ""), repo))
        assertNull(heldBranch(workTarget(WorkPick(), repo, "feat/checkout-v2", "ahmet/", "x"), repo))
    }

    @Test fun local_works_in_the_folder_switches_it_at_start_and_uses_a_sessions_worktree_instead_of_checking_out_twice() {
        val stay = workTarget(WorkPick(workIn = WorkIn.Local), repo, "feat/checkout-v2", "", "")
        assertEquals(WorkTarget.Here("feat/checkout-v2", null), stay)
        assertEquals(SessionSourceBody(kind = "local"), sessionSource(stay))
        val switch = workTarget(WorkPick(workIn = WorkIn.Local, switchTo = "main"), repo, "feat/checkout-v2", "", "")
        assertEquals(WorkTarget.Here("main", from = "feat/checkout-v2"), switch)
        assertEquals(SessionSourceBody(kind = "local", switchTo = "main"), sessionSource(switch))
        val reuse = workTarget(WorkPick(workIn = WorkIn.Local, switchTo = "fix/cart-total"), repo, "feat/checkout-v2", "", "")
        assertEquals(WorkTarget.Reuse("fix/cart-total", "s-2", "Cart total off by one"), reuse)
        assertEquals(SessionSourceBody(kind = "reuse", sessionId = "s-2"), sessionSource(reuse))
        // Picking the folder's own branch is no switch.
        assertEquals(WorkTarget.Here("feat/checkout-v2", null), workTarget(WorkPick(workIn = WorkIn.Local, switchTo = "feat/checkout-v2"), repo, "feat/checkout-v2", "", ""))
    }

    @Test fun local_on_a_branch_only_origin_has_gets_a_local_one_that_follows_it() {
        val t = workTarget(WorkPick(workIn = WorkIn.Local, switchTo = "origin/feat/login-redirect"), repo, "feat/checkout-v2", "", "")
        assertEquals(WorkTarget.Here("feat/login-redirect", "feat/checkout-v2", track = "origin/feat/login-redirect"), t)
        assertEquals(SessionSourceBody(kind = "local", switchTo = "origin/feat/login-redirect"), sessionSource(t))
        assertEquals("feat/login-redirect", branchRows(repo).second.first().shown)
    }

    @Test fun the_remote_is_the_one_the_default_branch_tracks_and_none_without_one() {
        assertEquals("origin", remoteName(repo))
        assertNull(remoteName(GitBranches(local = listOf(GitLocalBranch("main", isDefault = true)))))
        assertEquals("main", defaultBase(repo, "feat/checkout-v2"))
        assertEquals("dev", defaultBase(GitBranches(), "dev"))
    }

    @Test fun local_or_worktree_reads_the_brokers_word_and_defaults_to_worktree() {
        assertEquals(WorkIn.Local, WorkIn.of("local"))
        assertEquals(WorkIn.Worktree, WorkIn.of("worktree"))
        assertEquals(WorkIn.Worktree, WorkIn.of(null))
    }
}
