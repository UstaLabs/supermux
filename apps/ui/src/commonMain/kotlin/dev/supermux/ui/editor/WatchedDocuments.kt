package dev.supermux.ui.editor

/**
 * What the "changed on disk" banner needs from a set of open files, whoever owns them: the
 * workspace's [DocumentStore] and the session-scoped editor's [EditorState] (Android's
 * session-only chat) both implement it, and [dev.supermux.ui.files.FileStaleWatcher] drives either.
 * Paths are workdir-relative (leading slash optional).
 */
interface WatchedDocuments {
    /** Paths of the open files. A snapshot read: a composable reading it follows opens/closes. */
    val openPaths: Collection<String>

    /** Something that must know when these files are written by US (the stale-banner tracker: our
     *  own save changes the file's mtime too, and must not read as "changed on disk"). */
    interface WriteObserver {
        fun writeStarted(path: String)
        /** Always called after [writeStarted], whether the write succeeded, failed or threw. */
        fun writeFinished(path: String, ok: Boolean)
    }

    /** Register [observer]; the returned function unregisters it. */
    fun observeWrites(observer: WriteObserver): () -> Unit

    /** Record disk-change notifications → the reload banner. */
    fun markChanged(paths: List<String>)

    /** The watcher saw [paths] change on disk. A file with no unsaved edits just takes the new
     *  text; only one that has them (or can no longer be read) gets the banner via [markChanged]. */
    fun changedOnDisk(paths: List<String>) = markChanged(paths)
}
