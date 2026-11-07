package dev.supermux.media

/**
 * An in-memory [MediaStore] that records every write and can be told to fail. [resumable] false stands for a
 * store whose entries are files but that cannot keep a partial: its [openResumable] answers null, as the
 * interface's own default does.
 */
class FakeMediaStore(
    override val hasFiles: Boolean = true,
    override val persistent: Boolean = true,
    private val resumable: Boolean = true,
) : MediaStore {
    val entries = LinkedHashMap<String, ByteArray>()

    /** What stopped resumable writes kept, by the same key as [entries]; an open takes its partial out. */
    val partials = mutableMapOf<String, ByteArray>()
    val writes = mutableListOf<String>()
    var failReads = false
    var failWrites = false

    override suspend fun read(key: MediaKey): ByteArray? {
        if (failReads) error("read failed")
        return entries[key.relativePath]
    }

    override suspend fun exists(key: MediaKey): Boolean = entries.containsKey(key.relativePath)

    override suspend fun uri(key: MediaKey): String? =
        if (hasFiles && entries.containsKey(key.relativePath)) "file:///cache/${key.relativePath}" else null

    override suspend fun path(key: MediaKey): String? =
        if (hasFiles && entries.containsKey(key.relativePath)) "/cache/${key.relativePath}" else null

    override suspend fun size(key: MediaKey): Long? = if (hasFiles) entries[key.relativePath]?.size?.toLong() else null

    override suspend fun write(key: MediaKey, bytes: ByteArray): Boolean =
        writeStream(key) { append ->
            append(bytes, bytes.size)
            true
        }

    override suspend fun writeStream(key: MediaKey, fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean): Boolean {
        writes += key.relativePath
        if (failWrites) error("disk full")
        val chunks = mutableListOf<ByteArray>()
        if (!fill { buf, n -> chunks += buf.copyOf(n) }) return false
        entries[key.relativePath] = concat(chunks)
        return true
    }

    override suspend fun openResumable(key: MediaKey): ResumableWrite? {
        if (!hasFiles || !resumable) return null
        writes += key.relativePath
        return FakeResumableWrite(key.relativePath, partials.remove(key.relativePath) ?: ByteArray(0))
    }

    private inner class FakeResumableWrite(private val id: String, kept: ByteArray) : ResumableWrite {
        private val chunks = mutableListOf(kept)
        private var ended = false

        override val offset: Long = kept.size.toLong()

        override suspend fun append(buf: ByteArray, n: Int) {
            check(!ended) { "the write has ended" }
            chunks += buf.copyOf(n)
        }

        override suspend fun commit(): Boolean {
            if (!end()) return false
            if (failWrites) return false
            entries[id] = concat(chunks)
            return true
        }

        override suspend fun retain() {
            if (end()) partials[id] = concat(chunks)
        }

        override suspend fun abandon() {
            end()
        }

        /** True for the first end only. */
        private fun end(): Boolean {
            val first = !ended
            ended = true
            return first
        }
    }

    override suspend fun delete(key: MediaKey) {
        entries.remove(key.relativePath)
    }

    // Partials go too, as the real store's tree delete takes them with the entries.
    override suspend fun removeHost(host: String) {
        entries.keys.removeAll { it.startsWith("$host/") }
        partials.keys.removeAll { it.startsWith("$host/") }
    }

    override suspend fun clear() {
        entries.clear()
        partials.clear()
    }

    override suspend fun sizeBytes(): Long = entries.values.sumOf { it.size.toLong() }
}

fun concat(chunks: List<ByteArray>): ByteArray {
    val out = ByteArray(chunks.sumOf { it.size })
    var at = 0
    for (c in chunks) {
        c.copyInto(out, at)
        at += c.size
    }
    return out
}

/** A [RangeDownload] over [content] that clamps like a compliant server; [calls] records each range. */
fun rangeServer(content: ByteArray, calls: MutableList<Pair<Long, Long>> = mutableListOf()): RangeDownload =
    { from, to, sink ->
        calls += from to to
        if (from < content.size) {
            val end = minOf(to, content.size - 1L)
            val slice = content.copyOfRange(from.toInt(), end.toInt() + 1)
            sink(slice, slice.size)
        }
        RangeResult(content.size.toLong())
    }
