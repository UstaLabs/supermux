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

    suspend fun write(key: MediaKey, bytes: ByteArray): Boolean

    /**
     * Stream an entry in. [fill] is handed an append function (the buffer may be reused by the
     * caller after it returns) and answers true when the content is complete; false — or a throw —
     * discards everything written so far.
     */
    suspend fun writeStream(key: MediaKey, fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean): Boolean

    suspend fun delete(key: MediaKey)

    suspend fun removeHost(host: String)

    suspend fun clear()

    suspend fun sizeBytes(): Long
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
