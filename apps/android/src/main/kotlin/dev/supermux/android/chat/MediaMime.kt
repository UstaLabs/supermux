package dev.supermux.android.chat

/**
 * Whether a clipboard/drag/paste MIME type is an attachable inline-media type (image OR video).
 * Video-upload Phase 1 widened this from image-only; the actual upload stays generic via
 * stageFromUri, which reads each URI's real MIME and lets the broker infer the "video" kind.
 * Null/blank → false so text and arbitrary binary content falls through to normal handling.
 */
fun isAttachableMediaMime(mime: String?): Boolean =
    mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))
