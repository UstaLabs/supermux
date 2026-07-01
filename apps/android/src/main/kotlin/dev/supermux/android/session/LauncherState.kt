package dev.supermux.android.session

import kotlinx.serialization.Serializable

/** Sticky New Session launcher preferences — the agent + its last-used model, keyed per agent.
 *  Mirrors the web launcher's `cmux:launcher-prefs` localStorage shape (SessionLauncherView.vue). */
@Serializable
data class LauncherPrefs(
    val agent: String = "claude",
    val models: Map<String, String> = emptyMap(),
)

/** In-progress New Session launcher draft — cleared once a session is actually created.
 *  `workdir` is null when nothing was explicitly restored (so the screen's own
 *  most-recent-session fallback still applies). Mirrors the web launcher's `cmux:launcher-draft`. */
@Serializable
data class LauncherDraft(
    val workdir: String? = null,
    val useWorktree: Boolean = true,
    val baseBranch: String = "",
    val text: String = "",
)
