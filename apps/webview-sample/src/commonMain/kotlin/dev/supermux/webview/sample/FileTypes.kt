package dev.supermux.webview.sample

/**
 * What a document picker that takes MIME types only (Android's `OpenMultipleDocuments`) should offer for a file input's
 * [accept] list, whose tokens are MIME types (`image/png`, or a type with any subtype) or extensions (`.pdf`). An
 * extension becomes its MIME type ([mimeOfExtension], given it without the dot, lower case). With nothing asked, or an
 * extension nobody knows, anything may be picked ([ANY_TYPE]), and the page checks what it gets: better than a picker
 * that offers nothing.
 */
fun pickerTypes(accept: List<String>, mimeOfExtension: (String) -> String?): List<String> {
    val types = LinkedHashSet<String>()
    for (raw in accept) {
        val token = raw.trim().lowercase()
        when {
            token.isEmpty() -> Unit
            token.startsWith(".") -> types += mimeOfExtension(token.drop(1)) ?: return listOf(ANY_TYPE)
            else -> types += token
        }
    }
    return types.toList().ifEmpty { listOf(ANY_TYPE) }
}

/** Any type at all, as a picker takes it. */
private const val ANY_TYPE = "*/*"
