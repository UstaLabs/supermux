package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.localizedPlural

/**
 * What the composer footer says about a session's work tree (#72): one fact, the one that asks for
 * the next step, or nothing when the tree holds nothing. A worktree session (base mode) counts
 * against its base by name; a plain checkout (remote mode) against its upstream.
 */
sealed interface ComposerGitSign {
    data class Changed(val files: Int) : ComposerGitSign
    data class NotInBase(val commits: Int, val base: String) : ComposerGitSign
    data class BaseMoved(val commits: Int, val base: String) : ComposerGitSign
    data class ToPush(val commits: Int) : ComposerGitSign
    data class ToPull(val commits: Int) : ComposerGitSign
}

fun composerGitSign(git: GitLiteStatusDto?): ComposerGitSign? {
    if (git == null) return null
    if (git.dirty > 0) return ComposerGitSign.Changed(git.dirty)
    if (git.mode == "base") {
        if (git.compareRef.isEmpty()) return null
        if (git.ahead > 0) return ComposerGitSign.NotInBase(git.ahead, git.compareRef)
        if (git.behind > 0) return ComposerGitSign.BaseMoved(git.behind, git.compareRef)
        return null
    }
    if (git.unpublished == true) return null
    if (git.ahead > 0) return ComposerGitSign.ToPush(git.ahead)
    if (git.behind > 0) return ComposerGitSign.ToPull(git.behind)
    return null
}

@Composable
fun ComposerGitSign.label(): String = when (this) {
    is ComposerGitSign.Changed -> localizedPlural(Plurals.git_sign_changed, files, files)
    is ComposerGitSign.NotInBase -> localizedPlural(Plurals.git_sign_not_in_base, commits, commits, base)
    is ComposerGitSign.BaseMoved -> localizedPlural(Plurals.git_sign_base_moved, commits, commits, base)
    is ComposerGitSign.ToPush -> localizedPlural(Plurals.git_sign_to_push, commits, commits)
    is ComposerGitSign.ToPull -> localizedPlural(Plurals.git_sign_to_pull, commits, commits)
}
