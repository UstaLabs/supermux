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

import dev.supermux.fs.hostFileUri
import dev.supermux.fs.hostJoin

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

/** A directory and a workdir-relative path joined with exactly one separator: the directory's (`\` on Windows). */
fun joinPath(dir: String, rel: String): String = hostJoin(dir, rel)

/**
 * `file://` URI for an absolute host path: `file:///home/…`, `file:///C:/Users/…`, `file://server/share/…`. Each
 * name is percent-encoded as desktop's `java.net.URLEncoder` did (with `+` repaired to `%20`) and byte-by-byte as
 * UTF-8, by the one encoder in [hostFileUri].
 */
fun pathToUri(abs: String): String = hostFileUri(abs)

/** Directory URI for a workdir — always trailing-slash. */
// platform-ok: a file URI
fun dirUri(workdir: String): String = hostFileUri(workdir).let { if (it.endsWith('/')) it else "$it/" }
