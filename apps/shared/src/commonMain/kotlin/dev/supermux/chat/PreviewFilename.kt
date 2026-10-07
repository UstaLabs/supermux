package dev.supermux.chat

/**
 * A filename whose extension lets the host's viewer identify the file's type.
 *
 * Every OS preview path — Quick Look, the Android chooser, a desktop file manager — keys off the
 * suffix, so an attachment saved under a name with no extension (or no name at all) opens as a
 * generic blob. This keeps an existing extension when there is one, otherwise derives one from the
 * MIME type, preferring a real mapping over a naive subtype split: `text/plain` is `.txt`, never
 * `.plain`.
 *
 * @param name the attachment's original filename, if any.
 * @param mime the attachment's MIME type, if any — a raw `Content-Type` value is fine.
 * @param fallbackBase base name used when [name] is null/blank.
 * @param defaultExt extension used when [mime] yields none (null → no extension at all).
 */
fun previewFilename(
    name: String?,
    mime: String?,
    fallbackBase: String = "file",
    defaultExt: String? = null,
): String {
    // Anything with a dot already names its own type — including a dotfile like `.gitignore`,
    // where appending an extension would rename the file into something else entirely.
    if (name != null && name.isNotEmpty() && name.contains(".")) return name
    val base = name?.ifEmpty { null } ?: fallbackBase
    val ext = mimeFileExtension(mime) ?: defaultExt
    return if (ext != null) "$base.$ext" else base
}

/**
 * The preferred filename extension for a MIME type, falling back to the subtype.
 *
 * Strips any `; parameters` (`text/plain; charset=utf-8`) and lowercases first, since an
 * attachment's MIME arrives as whatever the sender's `Content-Type` header said.
 */
private fun mimeFileExtension(mime: String?): String? {
    if (mime == null) return null
    val bare = mime.substringBefore(';').trim().lowercase()
    if (bare.isEmpty()) return null
    MIME_EXTENSION[bare]?.let { return it }
    return bare.substringAfterLast('/', "").ifEmpty { null }
}

/**
 * MIME → extension for the types where the subtype is NOT the extension. Everything else falls
 * through to the subtype (`image/png` → `png`), so this table stays deliberately small: it only
 * has to carry the cases a split would get wrong.
 */
private val MIME_EXTENSION: Map<String, String> = mapOf(
    "text/plain" to "txt",
    "text/markdown" to "md",
    "text/x-markdown" to "md",
    "text/javascript" to "js",
    "image/jpeg" to "jpg",
    "image/svg+xml" to "svg",
    "video/quicktime" to "mov",
    "video/x-matroska" to "mkv",
    "video/x-msvideo" to "avi",
    "audio/mpeg" to "mp3",
    "audio/mp4" to "m4a",
    "audio/x-wav" to "wav",
    "application/octet-stream" to "bin",
    "application/x-tar" to "tar",
    "application/gzip" to "gz",
    "application/x-yaml" to "yaml",
)
