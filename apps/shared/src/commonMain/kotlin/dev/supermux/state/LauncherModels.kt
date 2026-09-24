package dev.supermux.state

import kotlinx.serialization.Serializable

/** Sticky New Session launcher preferences — the agent + its last-used model, keyed per agent.
 *  Shared by desktop and Android. */
@Serializable
data class LauncherPrefs(
    val agent: String = "claude",
    val models: Map<String, String> = emptyMap(),
    val reasoningLevels: Map<String, String> = emptyMap(),
    /**
     * Last location picked per persistent project, keyed by
     * [dev.supermux.workspace.projectLocationKey] (host + project id). A path that no longer
     * belongs to its project is ignored by [dev.supermux.workspace.launchLocation].
     */
    val projectLocations: Map<String, String> = emptyMap(),
    /**
     * Folders whose sessions run WITHOUT an isolated worktree, keyed by [worktreeChoiceKey]. The
     * worktree is on by default, so only the opt-outs are stored; a pick sticks to its folder and
     * never leaks into another project.
     */
    val worktreeOff: Set<String> = emptySet(),
)

/** [LauncherPrefs.worktreeOff]'s key: the folder on ONE host (paths are per-machine). */
fun worktreeChoiceKey(hostId: String, path: String): String = "$hostId|$path"

/** In-progress New Session launcher draft — cleared once a session is actually created.
 *  Shared by desktop and Android. `workdir` is null when nothing
 *  was explicitly restored (so the screen's own most-recent-session fallback still applies).
 *  `useWorktree` is no longer read — the choice is per folder ([LauncherPrefs.worktreeOff]) —
 *  but stays so stored drafts keep decoding; `baseBranch` applies only with a `workdir`. */
@Serializable
data class LauncherDraft(
    val workdir: String? = null,
    val useWorktree: Boolean = true,
    val baseBranch: String = "",
    val text: String = "",
)
