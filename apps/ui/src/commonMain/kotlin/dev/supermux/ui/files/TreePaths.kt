// Absolute-path helpers for the Files tree. The tree speaks the host's own paths ("/home/u/p/src/a.kt",
// "C:\Users\u\p\src\a.kt", by the rules in dev.supermux.fs.HostPaths); everything handed to the workdir-relative
// editor code goes through [relativeToWorkdir].
package dev.supermux.ui.files

import dev.supermux.fs.hostIsInside
import dev.supermux.fs.hostJoin
import dev.supermux.fs.hostParent
import dev.supermux.fs.hostRelativePath
import dev.supermux.fs.hostRoot
import dev.supermux.fs.isDriveSegment
import dev.supermux.fs.pathLeaf
import dev.supermux.fs.sameHostPath

/** [p] without a trailing separator, but a root as it is: `/`, and `C:\` (`C:` alone is the drive's current folder). */
internal fun trimEnd(p: String): String {
    if (p.startsWith('/')) return p.trimEnd('/').ifEmpty { "/" }
    val t = p.trimEnd('/', '\\')
    return if (t.length == 2 && isDriveSegment(t) && p.length > 2) p.substring(0, 3) else t
}

fun parentOf(path: String): String? = hostParent(path)

fun childOf(dir: String, name: String): String = hostJoin(dir, name)

fun isWithin(root: String, path: String): Boolean = hostIsInside(path, root)

/** Folders from [root] down to [path]'s parent (inclusive of root), or empty if outside root. */
fun ancestorsWithin(root: String, path: String): List<String> {
    if (!isWithin(root, path) || sameHostPath(path, root)) return emptyList()
    val out = ArrayList<String>()
    var cur = parentOf(path)
    while (cur != null && isWithin(root, cur)) {
        out.add(0, cur)
        if (sameHostPath(cur, root)) break
        cur = parentOf(cur)
    }
    return out
}

/**
 * Workdir-relative form of [path] ("." for the workdir itself), or null when outside it — by the
 * host's own path rule ([hostRelativePath]), so a Windows workdir spelled `c:\users\me\app` still
 * owns the `C:\Users\me\app/src/a.kt` the host's search answers with.
 */
fun relativeToWorkdir(workdir: String, path: String): String? =
    hostRelativePath(workdir, path)?.ifEmpty { "." }

/**
 * The editor's key for the absolute [abs]: workdir-relative inside [workdir], [abs] itself outside
 * it (see [dev.supermux.ui.toEditorPath]). A key starting with `/` is always absolute.
 */
fun editorPathFor(workdir: String, abs: String): String =
    relativeToWorkdir(workdir, abs)?.takeIf { it != "." && it.isNotEmpty() } ?: trimEnd(abs)

/** The absolute path of the editor key [path]: an absolute key as it is, a relative one under [workdir]. */
fun editorAbsolutePath(workdir: String, path: String): String =
    if (dev.supermux.ui.isAbsoluteEditorPath(path)) trimEnd(path) else absoluteInWorkdir(workdir, path)

/** The name the tree shows for [path]: its last segment, or a root (`/`, `C:\`) as it is. */
fun displayName(path: String): String {
    val root = hostRoot(path)
    if (root != null && hostParent(path) == null) return root
    return pathLeaf(path).ifEmpty { "/" }
}

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
    if (hostParent(from) == null || (to != null && sameHostPath(from, to))) return emptyList()
    val out = ArrayList<MovedOpenPath>()
    val seen = HashSet<String>()
    for (rel in open) {
        if (!seen.add(rel)) continue
        if (!dev.supermux.ui.isAbsoluteEditorPath(rel)) {
            // platform-ok: an open editor key, workdir-relative, always /
            val segs = rel.split('/').filter { it.isNotEmpty() }
            if (segs.isEmpty() || segs == listOf(".")) continue
        }
        val abs = editorAbsolutePath(workdir, rel)
        val inside = hostRelativePath(from, abs) ?: continue
        val moved = to?.let { if (inside.isEmpty()) it else hostJoin(it, inside) }
        out += MovedOpenPath(rel, moved?.let { editorPathFor(workdir, it) })
    }
    return out
}
