package dev.supermux.media

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.random.Random

/**
 * [MediaStore] on the local file system — desktop, Android and iOS: `<root>/v1/<host>/<fileId>.<ext>`.
 *
 * A write lands in a uniquely named `.part` file beside its target and is moved into place only when
 * complete, so a reader (this process, a second window, the next launch) sees a whole file or none;
 * two writers of the same file never share a part file. A [ResumableWrite] that stops keeps what arrived
 * as `<fileId>.<ext>.partial`, which no reader takes for the entry; the next open claims it by moving it
 * to a part file of its own, so two opens never continue the same bytes. [fileUri] turns an absolute
 * path into the URI the platform's media player opens; [io] is the dispatcher every call runs on
 * (`Dispatchers.IO`, which common code cannot name).
 */
class FileMediaStore(
    root: String,
    private val fileUri: (String) -> String,
    private val io: CoroutineDispatcher,
    private val fs: FileSystem = SystemFileSystem,
) : MediaStore {
    private val base = Path(root, "v1")

    override val hasFiles: Boolean get() = true
    override val persistent: Boolean get() = true

    private fun pathOf(key: MediaKey) = Path(Path(base, key.host), "${key.fileId}.${key.ext}")

    /** A working file of one write's own: the random suffix is what keeps two writers of an entry apart. */
    private fun newPart(dir: Path, key: MediaKey) =
        Path(dir, "${key.fileId}.${key.ext}.${Random.nextLong().toULong().toString(16)}.part")

    private fun isFile(p: Path) = fs.metadataOrNull(p)?.isRegularFile == true

    override suspend fun read(key: MediaKey): ByteArray? = withContext(io) {
        val p = pathOf(key)
        if (!isFile(p)) null else fs.source(p).buffered().use { it.readByteArray() }
    }

    override suspend fun exists(key: MediaKey): Boolean = withContext(io) { isFile(pathOf(key)) }

    override suspend fun uri(key: MediaKey): String? = withContext(io) {
        val p = pathOf(key)
        if (isFile(p)) fileUri(p.toString()) else null
    }

    override suspend fun path(key: MediaKey): String? = withContext(io) {
        val p = pathOf(key)
        if (isFile(p)) p.toString() else null
    }

    override suspend fun size(key: MediaKey): Long? = withContext(io) {
        fs.metadataOrNull(pathOf(key))?.takeIf { it.isRegularFile }?.size
    }

    override suspend fun write(key: MediaKey, bytes: ByteArray): Boolean =
        writeStream(key) { append ->
            append(bytes, bytes.size)
            true
        }

    override suspend fun writeStream(
        key: MediaKey,
        fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean,
    ): Boolean {
        val target = pathOf(key)
        val dir = Path(base, key.host)
        val part = newPart(dir, key)
        var committed = false
        try {
            val sink = withContext(io) {
                fs.createDirectories(dir)
                fs.sink(part).buffered()
            }
            // The whole fill on [io]: a seed reads its ChunkSource with blocking calls, a download
            // writes every range as it arrives — neither belongs on the caller's dispatcher.
            val complete = try {
                withContext(io) { fill { buf, n -> sink.write(buf, 0, n) } }
            } finally {
                withContext(NonCancellable + io) { sink.close() }
            }
            if (!complete) return false
            withContext(io) {
                try {
                    fs.atomicMove(part, target)
                } catch (e: Exception) {
                    // Another writer put the same immutable file in place first: theirs is as good.
                    if (!isFile(target)) throw e
                }
            }
            committed = true
            return true
        } finally {
            withContext(NonCancellable + io) {
                if (!committed || fs.exists(part)) runCatching { fs.delete(part, mustExist = false) }
            }
        }
    }

    override suspend fun openResumable(key: MediaKey): ResumableWrite? = withContext(io) {
        val dir = Path(base, key.host)
        fs.createDirectories(dir)
        val partial = Path(dir, "${key.fileId}.${key.ext}.partial")
        val part = newPart(dir, key)
        // The move is the claim: of two opens after the same partial one move succeeds, the other starts empty.
        if (fs.exists(partial)) runCatching { fs.atomicMove(partial, part) }
        val offset = fs.metadataOrNull(part)?.size ?: 0L
        FileResumableWrite(fs.sink(part, append = true).buffered(), part, partial, pathOf(key), offset)
    }

    /**
     * One write into [part], a working file it owns from its open until its end; [partial] is where a
     * [retain] leaves the bytes for the next open, and [target] where a [commit] puts them.
     */
    private inner class FileResumableWrite(
        private val sink: Sink,
        private val part: Path,
        private val partial: Path,
        private val target: Path,
        override val offset: Long,
    ) : ResumableWrite {
        private val lock = SynchronizedObject()
        private var ended = false

        override suspend fun append(buf: ByteArray, n: Int) = withContext(io) { sink.write(buf, 0, n) }

        override suspend fun commit(): Boolean =
            end {
                try {
                    sink.close()
                    fs.atomicMove(part, target)
                    true
                } catch (e: Exception) {
                    // Whatever failed, the entry is stored if another writer put the same immutable file in place first.
                    isFile(target)
                } finally {
                    discardPart()
                }
            } ?: false

        override suspend fun retain() {
            end {
                // A flush that fails still leaves a prefix of the stream on disk, and a prefix is all a partial has to be.
                runCatching { sink.close() }
                runCatching {
                    fs.delete(partial, mustExist = false)
                    fs.atomicMove(part, partial)
                }
                discardPart()
            }
        }

        override suspend fun abandon() {
            end {
                runCatching { sink.close() }
                discardPart()
            }
        }

        /**
         * Runs [block] when this is the write's first end and does nothing for a later one, on [io] and out of
         * reach of cancellation: a download that was cancelled is the one whose bytes still have to go somewhere.
         */
        private suspend fun <T> end(block: () -> T): T? {
            val first = synchronized(lock) { if (ended) false else { ended = true; true } }
            return if (first) withContext(NonCancellable + io) { block() } else null
        }

        private fun discardPart() {
            runCatching { fs.delete(part, mustExist = false) }
        }
    }

    override suspend fun delete(key: MediaKey) = withContext(io) { fs.delete(pathOf(key), mustExist = false) }

    override suspend fun removeHost(host: String) = withContext(io) { deleteAll(Path(base, host)) }

    override suspend fun clear() = withContext(io) { deleteAll(base) }

    override suspend fun sizeBytes(): Long = withContext(io) { treeSize(base) }

    /**
     * Delete [p] and everything under it, carrying on past an entry that will not go (a clip the
     * player holds open on Windows), then report how many stayed — one stuck file must not keep
     * every other one on disk.
     */
    private fun deleteAll(p: Path) {
        val stuck = mutableListOf<String>()
        deleteTree(p, stuck)
        if (stuck.isNotEmpty()) error("${stuck.size} cache entries could not be deleted, first: ${stuck.first()}")
    }

    private fun deleteTree(p: Path, stuck: MutableList<String>) {
        val meta = fs.metadataOrNull(p) ?: return
        if (meta.isDirectory) {
            runCatching { fs.list(p) }.onFailure { stuck += "$p: $it" }.getOrNull()?.forEach { deleteTree(it, stuck) }
        }
        runCatching { fs.delete(p, mustExist = false) }.onFailure { stuck += "$p: $it" }
    }

    private fun treeSize(p: Path): Long {
        val meta = fs.metadataOrNull(p) ?: return 0
        return if (meta.isDirectory) fs.list(p).sumOf(::treeSize) else meta.size
    }
}
