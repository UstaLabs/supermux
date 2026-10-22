package dev.supermux.media

import dev.supermux.proto.Attachment

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

    companion object {
        val None: AttachmentMedia = fromBytes { null }

        fun fromBytes(load: suspend (fileId: String) -> ByteArray?): AttachmentMedia = object : AttachmentMedia {
            override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? = load(att.file_id)
        }
    }
}
