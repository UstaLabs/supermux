package dev.supermux.media

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * two writers of the same file never share a part file. [fileUri] turns an absolute path into the URI
 * the platform's media player opens; [io] is the dispatcher every call runs on (`Dispatchers.IO`,
 * which common code cannot name).
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
        val part = Path(dir, "${key.fileId}.${key.ext}.${Random.nextLong().toULong().toString(16)}.part")
        var committed = false
        try {
            val sink = withContext(io) {
                fs.createDirectories(dir)
                fs.sink(part).buffered()
            }
            val complete = try {
                fill { buf, n -> withContext(io) { sink.write(buf, 0, n) } }
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

    override suspend fun delete(key: MediaKey) = withContext(io) { fs.delete(pathOf(key), mustExist = false) }

    override suspend fun removeHost(host: String) = withContext(io) { deleteTree(Path(base, host)) }

    override suspend fun clear() = withContext(io) { deleteTree(base) }

    override suspend fun sizeBytes(): Long = withContext(io) { treeSize(base) }

    private fun deleteTree(p: Path) {
        val meta = fs.metadataOrNull(p) ?: return
        if (meta.isDirectory) fs.list(p).forEach(::deleteTree)
        fs.delete(p, mustExist = false)
    }

    private fun treeSize(p: Path): Long {
        val meta = fs.metadataOrNull(p) ?: return 0
        return if (meta.isDirectory) fs.list(p).sumOf(::treeSize) else meta.size
    }
}
