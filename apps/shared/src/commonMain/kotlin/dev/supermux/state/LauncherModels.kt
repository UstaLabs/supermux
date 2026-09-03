package dev.supermux.state

import kotlinx.serialization.Serializable

/** Sticky New Session launcher preferences — the agent + its last-used model, keyed per agent.
 *  Desktop copy of `dev.supermux.android.session.LauncherPrefs`. */
@Serializable
data class LauncherPrefs(
    val agent: String = "claude",
    val models: Map<String, String> = emptyMap(),
    val reasoningLevels: Map<String, String> = emptyMap(),
)

/** In-progress New Session launcher draft — cleared once a session is actually created.
 *  Desktop copy of `dev.supermux.android.session.LauncherDraft`. `workdir` is null when nothing
 *  was explicitly restored (so the screen's own most-recent-session fallback still applies). */
@Serializable
data class LauncherDraft(
    val workdir: String? = null,
    val useWorktree: Boolean = true,
    val baseBranch: String = "",
    val text: String = "",
)
