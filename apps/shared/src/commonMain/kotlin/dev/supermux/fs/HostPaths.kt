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
