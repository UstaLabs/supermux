// Diff / inline code-review state, split out of EditorState.kt (which still delegates to it, so
// every call site is unchanged). Landed in M4g-2 as a verbatim port of Android
// EditorState.kt:44-47,138-155 (itself EditorState.swift:61-77) — keep in sync until a shared UI
// module exists.
package dev.supermux.desktop.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsRefsResult
import dev.supermux.net.RepoDiff
import dev.supermux.net.RepoRefs
import dev.supermux.net.ReviewComment

/** Diff / inline code-review state (M4g-2) — per-session, survives panel switches like the tree
 *  (Android EditorState.kt:44-47 parity). */
class DiffState {
    var showDiff by mutableStateOf(false)
    var diffRepos by mutableStateOf<List<RepoDiff>>(emptyList())
    var diffComments by mutableStateOf<List<ReviewComment>>(emptyList())
    var diffLoading by mutableStateOf(false)

    /** Selected diff-base spec passed to fs/diff ("session-start"/"head"/"commit:<sha>"/
     *  "branch:<name>"). The compare target is always the working tree (parity web/Android DiffView
     *  base picker — Android EditorState.kt:51-53). */
    var diffBase by mutableStateOf("session-start")

    /** Branches + recent commits per repo for the base picker's submenus (parity fsRefs — Android
     *  EditorState.kt:54-56). Fetched alongside the diff in [loadDiff]. */
    var diffRefs by mutableStateOf<List<RepoRefs>>(emptyList())

    /** Fetch the diff for the current [diffBase] + the ref list for the base picker; only flip
     *  [showDiff] on a non-null diff so a failed fetch never opens an empty pane (parity Android
     *  EditorState.kt:146-156 / EditorState.swift:67). */
    suspend fun loadDiff(fsDiff: suspend (String) -> FsDiffResult?, fsRefs: suspend () -> FsRefsResult?) {
        diffLoading = true
        val res = fsDiff(diffBase)
        diffRefs = fsRefs()?.repos ?: emptyList()
        diffLoading = false
        if (res == null) return
        diffRepos = res.repos
        diffComments = res.comments
        showDiff = true
    }

    /** Re-fetch the diff in place for [diffBase] (after add/resolve/submit or a base change) — does
     *  not toggle [showDiff] (parity Android EditorState.kt:159-164). */
    suspend fun reloadDiff(fsDiff: suspend (String) -> FsDiffResult?) {
        val res = fsDiff(diffBase) ?: return
        diffRepos = res.repos
        diffComments = res.comments
    }

    /** Switch the diff base and re-fetch in place (parity Android EditorState.kt:166-170 / web setBase). */
    suspend fun setDiffBase(base: String, fsDiff: suspend (String) -> FsDiffResult?) {
        diffBase = base
        reloadDiff(fsDiff)
    }
}
