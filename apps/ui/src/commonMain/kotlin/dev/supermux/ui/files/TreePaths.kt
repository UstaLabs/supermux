// Absolute-path helpers for the Files tree. The tree speaks host paths ("/home/u/p/src/a.kt");
// everything handed to the workdir-relative editor code goes through [relativeToWorkdir].
package dev.supermux.ui.files

private fun trimEnd(p: String): String = if (p.length > 1) p.trimEnd('/') else p

fun parentOf(path: String): String? {
    val p = trimEnd(path)
    if (p == "/") return null
    val i = p.lastIndexOf('/')
    return if (i <= 0) "/" else p.substring(0, i)
}

fun childOf(dir: String, name: String): String = if (trimEnd(dir) == "/") "/$name" else "${trimEnd(dir)}/$name"

fun isWithin(root: String, path: String): Boolean {
    val r = trimEnd(root)
    val p = trimEnd(path)
    return r == "/" || p == r || p.startsWith("$r/")
}

/** Folders from [root] down to [path]'s parent (inclusive of root), or empty if outside root. */
fun ancestorsWithin(root: String, path: String): List<String> {
    val r = trimEnd(root)
    if (!isWithin(r, path) || trimEnd(path) == r) return emptyList()
    val out = ArrayList<String>()
    var cur = parentOf(path)
    while (cur != null && isWithin(r, cur)) {
        out.add(0, cur)
        if (cur == r) break
        cur = parentOf(cur)
    }
    return out
}

/** Workdir-relative form of [path] ("." for the workdir itself), or null when outside it. */
fun relativeToWorkdir(workdir: String, path: String): String? {
    val w = trimEnd(workdir)
    val p = trimEnd(path)
    return when {
        p == w -> "."
        w == "/" -> p.removePrefix("/")
        p.startsWith("$w/") -> p.substring(w.length + 1)
        else -> null
    }
}

/**
 * The editor's key for the absolute [abs]: workdir-relative inside [workdir], [abs] itself outside
 * it (see [dev.supermux.ui.toEditorPath]). A key starting with `/` is always absolute.
 */
fun editorPathFor(workdir: String, abs: String): String =
    relativeToWorkdir(workdir, abs)?.takeIf { it != "." && it.isNotEmpty() } ?: trimEnd(abs)

/** The absolute path of the editor key [path]: an absolute key as it is, a relative one under [workdir]. */
fun editorAbsolutePath(workdir: String, path: String): String =
    if (dev.supermux.ui.isAbsoluteEditorPath(path)) trimEnd(path) else absoluteInWorkdir(workdir, path)

fun displayName(path: String): String = trimEnd(path).substringAfterLast('/').ifEmpty { "/" }

/**
 * One open document hit by a rename/delete in the tree: its editor key as the caller gave it
 * ([oldPath]) and where it lives now ([newPath], an editor key — workdir-relative, or absolute once
 * it moved out of the workdir; null = deleted).
 */
data class MovedOpenPath(val oldPath: String, val newPath: String?)

/**
 * Which of the [open] editor keys (workdir-relative, or absolute for files outside [workdir]) a move
 * of the tree entry [oldAbs] → [newAbs] touches ([newAbs] null = deleted): the entry itself and, for
 * a folder, everything under it — never a sibling that merely shares a name prefix (`src` vs
 * `srcx`). Order follows [open]; duplicates collapse. A no-op rename or `/` itself touch nothing.
 */
fun affectedOpenPaths(workdir: String, oldAbs: String, newAbs: String?, open: Collection<String>): List<MovedOpenPath> {
    val from = trimEnd(oldAbs)
    val to = newAbs?.let(::trimEnd)
    if (from == "/" || from == to) return emptyList()
    val out = ArrayList<MovedOpenPath>()
    val seen = HashSet<String>()
    for (rel in open) {
        if (!seen.add(rel)) continue
        if (!dev.supermux.ui.isAbsoluteEditorPath(rel)) {
            val segs = rel.split('/').filter { it.isNotEmpty() }
            if (segs.isEmpty() || segs == listOf(".")) continue
        }
        val abs = editorAbsolutePath(workdir, rel)
        if (!isWithin(from, abs)) continue
        val moved = to?.let { it + abs.substring(from.length) }
        out += MovedOpenPath(rel, moved?.let { editorPathFor(workdir, it) })
    }
    return out
}
