package dev.supermux.ui

/** What one line of a [ToolDiffDoc] is: unchanged, added, removed, or a header between hunks/files. */
enum class DiffLineKind { CONTEXT, ADD, REMOVE, META }

/**
 * A unified diff as an editor document: the lines without their `+`/`-`/space prefix, in patch
 * order (so the file's grammar highlights them), and each line's [kinds] for the tints. Line i of
 * [text] is `kinds[i]`.
 */
data class ToolDiffDoc(val text: String, val kinds: List<DiffLineKind>)

/**
 * [diff] (an agent's edit: a real patch, the broker's synthesized `-old`/`+new` block, or Codex's
 * `update <path>` + patch per file) as a [ToolDiffDoc]. File headers (`diff --git`, `index`,
 * `---`/`+++` before a hunk) and `\ No newline at end of file` are dropped; so is each file's first
 * hunk header (the pane's header already names the file). Later hunk headers and any other unprefixed line
 * (a Codex per-file header) stay as [DiffLineKind.META].
 */
fun toolDiffDoc(diff: String): ToolDiffDoc {
    val out = StringBuilder()
    val kinds = ArrayList<DiffLineKind>()
    fun emit(line: String, kind: DiffLineKind) {
        if (kinds.isNotEmpty()) out.append('\n')
        out.append(line)
        kinds += kind
    }
    var inHunk = false
    // The file's first hunk header says nothing the pane's header (or a per-file header) does not.
    var fileHunks = 0
    for (raw in diff.trimEnd('\n', '\r').lineSequence()) {
        val line = raw.removeSuffix("\r")
        if (line.startsWith("@@")) {
            if (fileHunks++ > 0) emit(line, DiffLineKind.META)
            inHunk = true
            continue
        }
        // A removed line may start with "-- " (an SQL comment): headers only count outside a hunk.
        if (line.startsWith("diff --git ")) { inHunk = false; fileHunks = 0; continue }
        if (!inHunk && FILE_HEADERS.any(line::startsWith)) continue
        when {
            line.startsWith("\\") -> Unit
            line.startsWith("+") -> emit(line.substring(1), DiffLineKind.ADD)
            line.startsWith("-") -> emit(line.substring(1), DiffLineKind.REMOVE)
            line.startsWith(" ") -> emit(line.substring(1), DiffLineKind.CONTEXT)
            // A blank context line whose trailing space was stripped.
            line.isEmpty() -> emit("", DiffLineKind.CONTEXT)
            else -> { emit(line, DiffLineKind.META); inHunk = false; fileHunks = 0 }
        }
    }
    return ToolDiffDoc(out.toString(), kinds)
}

private val FILE_HEADERS = listOf(
    "--- ", "+++ ", "index ", "new file mode", "deleted file mode", "old mode", "new mode",
    "similarity index", "rename from", "rename to", "Binary files",
)
