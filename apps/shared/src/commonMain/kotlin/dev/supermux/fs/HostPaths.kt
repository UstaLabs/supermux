// Where one path on the broker's host stands relative to another, decided in ONE place for every
// client feature. A POSIX host's paths are case-sensitive and use `/`. A Windows host's take either
// separator, and its file system names a folder whatever its letter case: `c:\users\me\app` is the
// host's own `C:\Users\me\app`. Lexical only: nothing here touches a file system.
package dev.supermux.fs

/** A path's segments on either separator: `/a/b` and `C:\a\b` alike. A Windows drive (`C:`) is the first. */
fun hostPathSegments(path: String): List<String> = path.split('/', '\\').filter { it.isNotEmpty() }

/** `C:`, the first segment of a path on a Windows drive. */
fun isDriveSegment(segment: String): Boolean = segment.length == 2 && segment[1] == ':' && segment[0].isLetter()

/** A Windows host's path: on a drive (`C:\`, `c:/`, `D:`) or a share (`\\server\share`). */
fun isWindowsHostPath(path: String): Boolean =
    path.startsWith("\\\\") ||
        (path.length >= 2 && isDriveSegment(path.substring(0, 2)) && (path.length == 2 || path[2] == '\\' || path[2] == '/'))

/**
 * One name on the host: exactly on POSIX, and on Windows case-insensitively the way NTFS does it, one character
 * at a time with the invariant upper case (`Char.uppercaseChar`). So it holds in every UI language ("FILE" is
 * "file" on a Turkish Windows too), and 'İ' stays a different name from 'i'.
 */
fun sameHostSegment(a: String, b: String, windows: Boolean): Boolean {
    if (!windows) return a == b
    if (a.length != b.length) return false
    for (i in a.indices) if (a[i].uppercaseChar() != b[i].uppercaseChar()) return false
    return true
}

/** Segment by segment, with [sameHostSegment]: `C:\Users` is `c:\users`, `/Users` is not `/users`. */
fun sameHostSegments(a: List<String>, b: List<String>, windows: Boolean): Boolean =
    a.size == b.size && a.indices.all { sameHostSegment(a[it], b[it], windows) }

/** Whether [a] and [b] name the same place on the host. */
fun sameHostPath(a: String, b: String): Boolean =
    isAbsoluteHostPath(a) == isAbsoluteHostPath(b) &&
        sameHostSegments(hostPathSegments(a), hostPathSegments(b), windows = isWindowsHostPath(a) || isWindowsHostPath(b))

/**
 * [path] relative to [root], `/`-separated ("" for [root] itself), or null when [path] is not inside
 * [root]. A search hit the host spells `C:\Users\me\app/src/a.kt` is `src/a.kt` of a session whose
 * workdir reads `c:\users\me\app` or `C:/Users/me/app`.
 */
fun hostRelativePath(root: String, path: String): String? {
    if (isAbsoluteHostPath(root) != isAbsoluteHostPath(path)) return null
    val r = hostPathSegments(root)
    val p = hostPathSegments(path)
    if (p.size < r.size || !sameHostSegments(r, p.subList(0, r.size), windows = isWindowsHostPath(root))) return null
    return p.subList(r.size, p.size).joinToString("/")
}

/** Whether [path] is [root] or inside it, on the host's rules. */
fun hostIsInside(path: String, root: String): Boolean = hostRelativePath(root, path) != null

private fun isAbsoluteHostPath(path: String): Boolean = path.startsWith('/') || isWindowsHostPath(path)

/** The last segment of [path] on either separator: `/a/b` and `C:\a\b` both give `b`. */
fun pathLeaf(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    return trimmed.substring(trimmed.lastIndexOfAny(charArrayOf('/', '\\')) + 1)
}

/**
 * Compiled once. [inferHomeDir] runs per session row per sidebar render (via the session list's
 * `projectLabel` and `formatWorkdir`); compiling the pattern inside the function made every one of
 * those pay for a fresh regex.
 */
private val HOME_DIR_RE = Regex("^(/(?:home|Users)/[^/]+|[A-Za-z]:[\\\\/][Uu]sers[\\\\/][^\\\\/]+)")

/** Best-effort home dir (/home/<user>, /Users/<user> or C:\Users\<user>) when none was supplied. */
fun inferHomeDir(workdir: String?): String? {
    val probe = workdir ?: ""
    val m = HOME_DIR_RE.find(probe)
    return m?.groupValues?.getOrNull(1)
}

private fun isShare(path: String): Boolean = path.startsWith("\\\\")

/** How many leading segments are the root: a share's server and name, a drive, or none. */
private fun rootSegmentCount(path: String): Int = when {
    isShare(path) -> 2
    isWindowsHostPath(path) -> 1
    else -> 0
}

/** The separator [path] is written with: `\` on a Windows host unless it is spelled with `/` only. */
private fun separatorOf(path: String): Char {
    if (!isWindowsHostPath(path)) return '/'
    val rest = path.drop(2)
    return if ('/' in rest && '\\' !in rest) '/' else '\\'
}

/** [segments] of [path] (its root's first) as one path again, with [sep] between them. */
private fun assemble(path: String, segments: List<String>, sep: Char): String {
    val s = sep.toString()
    return when {
        // A share's root has no trailing separator (`\\server\share`); a drive's (`C:\`) and POSIX's (`/`) do.
        isShare(path) -> s + s + segments.joinToString(s)
        isWindowsHostPath(path) -> segments[0] + s + segments.drop(1).joinToString(s)
        path.startsWith('/') -> "/" + segments.joinToString(s)
        else -> segments.joinToString(s)
    }
}

/** [path]'s parent, in the path's own spelling; null at a root (`/`, `C:\`, `\\server\share`) or for one name. */
fun hostParent(path: String): String? {
    val segments = hostPathSegments(path)
    if (segments.size <= rootSegmentCount(path)) return null
    if (segments.size == 1 && rootSegmentCount(path) == 0 && !path.startsWith('/')) return null
    return assemble(path, segments.dropLast(1), separatorOf(path))
}

/** [relative] (a broker's `/`-separated path, or the host's own) under [root], in the root's separator. */
fun hostJoin(root: String, relative: String): String {
    val sep = separatorOf(root)
    val rel = relative.split('/', '\\').filter { it.isNotEmpty() }.joinToString(sep.toString())
    if (rel.isEmpty()) return root
    return if (root.endsWith('/') || root.endsWith('\\')) root + rel else "$root$sep$rel"
}

/**
 * One spelling for a host path: `.` and `..` resolved, separators collapsed into the host's own (`\` on Windows, as
 * its broker answers, `/` elsewhere), no trailing separator but at a root, the drive letter upper-case. Lexical only:
 * it never touches a file system.
 */
fun normalizeHostPath(path: String): String {
    val roots = rootSegmentCount(path)
    val segments = hostPathSegments(path)
    val out = ArrayList<String>(segments.take(roots))
    for (segment in segments.drop(roots)) {
        when (segment) {
            "." -> {}
            ".." -> if (out.size > roots && out.last() != "..") out.removeAt(out.size - 1) else if (roots == 0 && !path.startsWith('/')) out += segment
            else -> out += segment
        }
    }
    if (roots == 1) out[0] = out[0].uppercase()
    return assemble(path, out, if (isWindowsHostPath(path)) '\\' else '/')
}

private const val URI_SAFE = "-._~!$&'()*+,;=:@"

private fun encodeUriSegment(segment: String): String = buildString {
    for (byte in segment.encodeToByteArray()) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (c.isLetterOrDigit() && c.code < 128 || c in URI_SAFE) append(c)
        else append('%').append(((byte.toInt() and 0xFF) or 0x100).toString(16).substring(1).uppercase())
    }
}

/** A `file:` URI for a host path: `file:///C:/Users/…` on Windows, `file:///home/…`, `file://server/share/…`. */
fun hostFileUri(path: String): String {
    val segments = hostPathSegments(path)
    return when {
        isShare(path) -> "file://${segments[0]}/" + segments.drop(1).joinToString("/") { encodeUriSegment(it) }
        isWindowsHostPath(path) -> "file:///${segments[0].uppercase()}/" + segments.drop(1).joinToString("/") { encodeUriSegment(it) }
        else -> "file:///" + segments.joinToString("/") { encodeUriSegment(it) }
    }
}
