package dev.supermux.media

import dev.supermux.proto.Attachment
import dev.supermux.work.DownloadedFile

/**
 * How the chat reads an attachment's content. `HostStore.media` answers through the media cache;
 * [fromBytes] adapts a plain loader for surfaces that show something else in the same widgets (a
 * markdown video on the host, an editor preview).
 */
interface AttachmentMedia {
    /** Bytes already in memory, without suspending — a row coming back on screen paints at once. */
    fun peek(att: Attachment): ByteArray? = null

    /** The attachment's bytes, or null. [refresh] skips every cached copy (one that would not decode). */
    suspend fun bytes(att: Attachment, refresh: Boolean = false): ByteArray?

    /** A file URI the platform's media player opens, or null — the caller then stages [bytes]. */
    suspend fun playableUri(att: Attachment): String? = null

    /**
     * [att] downloaded to a real file (or, on the web, a browser Blob) for the DownloadCenter; null where this
     * media has no download (a preview's in-memory bytes) — the caller then keeps today's bytes path.
     */
    suspend fun download(att: Attachment, progress: (received: Long, total: Long?) -> Unit): DownloadedFile? = null

    /** Stop a [download] under way; what arrived is kept for a retry. */
    fun cancelDownload(att: Attachment) {}

    /** [att]'s identity across every chat on its host — `att:<host>/<fileId>` — or null without a download. */
    fun downloadKey(att: Attachment): String? = null

    companion object {
        val None: AttachmentMedia = fromBytes { null }

        fun fromBytes(load: suspend (fileId: String) -> ByteArray?): AttachmentMedia = object : AttachmentMedia {
            override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? = load(att.file_id)
        }
    }
}
