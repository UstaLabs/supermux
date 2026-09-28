package dev.supermux.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.supermux.fs.DirSnapshot
import dev.supermux.fs.DirState
import dev.supermux.fs.FileSystemService
import dev.supermux.ui.editor.DocumentStore

/**
 * Feeds [documents]' "changed on disk" banner from folder subscriptions: one subscription per
 * distinct parent folder of an open document (shared by every document in it, released when the
 * last one there closes), compared by a [FileChangeTracker]. Our own saves are bracketed through
 * [DocumentStore.observeWrites] so they never raise the banner. Draws nothing.
 */
@Composable
fun FileStaleWatcher(fileSystem: FileSystemService?, workdir: String, documents: DocumentStore) {
    if (fileSystem == null) return
    val tracker = remember(fileSystem, workdir, documents) { FileChangeTracker() }
    // abs → the workdir-relative path the document store knows it by.
    val relByAbs = documents.openPaths.associateBy { absoluteInWorkdir(workdir, it) }
    val currentRelByAbs = rememberUpdatedState(relByAbs)
    // Runs after composition and before the collectors below start, so a folder's first snapshot
    // always finds its files tracked.
    SideEffect {
        tracker.trackedPaths.filter { it !in relByAbs }.forEach(tracker::untrack)
        relByAbs.keys.forEach(tracker::track)
    }
    DisposableEffect(tracker) {
        val stop = documents.observeWrites(object : DocumentStore.WriteObserver {
            override fun writeStarted(path: String) = tracker.beginWrite(absoluteInWorkdir(workdir, path))
            override fun writeFinished(path: String, ok: Boolean) = tracker.endWrite(absoluteInWorkdir(workdir, path))
        })
        onDispose { stop() }
    }
    val folders = relByAbs.keys.mapNotNull(::parentOf).toSet()
    for (dir in folders) {
        key(dir) {
            DisposableEffect(fileSystem, dir) {
                val sub = fileSystem.subscribe(dir)
                onDispose { sub.close() }
            }
            LaunchedEffect(tracker, dir) {
                fileSystem.dir(dir).collect { state ->
                    // Only a live snapshot counts: a Loading/Failed state's `previous` may predate
                    // the document's read, and would make the next fresh one look like a change.
                    val snap = when (state) {
                        is DirState.Ready -> state.snap
                        DirState.Gone -> DirSnapshot(path = dir, version = "gone")
                        else -> null
                    } ?: return@collect
                    val changed = tracker.onSnapshot(dir, snap)
                    val rels = changed.mapNotNull { currentRelByAbs.value[it] }
                    if (rels.isNotEmpty()) documents.markChanged(rels)
                }
            }
        }
    }
}
