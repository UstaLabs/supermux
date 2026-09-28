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

fun displayName(path: String): String = trimEnd(path).substringAfterLast('/').ifEmpty { "/" }
