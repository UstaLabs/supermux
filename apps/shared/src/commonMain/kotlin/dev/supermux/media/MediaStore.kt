package dev.supermux.media

/**
 * Where cached attachments are kept between runs. Implementations move bytes only — what may be
 * stored, when, and what a failure means all live in [MediaCache], which also catches anything a
 * store throws. The one thing a store must guarantee itself: a write that fails or is abandoned
 * leaves nothing behind, so a reader sees a whole entry or none.
 */
interface MediaStore {
    /** Entries are real files a media player can open, so [uri] can answer. False on the web. */
    val hasFiles: Boolean

    /** False when nothing survives the process (a browser without Cache Storage): memory only. */
    val persistent: Boolean

    suspend fun read(key: MediaKey): ByteArray?

    suspend fun exists(key: MediaKey): Boolean

    /** A URI the platform's media player opens, or null when there is no such entry. */
    suspend fun uri(key: MediaKey): String?

    /** The stored entry's absolute path, or null when there is none or entries are not files. */
    suspend fun path(key: MediaKey): String? = null

    /** How many bytes the stored entry holds, or null when there is none or entries are not files. */
    suspend fun size(key: MediaKey): Long? = null

    suspend fun write(key: MediaKey, bytes: ByteArray): Boolean

    /**
     * Stream an entry in. [fill] is handed an append function (the buffer may be reused by the
     * caller after it returns) and answers true when the content is complete; false — or a throw —
     * discards everything written so far.
     */
    suspend fun writeStream(key: MediaKey, fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean): Boolean

    /** Start, or continue after a [ResumableWrite.retain], a write of [key]. Null where entries are not files. */
    suspend fun openResumable(key: MediaKey): ResumableWrite? = null

    suspend fun delete(key: MediaKey)

    suspend fun removeHost(host: String)

    suspend fun clear()

    suspend fun sizeBytes(): Long
}

/**
 * One write of a store entry that can stop and continue later (#95's Retry resumes a download where it
 * stopped). Exactly one of [commit], [retain] or [abandon] ends it; later calls do nothing. What it holds is
 * never an entry: a reader sees a whole file or none, as with [MediaStore.writeStream].
 */
interface ResumableWrite {
    /** Bytes kept from an earlier attempt, which this write continues after. */
    val offset: Long

    /** Add the first [n] bytes of [buf]; the caller may reuse the buffer once this returns. */
    suspend fun append(buf: ByteArray, n: Int)

    /** Move what was written into place as the entry. False when it could not be (nothing is left behind). */
    suspend fun commit(): Boolean

    /** Keep what was written for the next [MediaStore.openResumable] of the same key. */
    suspend fun retain()

    /** Delete what was written. */
    suspend fun abandon()
}

/** A store that keeps nothing — the memory tier is all there is. */
object NoMediaStore : MediaStore {
    override val hasFiles: Boolean get() = false
    override val persistent: Boolean get() = false

    override suspend fun read(key: MediaKey): ByteArray? = null

    override suspend fun exists(key: MediaKey): Boolean = false

    override suspend fun uri(key: MediaKey): String? = null

    override suspend fun write(key: MediaKey, bytes: ByteArray): Boolean = false

    override suspend fun writeStream(
        key: MediaKey,
        fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean,
    ): Boolean = false

    override suspend fun delete(key: MediaKey) = Unit

    override suspend fun removeHost(host: String) = Unit

    override suspend fun clear() = Unit

    override suspend fun sizeBytes(): Long = 0
}
