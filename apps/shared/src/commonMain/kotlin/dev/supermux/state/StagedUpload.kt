package dev.supermux.state

import dev.supermux.net.ChunkSource

/** A file staged in the launcher before any session exists (so it can't be uploaded yet).
 *  [HostStore.createSessionWithFirstMessage] uploads these right
 *  after spawn, once there's a session id to upload against (mirrors iOS NewSessionView.spawn() and
 *  the web launcher). Shared copy of Android's session staged-upload. NOT serializable:
 *  the [ChunkSource] streams live file bytes (a platform [ChunkSource] implementation). */
data class StagedUpload(
    val source: ChunkSource,
    val name: String,
    val mime: String,
    val kind: String? = null,
)
