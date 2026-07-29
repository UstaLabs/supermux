package dev.supermux.ui

data class FilePathRef(val path: String, val line: Int? = null, val endLine: Int? = null)

/** One in-text match: half-open [start, end) char range + parsed ref + matched text. */
data class FilePathMatch(val start: Int, val end: Int, val ref: FilePathRef, val display: String)

/** Path body shared with linkification (relative, absolute, home-relative). Port of FILE_PATH_BODY.
 *
 *  Exposed so Apple clients can compile the SAME pattern with Foundation's regex engine — see
 *  the note on [findFilePathRefs]. Keep in sync with `src/web-app/src/lib/file-path-ref.ts`. */
const val FILE_PATH_BODY: String =
    """(?:\.{0,2}/)?(?:[\w@.-]+/)+[\w.-]+\.[\w]+|(?:/|~/)(?:[\w@.-]+/)+[\w.-]+\.[\w]+"""

private val FILE_PATH_REF_RE = Regex("""^($FILE_PATH_BODY)(?::(.*))?$""")

/** Line-suffix (`:12`, `:12-20`) matcher for [parseFilePathRef].
 *
 *  Hoisted because [findFilePathRefs] calls [parseFilePathRef] once per candidate match, and this
 *  was being compiled fresh on every call. Regex objects are immutable and safe to share. */
private val LINE_SUFFIX_RE = Regex("""^(\d+)(?:-(\d+))?$""")

/** Trailing `:12` / `:12-20` stripper for [stripFilePathRefSuffix] — same reason as above. */
private val TRAILING_LINE_SUFFIX_RE = Regex(""":(\d+)(?:-(\d+))?$""")

/** Path + optional line suffix, with word boundaries. Port of FILE_PATH_MATCH_RE. */
val FILE_PATH_MATCH_RE = Regex("""(?<!\w)($FILE_PATH_BODY)(?::\d+(?:-\d+)?|:[^\s<>"'\w]+)?(?!\w)""")

/** Same 34-entry set as web's FILE_EXTENSIONS (markdown.ts). */
private val FILE_EXTENSIONS = setOf(
    "ts", "tsx", "js", "jsx", "vue", "py", "json", "md", "css", "html",
    "yml", "yaml", "toml", "sql", "sh", "bash", "zsh", "go", "rs",
    "rb", "java", "kt", "swift", "c", "cpp", "h", "hpp", "txt",
    "env", "gitignore", "dockerfile", "xml", "svg", "lock",
)

fun hasKnownExtension(path: String): Boolean =
    FILE_EXTENSIONS.contains(path.substringAfterLast('.', "").lowercase())

/** Parse a whole path token (anchored). Returns null on a non-numeric or inverted suffix. */
fun parseFilePathRef(raw: String): FilePathRef? {
    val trimmed = raw.trim()
    val m = FILE_PATH_REF_RE.matchEntire(trimmed) ?: return null
    val path = m.groupValues[1]
    val suffix = m.groups[2]?.value ?: return FilePathRef(path)

    val lineMatch = LINE_SUFFIX_RE.matchEntire(suffix) ?: return null
    val line = lineMatch.groupValues[1].toInt()
    val endLine = lineMatch.groups[2]?.value?.toInt()
    if (endLine != null && line > endLine) return null
    return FilePathRef(path, line, endLine)
}

fun stripFilePathRefSuffix(raw: String): String {
    val ref = parseFilePathRef(raw.trim())
    if (ref != null) return ref.path
    return raw.trim().replace(TRAILING_LINE_SUFFIX_RE, "")
}

fun formatFilePathRef(ref: FilePathRef): String = when {
    ref.line == null -> ref.path
    ref.endLine != null -> "${ref.path}:${ref.line}-${ref.endLine}"
    else -> "${ref.path}:${ref.line}"
}

/** All known-extension path refs in a plain text run (ports linkifyFilePaths minus HTML).
 *
 *  ⚠️ **Do not call this from Apple clients on a UI path.** Kotlin/Native ships a pure-Kotlin regex
 *  engine that is ~1000x slower than Foundation's C-backed one. Measured, identical pattern and
 *  input, same 64 matches:
 *
 *      700 chars, 0 matches:   Kotlin/Native  25.9 ms   vs  Foundation  0.16 ms
 *      4992 chars, 64 matches: Kotlin/Native 635.9 ms   vs  Foundation  0.60 ms
 *
 *  Linkifying one ordinary agent message therefore cost most of a second on macOS/iOS, on the main
 *  thread, which was the dominant cost of opening a session. Android/JVM is fine (fast JDK engine),
 *  which is why only the Apple clients felt it. Apple clients should compile [FILE_PATH_BODY] with
 *  `NSRegularExpression` instead; this implementation stays for Android/JVM. */
fun findFilePathRefs(text: String): List<FilePathMatch> =
    FILE_PATH_MATCH_RE.findAll(text).mapNotNull { m ->
        val ref = parseFilePathRef(m.value) ?: return@mapNotNull null
        if (!hasKnownExtension(ref.path)) return@mapNotNull null
        FilePathMatch(m.range.first, m.range.last + 1, ref, m.value)
    }.toList()
