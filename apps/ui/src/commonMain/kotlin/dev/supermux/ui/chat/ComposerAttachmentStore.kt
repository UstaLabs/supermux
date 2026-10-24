// Where the composer's attachment chips live when they must outlive the composition that staged
// them. On Android a rotation (or a fold, or a dark-mode flip) RECREATES the activity on purpose —
// see the manifest: the window size class is re-evaluated that way — so a chip held in a plain
// `remember` was dropped the moment the phone turned, photo and in-flight upload with it.
//
// A host whose compositions are torn down under the user provides one of these process-wide (see
// `AndroidTheme`); everywhere else — desktop, iOS, web, tests — nothing is provided and the
// composer keeps its chips in composition exactly as before.
package dev.supermux.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Per-session chip lists plus the scope their uploads run in, both outside any composition.
 *
 * Keyed by session like the composer's own `remember(sessionKey)`, so session A's chips still never
 * leak into session B's send — and switching back to A now finds its chips (and their finished
 * uploads) where they were left.
 */
class ComposerAttachmentStore(
    /** Uploads run here, not in a composition scope, so a recreation does not cancel them. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    /** Chip-id / run-sequence counters for one session's list. */
    class Counters {
        var nextId = 0L
        var nextSeq = 0L
    }

    private val chat = mutableMapOf<String, SnapshotStateList<ComposerAttachment>>()
    private val counters = mutableMapOf<String, Counters>()

    /** The New Session launcher's pre-spawn files (there is one launcher, so one list). */
    val launcherStaged: SnapshotStateList<ComposerStagedFile> = mutableStateListOf()
    var launcherNextId = 0L

    fun attachments(sessionKey: String): SnapshotStateList<ComposerAttachment> =
        chat.getOrPut(sessionKey) { mutableStateListOf() }

    fun counters(sessionKey: String): Counters = counters.getOrPut(sessionKey) { Counters() }

    companion object {
        /** The one store for a process whose activities come and go (Android). */
        val process: ComposerAttachmentStore by lazy { ComposerAttachmentStore() }
    }
}

/** Null (the default) keeps attachment state in composition. */
val LocalComposerAttachmentStore = staticCompositionLocalOf<ComposerAttachmentStore?> { null }
