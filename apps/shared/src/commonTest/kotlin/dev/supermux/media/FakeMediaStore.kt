package dev.supermux.media

/** An in-memory [MediaStore] that records every write and can be told to fail. */
class FakeMediaStore(
    override val hasFiles: Boolean = true,
    override val persistent: Boolean = true,
) : MediaStore {
    val entries = LinkedHashMap<String, ByteArray>()
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

    override suspend fun delete(key: MediaKey) {
        entries.remove(key.relativePath)
    }

    override suspend fun removeHost(host: String) {
        entries.keys.removeAll { it.startsWith("$host/") }
    }

    override suspend fun clear() = entries.clear()

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
