package dev.supermux.ui

import dev.supermux.fs.hostJoin
import dev.supermux.fs.hostRelativePath
import dev.supermux.fs.inferHomeDir
import dev.supermux.fs.isWindowsHostPath
import dev.supermux.fs.normalizeHostPath

/** Neither absolute on either host nor under `~`: relative to the workdir. */
private fun isRelativeChatPath(path: String): Boolean =
    !isAbsoluteEditorPath(path) && path != "~" && !path.startsWith("~/") && !path.startsWith("~\\")

/** Map an agent-mentioned path to a workdir-relative path for the editor API.
 *  Returns "" for the workdir root, or null when the path is outside the workdir. Under a Windows
 *  workdir a relative path may use `\` and the comparison ignores letter case, as the host does.
 *  Port of toWorkdirRelativePath (workdir-display.ts). */
fun toWorkdirRelativePath(path: String, workdir: String, homeDir: String?): String? {
    val root = normalizeWorkdirKey(workdir, homeDir)
    val trimmed = stripFilePathRefSuffix(path.trim())

    if (isRelativeChatPath(trimmed)) {
        return (if (isWindowsHostPath(root)) trimmed.replace('\\', '/') else trimmed).removePrefix("./")
    }
    return hostRelativePath(root, normalizeWorkdirKey(trimmed, homeDir))
}

/**
 * The editor's key for [path]: workdir-relative when it is inside [workdir] (as
 * [toWorkdirRelativePath] gives it), otherwise its normalized ABSOLUTE path — a file outside the
 * workdir opens by that, through the host's absolute-path routes. Null only for the workdir itself.
 */
fun toEditorPath(path: String, workdir: String, homeDir: String?): String? {
    val home = homeDir ?: inferHomeDir(workdir)
    val trimmed = stripFilePathRefSuffix(path.trim())
    val root = normalizeWorkdirKey(workdir, home)
    // `.`/`..` segments are resolved first, so `../sibling/x` lands outside and `src/../a` inside.
    val joined = when {
        !isRelativeChatPath(trimmed) -> normalizeWorkdirKey(trimmed, home)
        isWindowsHostPath(root) -> hostJoin(root, trimmed)
        else -> "$root/$trimmed"
    }
    if (!isAbsoluteEditorPath(joined)) return null // `~` with no home to expand it against
    val abs = if (isWindowsHostPath(joined)) normalizeHostPath(joined) else collapseDotSegments(joined)
    val rel = toWorkdirRelativePath(abs, workdir, home) ?: return abs
    return rel.ifEmpty { null }
}

/** `/a/./b/../c` → `/a/c` (an absolute path; `..` never climbs above `/`). */
private fun collapseDotSegments(abs: String): String {
    val out = ArrayList<String>()
    for (seg in abs.split('/')) when (seg) {
        "", "." -> {}
        ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
        else -> out += seg
    }
    return "/" + out.joinToString("/")
}

/**
 * An editor key that names a file OUTSIDE the workdir by its absolute path ([toEditorPath]): `/…` on a
 * POSIX host, `C:\…` or `\\server\share\…` on Windows. Workdir-relative keys never start with `/`: every
 * open path strips it.
 */
fun isAbsoluteEditorPath(path: String): Boolean = path.startsWith("/") || isWindowsHostPath(path)

/** Port of normalizeWorkdirKey: expand ~, repair home-prefixed tilde, collapse // , drop trailing /. */
fun normalizeWorkdirKey(workdir: String, homeDir: String?): String {
    val trimmed = workdir.trim()
    val home = normalizeHomeDir(homeDir ?: inferHomeDir(trimmed))
    val expanded = when {
        home != null && trimmed == "~" -> home
        home != null && isWindowsHostPath(home) && (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) ->
            hostJoin(home, trimmed.substring(2))
        home != null && trimmed.startsWith("~/") -> "$home/${trimmed.substring(2)}"
        else -> expandHomePrefixedTilde(trimmed, home)
    }
    // A Windows host's path: its own separators, dots resolved, no trailing separator but at a root.
    if (isWindowsHostPath(expanded)) return normalizeHostPath(expanded)
    val normalized = expanded.replace(Regex("/+"), "/")
    return if (normalized.length > 1) normalized.trimEnd('/') else normalized
}

private fun expandHomePrefixedTilde(workdir: String, homeDir: String?): String {
    if (homeDir == null) return workdir
    val homeTilde = "$homeDir/~"
    if (workdir == homeTilde) return homeDir
    if (workdir.startsWith("$homeTilde/")) return "$homeDir/${workdir.substring(homeTilde.length + 1)}"
    return workdir
}

private fun normalizeHomeDir(homeDir: String?): String? {
    if (homeDir.isNullOrEmpty()) return null
    val normalized = homeDir.replace(Regex("/+"), "/")
    return if (normalized.length > 1) normalized.trimEnd('/') else normalized
}
