package dev.supermux.android.session

import dev.supermux.net.ChunkSource
import kotlinx.serialization.Serializable

/** A file staged in the launcher before any session exists (so it can't be uploaded yet).
 *  [dev.supermux.android.AppViewModel.createSessionWithFirstMessage] uploads these right after
 *  spawn, once there's a session id to upload against — mirrors iOS NewSessionView.spawn() and
 *  the web launcher. Not serializable: the [ChunkSource] streams live file bytes. */
data class StagedUpload(
    val source: ChunkSource,
    val name: String,
    val mime: String,
    val kind: String? = null,
)

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
