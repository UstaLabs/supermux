// Pure helpers the editor panes share: the markdown-preview gate and the LSP `file://` URI
// conversions. They used to live at the foot of EditorPanel.kt — the old composite editor, deleted
// with the shell that was its only caller — but EditorPanes.kt (the workspace's Explorer / File /
// Diff panes) still uses every one of them, so the code moves here rather than going with it.
//
// `internal` (not `private`) so EditorPanelMarkdownTest / EditorLspUriTest can drive them directly:
// the pure/testable-seam discipline this module uses for KCEF-adjacent logic.
package dev.supermux.desktop.editor

// ─── Markdown-preview toggle (M4g-1) ───────────────────────────────────────

/** `.md` / `.markdown` → markdown preview eligible (verbatim port of Android
 *  EditorScreen.kt:582-583). `internal` (not `private`) so [EditorPanelMarkdownTest] can drive it
 *  directly — the pure/testable-seam discipline this module uses for KCEF-adjacent logic. */
internal fun isMarkdownPath(path: String): Boolean =
    path.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }

/** Pure derivation of the preview toggle/overlay visibility from the active tab's path and
 *  [EditorState.previewMode] — extracted so it's unit-testable without hosting Compose (the panel
 *  itself just calls this at composition time; see EditorPanel body). */
internal data class EditorPreviewGate(val showPreviewToggle: Boolean, val showPreview: Boolean)

/** M4g-2: restores the `&& !showDiff` clauses Android EditorScreen.kt:177-178 has and M4g-1
 *  deliberately omitted (diff mode didn't exist yet on desktop) — full Android parity now that
 *  [EditorState.showDiff] exists. Diff mode fully replaces the column (see the swap gate in
 *  [EditorPanel]'s body), so the preview toggle/overlay must never show alongside it. */
internal fun editorPreviewGate(activePath: String?, previewMode: Boolean, showDiff: Boolean = false): EditorPreviewGate {
    val activeIsMarkdown = activePath?.let(::isMarkdownPath) == true
    return EditorPreviewGate(
        showPreviewToggle = activeIsMarkdown && !showDiff,
        showPreview = previewMode && activeIsMarkdown && !showDiff,
    )
}

// ─── LSP file:// URI helpers (M4g-3; port of Android EditorScreen.kt:595-603) ─────────────────

/** Join a directory and a workdir-relative path with exactly one '/' between them. */
internal fun joinPath(dir: String, rel: String): String {
    val d = dir.removeSuffix("/")
    val r = rel.removePrefix("/")
    return "$d/$r"
}

/**
 * `file://` URI for an absolute path, percent-encoding every path SEGMENT except the `/`
 * separators — the JVM equivalent of Android's `android.net.Uri.encode(abs, "/")`
 * (`EditorScreen.kt:601`). [java.net.URLEncoder] is form-encoding (encodes space as `+`, not
 * `%20`), so each segment is encoded individually and `+` is repaired to `%20` before rejoining.
 */
internal fun pathToUri(abs: String): String {
    val encoded = abs.split("/").joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
    return "file://$encoded"
}

/** Directory URI for a workdir — always trailing-slash (Android `EditorScreen.kt:603` parity). */
internal fun dirUri(workdir: String): String = pathToUri(workdir.removeSuffix("/")) + "/"
