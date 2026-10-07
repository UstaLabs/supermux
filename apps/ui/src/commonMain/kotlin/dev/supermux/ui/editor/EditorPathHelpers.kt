// Pure helpers the editor panes share: the markdown-preview gate and the LSP `file://` URI
// conversions. They used to live at the foot of desktop's `EditorPanel.kt` — the old composite
// editor, deleted with the shell that was its only caller — then in desktop's
// `EditorPathHelpers.kt`; Android carried a private twin at the foot of `EditorScreen.kt`. Both
// panes (desktop's Explorer / File / Diff, Android's `EditorPanel`) still use every one of them,
// so they live here now, in `:ui`, as the single copy.
//
// Public (not `internal`) because both app modules call them; `EditorPanelMarkdownTest` /
// `EditorPanelLspUriTest` in `:ui` jvmTest drive them directly — the pure/testable-seam
// discipline this module uses for web-view-adjacent logic.
package dev.supermux.ui.editor

// ─── Markdown-preview toggle (M4g-1) ───────────────────────────────────────

/** `.md` / `.markdown` → markdown preview eligible (parity EditorPane.swift:30-33). */
fun isMarkdownPath(path: String): Boolean =
    path.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }

/** Pure derivation of the preview toggle/overlay visibility from the active tab's path and
 *  [EditorState.previewMode] — extracted so it's unit-testable without hosting Compose (the panel
 *  itself just calls this at composition time; see the editor panel body). */
data class EditorPreviewGate(val showPreviewToggle: Boolean, val showPreview: Boolean)

/** M4g-2: the `&& !showDiff` clauses give full parity with Android's editor panel. Diff mode fully
 *  replaces the column (see the swap gate in the panel's body), so the preview toggle/overlay must
 *  never show alongside it. */
fun editorPreviewGate(activePath: String?, previewMode: Boolean, showDiff: Boolean = false): EditorPreviewGate {
    val activeIsMarkdown = activePath?.let(::isMarkdownPath) == true
    return EditorPreviewGate(
        showPreviewToggle = activeIsMarkdown && !showDiff,
        showPreview = previewMode && activeIsMarkdown && !showDiff,
    )
}

// ─── LSP file:// URI helpers (M4g-3) ──────────────────────────────────────────────────────────

/** Join a directory and a workdir-relative path with exactly one '/' between them. */
fun joinPath(dir: String, rel: String): String {
    val d = dir.removeSuffix("/")
    val r = rel.removePrefix("/")
    return "$d/$r"
}

/** Characters `java.net.URLEncoder` (and `android.net.Uri.encode`) leave untouched, minus the `/`
 *  separators [pathToUri] preserves itself. */
private const val UNRESERVED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-*_"

private const val HEX = "0123456789ABCDEF"

/**
 * `file://` URI for an absolute path, percent-encoding every character except the `/` separators
 * and [UNRESERVED] — a pure-Kotlin equivalent of desktop's `java.net.URLEncoder` (with `+` repaired
 * to `%20`) and Android's `android.net.Uri.encode(abs, "/")`, so the shared copy needs no JDK or
 * Android URI type. Non-ASCII is percent-encoded byte-by-byte as UTF-8, as both originals did.
 */
fun pathToUri(abs: String): String {
    val sb = StringBuilder("file://")
    for (byte in abs.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (byte >= 0 && (c == '/' || c in UNRESERVED)) {
            sb.append(c)
        } else {
            val v = byte.toInt() and 0xFF
            sb.append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
    }
    return sb.toString()
}

/** Directory URI for a workdir — always trailing-slash. */
fun dirUri(workdir: String): String = pathToUri(workdir.removeSuffix("/")) + "/"
