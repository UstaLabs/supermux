package dev.supermux.state

import dev.supermux.net.ChunkSource

/** A file staged in the launcher before any session exists (so it can't be uploaded yet).
 *  [dev.supermux.desktop.state.DesktopAppState.createSessionWithFirstMessage] uploads these right
 *  after spawn, once there's a session id to upload against (mirrors iOS NewSessionView.spawn() and
 *  the web launcher). Desktop copy of `dev.supermux.android.session.StagedUpload`. NOT serializable:
 *  the [ChunkSource] streams live file bytes (a desktop [dev.supermux.desktop.upload.FileChunkSource]). */
data class StagedUpload(
    val source: ChunkSource,
    val name: String,
    val mime: String,
    val kind: String? = null,
)
