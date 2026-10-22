package dev.supermux.media

import dev.supermux.chat.mimeFileExtension

/**
 * Where one cached attachment lives in a [MediaStore]: `<host>/<fileId>.<ext>` under its root.
 *
 * Built only through [of], which refuses anything that could name a path outside that root: the
 * file id must be a broker id (`randomBytes(16)` as hex), the host a paired-host record id, and the
 * extension a short lowercase token. A null key is not an error — the file is still fetched and
 * shown, it just stays in memory.
 */
class MediaKey private constructor(val host: String, val fileId: String, val ext: String) {
    /** The entry's path under the store root, `/`-separated. */
    val relativePath: String get() = "$host/$fileId.$ext"

    override fun equals(other: Any?): Boolean =
        other is MediaKey && other.host == host && other.fileId == fileId && other.ext == ext

    override fun hashCode(): Int = (host.hashCode() * 31 + fileId.hashCode()) * 31 + ext.hashCode()

    override fun toString(): String = relativePath

    companion object {
        private val FILE_ID = Regex("^[0-9a-f]{32}$")
        private val HOST = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** The key for [fileId] on [host], or null when either could name something outside the root. */
        fun of(host: String, fileId: String, name: String?, mime: String?): MediaKey? {
            if (!FILE_ID.matches(fileId) || !isValidHost(host)) return null
            return MediaKey(host, fileId, mediaExtension(name, mime))
        }

        /** Whether [host] may name a folder — the rule [of] applies. */
        fun isValidHost(host: String): Boolean = HOST.matches(host)
    }
}

private val EXTENSION = Regex("^[a-z0-9]{1,8}$")

/**
 * The extension a cached file is stored under: [name]'s own suffix when it is a short plain token,
 * else the one [mime] implies, else `bin`. The native video backends pick their demuxer from it, so
 * a real one matters — and it can never carry a separator or a dot.
 */
fun mediaExtension(name: String?, mime: String?): String {
    val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\')
    val fromName = leaf?.takeIf { '.' in it }?.substringAfterLast('.')?.lowercase()
    if (fromName != null && EXTENSION.matches(fromName)) return fromName
    val fromMime = mimeFileExtension(mime)?.lowercase()
    if (fromMime != null && EXTENSION.matches(fromMime)) return fromMime
    return "bin"
}
