// Where one path on the broker's host stands relative to another, decided in ONE place for every
// client feature. A POSIX host's paths are case-sensitive and use `/`. A Windows host's take either
// separator, and its file system names a folder whatever its letter case: `c:\users\me\app` is the
// host's own `C:\Users\me\app`. Lexical only: nothing here touches a file system.
package dev.supermux.fs

/**
 * A path's segments: `/a/b` and `C:\a\b` alike. A path starting with `/` is a POSIX host's, where `\` is an ordinary
 * character of a name; any other (a drive, a share, a relative path, a label like `…\parent\leaf`) splits on both.
 * A Windows drive (`C:`) is the first.
 */
fun hostPathSegments(path: String): List<String> = path.split(*separatorsOf(path)).filter { it.isNotEmpty() }

private val POSIX_SEPARATORS = charArrayOf('/')
private val BOTH_SEPARATORS = charArrayOf('/', '\\')

/** The characters that separate [path]'s names (see [hostPathSegments]). */
private fun separatorsOf(path: String): CharArray = if (path.startsWith('/')) POSIX_SEPARATORS else BOTH_SEPARATORS

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

/** An absolute path on either kind of host: POSIX (`/…`) or Windows (`C:\…`, `\\server\share`). */
fun isAbsoluteHostPath(path: String): Boolean = path.startsWith('/') || isWindowsHostPath(path)

/**
 * A file's own name from a name or path any host may have written: what follows its last `/` or `\`. For an
 * attachment's or a download's name, whose host is unknown; [pathLeaf] is for a path of a known host.
 */
fun fileNameOf(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')

/** [path] without its trailing separators, but a root as it is: `/`, `C:\`, `C:/` (`C:` alone is the drive's current folder). */
fun trimHostPath(path: String): String {
    if (path.startsWith('/')) return path.trimEnd('/').ifEmpty { "/" }
    val t = path.trimEnd('/', '\\')
    return if (t.length == 2 && isDriveSegment(t) && path.length > 2) path.substring(0, 3) else t
}

/** The last segment of [path]: `/a/b` and `C:\a\b` both give `b` (separators as in [hostPathSegments]). */
fun pathLeaf(path: String): String {
    val separators = separatorsOf(path)
    val trimmed = path.trimEnd(*separators)
    return trimmed.substring(trimmed.lastIndexOfAny(separators) + 1)
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

/**
 * [relative] (a broker's `/`-separated path, or the host's own) under [root], in the root's separator; `.` segments
 * drop out. Under a POSIX root a `\` in [relative] belongs to a name.
 */
fun hostJoin(root: String, relative: String): String {
    val sep = separatorOf(root)
    val rel = relative.split(*separatorsOf(root)).filter { it.isNotEmpty() && it != "." }.joinToString(sep.toString())
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

/** The root [path] hangs from, in its own spelling: `/`, `C:\` (or `C:/`), `\\server\share`; null for a relative path. */
fun hostRoot(path: String): String? {
    val segments = hostPathSegments(path)
    return when {
        isShare(path) -> if (segments.size >= 2) assemble(path, segments.take(2), separatorOf(path)) else null
        isWindowsHostPath(path) -> assemble(path, segments.take(1), separatorOf(path))
        path.startsWith('/') -> "/"
        else -> null
    }
}

// What java.net.URLEncoder leaves as it is: ASCII letters and digits and these. Every other byte of a name's UTF-8 is
// `%XX`, `! ~ ' ( )` included (the convergence EditorPanelLspUriTest pinned for the LSP URIs).
private const val URI_UNRESERVED = ".-*_"
private const val HEX = "0123456789ABCDEF"

private fun encodeUriSegment(segment: String): String = buildString {
    for (byte in segment.encodeToByteArray()) {
        val v = byte.toInt() and 0xFF
        val c = v.toChar()
        if (v < 128 && (c.isLetterOrDigit() || c in URI_UNRESERVED)) append(c)
        else append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** `%XX` escapes as UTF-8 bytes (a run of them decodes together), everything else as it is; a bad escape stays. */
private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        if (s[i] != '%') { out.append(s[i]); i++; continue }
        val bytes = ArrayList<Byte>()
        while (i + 2 < s.length && s[i] == '%') {
            val hi = hexValue(s[i + 1])
            val lo = hexValue(s[i + 2])
            if (hi < 0 || lo < 0) break
            bytes += (hi * 16 + lo).toByte()
            i += 3
        }
        if (bytes.isEmpty()) { out.append('%'); i++ } else out.append(bytes.toByteArray().decodeToString())
    }
    return out.toString()
}

/**
 * The host path a `file:` URI names, or null for another scheme. `file:///C:/Users/…` and `file:///c%3A/Users/…` (how
 * VS Code's language servers spell it) are `C:\Users\…`; `file://server/share/…` is `\\server\share\…`; the rest is
 * a POSIX path. `localhost` is no host.
 */
fun hostPathFromFileUri(uri: String): String? {
    if (!uri.startsWith("file://")) return null
    val rest = uri.removePrefix("file://").let { if (it.startsWith("localhost/")) it.removePrefix("localhost") else it }
    val path = percentDecode(rest)
    return when {
        path.length >= 3 && path[0] == '/' && isDriveSegment(path.substring(1, 3)) && (path.length == 3 || path[3] == '/') ->
            path.substring(1, 3).uppercase() + path.substring(3).replace('/', '\\').ifEmpty { "\\" }
        !path.startsWith('/') -> "\\\\" + path.replace('/', '\\')
        else -> path
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
