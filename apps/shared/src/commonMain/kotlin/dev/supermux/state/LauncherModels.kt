package dev.supermux.state

import kotlinx.serialization.Serializable

/** Sticky New Session launcher preferences — the agent + its last-used model, keyed per agent.
 *  Shared by desktop and Android. */
@Serializable
data class LauncherPrefs(
    val agent: String = "claude",
    val models: Map<String, String> = emptyMap(),
    val reasoningLevels: Map<String, String> = emptyMap(),
    /** Last permission-mode id per agent. */
    val permissionModes: Map<String, String> = emptyMap(),
    /**
     * Last location picked per persistent project, keyed by
     * [dev.supermux.workspace.projectLocationKey] (host + project id). A path that no longer
     * belongs to its project is ignored by [dev.supermux.workspace.launchLocation].
     */
    val projectLocations: Map<String, String> = emptyMap(),
)

/** In-progress New Session launcher draft — cleared once a session is actually created.
 *  Shared by desktop and Android. `workdir` is null when nothing
 *  was explicitly restored (so the screen's own most-recent-session fallback still applies). */
@Serializable
data class LauncherDraft(
    val workdir: String? = null,
    val useWorktree: Boolean = true,
    val baseBranch: String = "",
    val text: String = "",
)
