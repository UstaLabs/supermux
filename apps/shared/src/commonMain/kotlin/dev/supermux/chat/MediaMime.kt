package dev.supermux.chat

/**
 * Whether a clipboard/drag/paste MIME type is an attachable inline-media type (image OR video).
 * Video-upload Phase 1 widened this from image-only; the actual upload stays generic via the
 * platform's URI staging, which reads each URI's real MIME and lets the broker infer the "video"
 * kind. Null/blank → false so text and arbitrary binary content falls through to normal handling.
 */
fun isAttachableMediaMime(mime: String?): Boolean =
    mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))

/**
 * The pure, host-independent half of `Platform.files.probeMime`: a MIME guess from a file NAME
 * alone, with no I/O and no platform tables. Null when the extension is unknown — the actuals ask
 * the OS first (`Files.probeContentType` / `MimeTypeMap`) and only fall back here, so this table
 * exists to keep the answer identical on a host whose OS knows nothing (a bare JVM, iOS later).
 */
fun mimeForFileName(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty() || ext == name.lowercase()) return null
    return EXTENSION_MIME[ext]
}

/** Extension → MIME for the types chat attachments actually carry. Deliberately small. */
private val EXTENSION_MIME: Map<String, String> = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "svg" to "image/svg+xml",
    "mp4" to "video/mp4",
    "m4v" to "video/mp4",
    "mov" to "video/quicktime",
    "webm" to "video/webm",
    "mkv" to "video/x-matroska",
    "avi" to "video/x-msvideo",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg",
    "opus" to "audio/opus",
    "flac" to "audio/flac",
    "pdf" to "application/pdf",
    "json" to "application/json",
    "zip" to "application/zip",
    "txt" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "html" to "text/html",
)
