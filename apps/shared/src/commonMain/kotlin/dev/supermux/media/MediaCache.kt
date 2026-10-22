package dev.supermux.media

import dev.supermux.net.ChunkSource
import dev.supermux.proto.Attachment
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The client-side cache of chat attachments: memory first, then the [store], then the network.
 *
 * A broker `file_id` is written once and never rewritten (`/files/<id>` is served `immutable`), so
 * nothing here ever revalidates. Nothing is evicted in the background either: an entry goes only
 * when the user presses Clear ([clear]) or forgets its host ([removeHost]); the memory tier alone is
 * bounded. Every store failure is logged and falls through to the network — no cache problem may
 * stop a file from showing.
 *
 * @param scope runs shared downloads and the writes nobody waits for (write-behind, seeding).
 */
class MediaCache(
    private val store: MediaStore,
    private val scope: CoroutineScope,
    private val memory: MemoryLru = MemoryLru(DEFAULT_MEMORY_BYTES, DEFAULT_MEMORY_ENTRY_BYTES),
    private val rangeBytes: Long = DEFAULT_RANGE_BYTES,
    private val log: (String) -> Unit = { println(it) },
) {
    /** False when nothing outlives the process (the web without Cache Storage). */
    val persistent: Boolean get() = store.persistent

    private val lock = SynchronizedObject()
    private val inflight = HashMap<String, Deferred<*>>()
    private var generation = 0L

    /** The bytes if they are in memory, without suspending — a row re-entering the screen paints at once. */
    fun peek(host: String, att: Attachment): ByteArray? = memory.get(memKey(host, att.file_id))

    /**
     * The attachment's bytes, or null when they cannot be had. [fetch] is the plain one-shot GET,
     * [download] the ranged one (null where the platform streams nothing to disk). [refresh] drops
     * every cached copy first — a copy that would not decode.
     */
    suspend fun bytes(
        host: String,
        att: Attachment,
        fetch: suspend () -> ByteArray?,
        download: RangeDownload?,
        refresh: Boolean = false,
    ): ByteArray? {
        val mk = memKey(host, att.file_id)
        val key = MediaKey.of(host, att.file_id, att.name, att.mime)
        if (refresh) {
            memory.remove(mk)
            if (key != null) safe("delete", Unit) { store.delete(key) }
        } else {
            memory.get(mk)?.let {
                log("[media] ${att.file_id} <- memory")
                return it
            }
        }
        return shared(if (refresh) "$mk#refresh" else mk) {
            load(mk, key, att, fetch, download, readDisk = !refresh)
        }
    }

    /**
     * A file URI the platform's media player can open — the file downloaded into the store first if
     * needed. Null where entries are not files (the web), for a key that cannot be stored, or when the
     * download fails; the caller then stages bytes the old way.
     */
    suspend fun playableUri(host: String, att: Attachment, download: RangeDownload): String? {
        if (!store.hasFiles) return null
        val key = MediaKey.of(host, att.file_id, att.name, att.mime) ?: return null
        safe("uri", null) { store.uri(key) }?.let {
            log("[media] ${att.file_id} <- disk (file)")
            return it
        }
        return shared(memKey(host, att.file_id) + "#file") {
            if (downloadToStore(key, download, att.size, currentGeneration())) {
                safe("uri", null) { store.uri(key) }?.also { log("[media] ${att.file_id} <- network (file)") }
            } else {
                log("[media] ${att.file_id}: download failed")
                null
            }
        }
    }

    /**
     * Keep the user's own upload: copy [source] into the store in the background, so the file the
     * user just sent never comes back over the network. Skipped when already stored; a failure is
     * logged and dropped — it never touches the send.
     */
    fun seed(host: String, fileId: String, name: String?, mime: String?, source: ChunkSource) {
        if (!store.persistent) return
        val key = MediaKey.of(host, fileId, name, mime) ?: return
        val gen = currentGeneration()
        scope.launch {
            if (safe("exists", false) { store.exists(key) }) return@launch
            val ok = safe("seed", false) {
                store.writeStream(key) { append ->
                    var offset = 0L
                    while (offset < source.size) {
                        val chunk = source.read(offset, SEED_CHUNK_BYTES)
                        if (chunk.isEmpty()) return@writeStream false
                        append(chunk, chunk.size)
                        offset += chunk.size
                    }
                    true
                }
            }
            if (ok && keepIfCurrent(key, gen)) log("[media] $fileId seeded from the upload")
        }
    }

    /** Settings → Storage → Clear: every host's files, memory and store. */
    suspend fun clear() {
        bumpGeneration()
        memory.clear()
        safe("clear", Unit) { store.clear() }
        log("[media] cleared")
    }

    /** A forgotten host's files. */
    suspend fun removeHost(host: String) {
        bumpGeneration()
        memory.removePrefix("$host/")
        if (MediaKey.isValidHost(host)) safe("removeHost", Unit) { store.removeHost(host) }
    }

    /** What Settings shows: the store's size, or the memory tier's when nothing persists. */
    suspend fun sizeBytes(): Long =
        if (store.persistent) safe("size", 0L) { store.sizeBytes() } else memory.sizeBytes

    private suspend fun load(
        mk: String,
        key: MediaKey?,
        att: Attachment,
        fetch: suspend () -> ByteArray?,
        download: RangeDownload?,
        readDisk: Boolean,
    ): ByteArray? {
        val gen = currentGeneration()
        if (key != null && readDisk && store.persistent) {
            safe("read", null) { store.read(key) }?.let {
                memory.put(mk, it)
                log("[media] ${att.file_id} <- disk")
                return it
            }
        }
        // A files platform streams the download straight to disk, a range at a time — a 500 MB clip
        // never sits in memory and no request outlives its timeout — then reads it back.
        val streamed = key != null && store.hasFiles && download != null
        if (key != null && store.hasFiles && download != null) {
            if (downloadToStore(key, download, att.size, gen)) {
                safe("read", null) { store.read(key) }?.let {
                    memory.put(mk, it)
                    log("[media] ${att.file_id} <- network")
                    return it
                }
            }
        }
        val fetched = safe("fetch", null) { fetch() }
        if (fetched == null) {
            log("[media] ${att.file_id}: download failed")
            return null
        }
        memory.put(mk, fetched)
        if (key != null && store.persistent && !streamed) {
            scope.launch { if (safe("write", false) { store.write(key, fetched) }) keepIfCurrent(key, gen) }
        }
        log("[media] ${att.file_id} <- network")
        return fetched
    }

    private suspend fun downloadToStore(key: MediaKey, download: RangeDownload, sizeHint: Long?, gen: Long): Boolean {
        val ok = safe("download", false) {
            store.writeStream(key) { append -> downloadInRanges(download, rangeBytes, sizeHint, append) }
        }
        return ok && keepIfCurrent(key, gen)
    }

    /** A write that finished after a Clear (or a forget) started must not resurrect what was cleared. */
    private suspend fun keepIfCurrent(key: MediaKey, gen: Long): Boolean {
        if (currentGeneration() == gen) return true
        safe("delete", Unit) { store.delete(key) }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(slot: String, block: suspend () -> T): T {
        val job = synchronized(lock) {
            (inflight[slot] as Deferred<T>?) ?: scope.async { block() }.also { started ->
                inflight[slot] = started
                started.invokeOnCompletion { synchronized(lock) { if (inflight[slot] === started) inflight.remove(slot) } }
            }
        }
        return job.await()
    }

    private suspend fun <T> safe(op: String, fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (c: CancellationException) {
            // A timeout surfaces as a CancellationException while we are still active: a failure, not ours.
            currentCoroutineContext().ensureActive()
            log("[media] $op failed: ${c.message}")
            fallback
        } catch (e: Throwable) {
            log("[media] $op failed: $e")
            fallback
        }

    private fun currentGeneration(): Long = synchronized(lock) { generation }

    private fun bumpGeneration() {
        synchronized(lock) { generation++ }
    }

    private fun memKey(host: String, fileId: String) = "$host/$fileId"

    companion object {
        const val DEFAULT_MEMORY_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_MEMORY_ENTRY_BYTES: Long = 8L * 1024 * 1024
        const val DEFAULT_RANGE_BYTES: Long = 4L * 1024 * 1024
        private const val SEED_CHUNK_BYTES = 1024 * 1024
    }
}
