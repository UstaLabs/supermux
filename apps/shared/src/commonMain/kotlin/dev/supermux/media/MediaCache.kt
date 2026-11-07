package dev.supermux.media

import dev.supermux.net.ChunkSource
import dev.supermux.proto.Attachment
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a downloaded attachment landed: an absolute [path] and how many bytes it holds. */
class StoredFile(val path: String, val size: Long)

/** One file download's progress, shared by everyone waiting on it. [total] is null until the server says. */
data class TransferProgress(val received: Long, val total: Long?)

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
    private val fileSlots = HashMap<String, FileSlot>()

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
     * download fails or is cancelled; the caller then stages bytes the old way. The download is the one
     * [file] makes, so a clip played in place and the same file saved are a single transfer.
     */
    suspend fun playableUri(host: String, att: Attachment, download: RangeDownload): String? {
        if (!store.hasFiles) return null
        val key = MediaKey.of(host, att.file_id, att.name, att.mime) ?: return null
        safe("uri", null) { store.uri(key) }?.let {
            log("[media] ${att.file_id} <- disk (file)")
            return it
        }
        if (fileSlot(host, key, att, download).result.await() == null) return null
        return safe("uri", null) { store.uri(key) }
    }

    /**
     * [att] as a stored FILE — downloaded first when needed, resumed from a kept partial, never read into
     * memory (#95: a big file must not run a phone out of memory). Null where entries are not files (the web),
     * for a key that cannot be stored, or when the download fails or is cancelled ([cancelFile]).
     *
     * [onProgress] follows the download from wherever it is when this call comes in — one a clip started
     * first included — to its end.
     */
    suspend fun file(
        host: String,
        att: Attachment,
        download: RangeDownload,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
    ): StoredFile? {
        if (!store.hasFiles) return null
        val key = MediaKey.of(host, att.file_id, att.name, att.mime) ?: return null
        stored(key)?.let {
            log("[media] ${att.file_id} <- disk (file)")
            return it
        }
        return follow(fileSlot(host, key, att, download), onProgress)
    }

    /**
     * A file that is not an attachment — a host file (#95: Files "Save and open…"), under the synthetic id its [key]
     * carries — kept in the store like one, so it is not fetched twice and Storage › Clear covers it. Answered at once
     * when already stored. Else [fill] writes it and answers whether what it appended is whole; nothing is kept when
     * it is not, nor when a Clear (or a forget of its host) came meanwhile. Null where entries are not files.
     *
     * What [fill] or the store throws reaches the caller: here the store is where the file goes, so a failure is
     * that download's own, not a cache problem the network could stand in for.
     */
    internal suspend fun storeFile(key: MediaKey, fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean): StoredFile? {
        if (!store.hasFiles) return null
        stored(key)?.let {
            log("[media] ${key.fileId} <- disk (file)")
            return it
        }
        val stamp = stamp(key.host)
        if (!store.writeStream(key, fill) || !keepIfCurrent(key, stamp)) return null
        log("[media] ${key.fileId} <- network (file)")
        return stored(key)
    }

    /** The user's Cancel: stop [fileId]'s file download, keeping what arrived for a retry. */
    fun cancelFile(host: String, fileId: String) {
        val transfer = synchronized(lock) {
            fileSlots[fileSlotName(host, fileId)]?.let { slot ->
                // A Cancel can come before the slot has begun its transfer: then it never begins.
                slot.cancelled = true
                slot.transfer
            }
        }
        transfer?.cancel()
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

    private suspend fun downloadToStore(
        key: MediaKey,
        download: RangeDownload,
        sizeHint: Long?,
        stamp: Stamp,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
    ): Stored {
        var network: Boolean? = null
        val written = safe("download", false) {
            store.writeStream(key) { append ->
                (downloadInRanges(download, rangeBytes, sizeHint, append, onProgress = onProgress) == RangeOutcome.Complete)
                    .also { network = it }
            }
        }
        return when {
            written && keepIfCurrent(key, stamp) -> Stored.Yes
            written -> Stored.StoreFailed // cleared while it downloaded: shown once from the network
            network == false -> Stored.NetworkFailed
            else -> Stored.StoreFailed
        }
    }

    /**
     * One file download in flight, shared by [file] and [playableUri]: everyone waits on [result] and may
     * follow [progress]. [transfer] is the download itself, a child of [result] that [cancelFile] can stop while
     * [result] still answers — null, so no waiter is failed by someone else's Cancel.
     */
    private class FileSlot(val progress: MutableStateFlow<TransferProgress>) {
        lateinit var result: Deferred<StoredFile?>
        var transfer: Job? = null
        var cancelled = false
    }

    private fun fileSlot(host: String, key: MediaKey, att: Attachment, download: RangeDownload): FileSlot {
        val name = fileSlotName(host, att.file_id)
        return synchronized(lock) {
            fileSlots[name] ?: FileSlot(MutableStateFlow(TransferProgress(0, att.size))).also { slot ->
                fileSlots[name] = slot
                slot.result = scope.async { runFileSlot(slot, key, download, att.size) }
                // The progress goes with the slot: the next download of this file starts a fresh one.
                slot.result.invokeOnCompletion { synchronized(lock) { if (fileSlots[name] === slot) fileSlots.remove(name) } }
            }
        }
    }

    /**
     * The slot's body. The cancel of its transfer is caught here, while the slot itself is still active, so
     * every waiter hears null — never a CancellationException that a `runCatching` caller (the inline video)
     * would misread.
     */
    private suspend fun runFileSlot(slot: FileSlot, key: MediaKey, download: RangeDownload, sizeHint: Long?): StoredFile? =
        coroutineScope {
            val stamp = stamp(key.host)
            val transfer = async(start = CoroutineStart.LAZY) { downloadFile(key, download, sizeHint, stamp, slot.progress) }
            val go = synchronized(lock) {
                slot.transfer = transfer
                !slot.cancelled
            }
            if (go) transfer.start() else transfer.cancel()
            val stored = try {
                transfer.await()
            } catch (c: CancellationException) {
                ensureActive()
                log("[media] ${key.fileId}: download cancelled")
                return@coroutineScope null
            }
            log(if (stored != null) "[media] ${key.fileId} <- network (file)" else "[media] ${key.fileId}: download failed")
            stored
        }

    /**
     * Download [key] into the store through a resumable write, continuing what an earlier attempt kept, and
     * say where it landed. Every write it opens is ended on every path: a whole file is committed (and dropped
     * again by [keepIfCurrent] when a Clear came meanwhile); a failure or a cancel keeps what arrived for a retry
     * ([keepForRetry]); a kept part longer than the file cannot be its prefix, so it is thrown away and the
     * download starts once more from nothing.
     */
    private suspend fun downloadFile(
        key: MediaKey,
        download: RangeDownload,
        sizeHint: Long?,
        stamp: Stamp,
        progress: MutableStateFlow<TransferProgress>,
    ): StoredFile? {
        repeat(2) {
            val write = safe("open", null) { store.openResumable(key) }
                ?: return wholeFile(key, download, sizeHint, stamp, progress)
            var total: Long? = null
            try {
                val outcome = downloadInRanges(
                    download, rangeBytes, sizeHint, { buf, n -> write.append(buf, n) }, startAt = write.offset,
                ) { received, size ->
                    if (size != null) total = size
                    progress.advance(received, size)
                }
                when (outcome) {
                    RangeOutcome.Complete -> return withContext(NonCancellable) {
                        if (write.commit() && keepIfCurrent(key, stamp)) stored(key, total) else null
                    }
                    RangeOutcome.Failed -> {
                        keepForRetry(write, key, stamp)
                        return null
                    }
                    RangeOutcome.Overlong -> {
                        log("[media] ${key.fileId}: the kept part runs past the file's end: thrown away")
                        write.abandon()
                        progress.value = TransferProgress(0, total)
                    }
                }
            } catch (c: CancellationException) {
                keepForRetry(write, key, stamp)
                throw c
            } catch (e: Throwable) {
                log("[media] ${key.fileId} download failed: $e")
                keepForRetry(write, key, stamp)
                return null
            }
        }
        return null
    }

    /** A store whose entries are files but that cannot keep a partial: the whole download in one go, as [bytes] does. */
    private suspend fun wholeFile(
        key: MediaKey,
        download: RangeDownload,
        sizeHint: Long?,
        stamp: Stamp,
        progress: MutableStateFlow<TransferProgress>,
    ): StoredFile? {
        val outcome = downloadToStore(key, download, sizeHint, stamp) { received, total -> progress.advance(received, total) }
        return if (outcome == Stored.Yes) stored(key) else null
    }

    /**
     * End a write that did not finish: keep what arrived for a retry — unless a Clear or a forget of its host
     * came since [stamp], which a kept part must not outlive either. Out of reach of cancellation: a cancelled
     * download is exactly the one whose bytes still have to go somewhere.
     */
    private suspend fun keepForRetry(write: ResumableWrite, key: MediaKey, stamp: Stamp) {
        withContext(NonCancellable) {
            safe("keep", Unit) { if (stamp(key.host) == stamp) write.retain() else write.abandon() }
        }
    }

    /** [key]'s entry as a [StoredFile]: [size] when the caller knows it, else what the store counts. Null when there is none. */
    private suspend fun stored(key: MediaKey, size: Long? = null): StoredFile? {
        val path = safe("path", null) { store.path(key) } ?: return null
        return StoredFile(path, size ?: safe("size", null) { store.size(key) } ?: return null)
    }

    /**
     * Wait for [slot], passing its progress on as it moves — and its last value once more at the end: the
     * slot can reach that value just before it ends, while the watcher is still on its way to it.
     */
    private suspend fun follow(slot: FileSlot, onProgress: (received: Long, total: Long?) -> Unit): StoredFile? {
        var heard: TransferProgress? = null
        val hear = { p: TransferProgress ->
            if (p != heard) {
                heard = p
                onProgress(p.received, p.total)
            }
        }
        val result = coroutineScope {
            // Undispatched: a caller that joins hears at once where the transfer already is.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) { slot.progress.collect { hear(it) } }
            slot.result.await().also { watcher.cancel() }
        }
        hear(slot.progress.value)
        return result
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

    private fun fileSlotName(host: String, fileId: String) = memKey(host, fileId) + "#file"

    companion object {
        const val DEFAULT_MEMORY_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_MEMORY_ENTRY_BYTES: Long = 8L * 1024 * 1024
        const val DEFAULT_RANGE_BYTES: Long = 4L * 1024 * 1024
        private const val SEED_CHUNK_BYTES = 1024 * 1024

        /** The largest upload seeded where entries are not files: the browser reads it synchronously. */
        const val SEED_MAX_WITHOUT_FILES: Long = 8L * 1024 * 1024
    }
}

/**
 * Move a file download's progress on. A null [total] keeps the size already known (the attachment's own, or
 * what the server stated). A kept part longer than the file reports more bytes than the file holds just
 * before it is thrown away: no caller ever sees that.
 */
private fun MutableStateFlow<TransferProgress>.advance(received: Long, total: Long?) = update { now ->
    val size = total ?: now.total
    if (size != null && received > size) now else TransferProgress(received, size)
}
