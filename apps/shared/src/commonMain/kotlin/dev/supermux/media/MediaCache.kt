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

    // Bumped by Clear (every host) and by forgetting one host, so a write that started before either
    // is dropped when it finishes — without touching another host's downloads.
    private var clearGeneration = 0L
    private val hostGenerations = HashMap<String, Long>()

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
            // A load of this file already under way would put its copy back into memory after ours.
            synchronized(lock) { inflight[mk] }?.join()
            memory.remove(mk)
            if (key != null) safe("delete", Unit) { store.delete(key) }
        } else {
            memory.get(mk)?.let {
                log("[media] ${att.file_id} <- memory")
                return it
            }
        }
        return shared(if (refresh) "$mk#refresh" else mk) {
            load(host, mk, key, att, fetch, download, readDisk = !refresh)
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
            if (downloadToStore(key, download, att.size, stamp(host)) == Stored.Yes) {
                safe("uri", null) { store.uri(key) }?.also { log("[media] ${att.file_id} <- network (file)") }
            } else {
                log("[media] ${att.file_id}: download failed")
                null
            }
        }
    }

    /**
     * Keep the user's own upload: copy [source] into the store in the background, so the file the
     * user just sent never comes back over the network. [size] is what the broker stored: a source
     * that no longer holds exactly that many bytes (deleted, truncated or grown since the upload) is
     * not copied, so it can never stand in for the real file. Skipped when already stored, and where
     * entries are not files (the web) for anything over [SEED_MAX_WITHOUT_FILES] — the browser's
     * source reads synchronously on the only thread. A failure is logged and dropped — it never
     * touches the send.
     */
    fun seed(host: String, fileId: String, name: String?, mime: String?, size: Long, source: ChunkSource) {
        if (!store.persistent || size <= 0) return
        if (!store.hasFiles && size > SEED_MAX_WITHOUT_FILES) return
        val key = MediaKey.of(host, fileId, name, mime) ?: return
        val stamp = stamp(host)
        scope.launch {
            if (safe("exists", false) { store.exists(key) }) return@launch
            val ok = safe("seed", false) {
                if (source.size != size) {
                    log("[media] $fileId not seeded: the source holds ${source.size} bytes, the broker $size")
                    return@safe false
                }
                store.writeStream(key) { append ->
                    var offset = 0L
                    while (offset < size) {
                        val chunk = source.read(offset, minOf(SEED_CHUNK_BYTES.toLong(), size - offset).toInt())
                        if (chunk.isEmpty()) return@writeStream false
                        append(chunk, chunk.size)
                        offset += chunk.size
                    }
                    offset == size
                }
            }
            if (ok && keepIfCurrent(key, stamp)) log("[media] $fileId seeded from the upload")
        }
    }

    /** Settings → Storage → Clear: every host's files, memory and store. */
    suspend fun clear() {
        synchronized(lock) { clearGeneration++ }
        memory.clear()
        safe("clear", Unit) { store.clear() }
        log("[media] cleared")
    }

    /** A forgotten host's files. */
    suspend fun removeHost(host: String) {
        synchronized(lock) { hostGenerations[host] = (hostGenerations[host] ?: 0L) + 1 }
        memory.removePrefix("$host/")
        if (MediaKey.isValidHost(host)) safe("removeHost", Unit) { store.removeHost(host) }
    }

    /** What Settings shows: the store's size, or the memory tier's when nothing persists. */
    suspend fun sizeBytes(): Long =
        if (store.persistent) safe("size", 0L) { store.sizeBytes() } else memory.sizeBytes

    private suspend fun load(
        host: String,
        mk: String,
        key: MediaKey?,
        att: Attachment,
        fetch: suspend () -> ByteArray?,
        download: RangeDownload?,
        readDisk: Boolean,
    ): ByteArray? {
        val stamp = stamp(host)
        if (key != null && readDisk && store.persistent) {
            safe("read", null) { store.read(key) }?.let {
                memory.put(mk, it)
                log("[media] ${att.file_id} <- disk")
                return it
            }
        }
        // A files platform streams the download straight to disk, a range at a time — a 500 MB clip
        // never sits in memory and no request outlives its timeout — then reads it back. Only a store
        // failure falls through to the one-shot fetch: after a network failure it would just be the
        // same download again, whole and in memory.
        val streamed = key != null && store.hasFiles && download != null
        if (key != null && store.hasFiles && download != null) {
            when (downloadToStore(key, download, att.size, stamp)) {
                Stored.Yes -> safe("read", null) { store.read(key) }?.let {
                    memory.put(mk, it)
                    log("[media] ${att.file_id} <- network")
                    return it
                }
                Stored.NetworkFailed -> {
                    log("[media] ${att.file_id}: download failed")
                    return null
                }
                Stored.StoreFailed -> Unit
            }
        }
        val fetched = safe("fetch", null) { fetch() }
        if (fetched == null) {
            log("[media] ${att.file_id}: download failed")
            return null
        }
        memory.put(mk, fetched)
        if (key != null && store.persistent && !streamed) {
            scope.launch { if (safe("write", false) { store.write(key, fetched) }) keepIfCurrent(key, stamp) }
        }
        log("[media] ${att.file_id} <- network")
        return fetched
    }

    private enum class Stored { Yes, NetworkFailed, StoreFailed }

    private suspend fun downloadToStore(key: MediaKey, download: RangeDownload, sizeHint: Long?, stamp: Stamp): Stored {
        var network: Boolean? = null
        val written = safe("download", false) {
            store.writeStream(key) { append -> downloadInRanges(download, rangeBytes, sizeHint, append).also { network = it } }
        }
        return when {
            written && keepIfCurrent(key, stamp) -> Stored.Yes
            written -> Stored.StoreFailed // cleared while it downloaded: shown once from the network
            network == false -> Stored.NetworkFailed
            else -> Stored.StoreFailed
        }
    }

    /** A write that finished after a Clear (or a forget of its host) started must not resurrect it. */
    private suspend fun keepIfCurrent(key: MediaKey, stamp: Stamp): Boolean {
        if (stamp(key.host) == stamp) return true
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

    private data class Stamp(val clear: Long, val host: Long)

    private fun stamp(host: String): Stamp = synchronized(lock) { Stamp(clearGeneration, hostGenerations[host] ?: 0L) }

    private fun memKey(host: String, fileId: String) = "$host/$fileId"

    companion object {
        const val DEFAULT_MEMORY_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_MEMORY_ENTRY_BYTES: Long = 8L * 1024 * 1024
        const val DEFAULT_RANGE_BYTES: Long = 4L * 1024 * 1024
        private const val SEED_CHUNK_BYTES = 1024 * 1024

        /** The largest upload seeded where entries are not files: the browser reads it synchronously. */
        const val SEED_MAX_WITHOUT_FILES: Long = 8L * 1024 * 1024
    }
}
