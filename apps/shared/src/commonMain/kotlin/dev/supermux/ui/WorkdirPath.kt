package dev.supermux.ui

import dev.supermux.session.inferHomeDir

/** Map an agent-mentioned path to a workdir-relative path for the editor API.
 *  Returns "" for the workdir root, or null when the path is outside the workdir.
 *  Port of toWorkdirRelativePath (workdir-display.ts). */
fun toWorkdirRelativePath(path: String, workdir: String, homeDir: String?): String? {
    val root = normalizeWorkdirKey(workdir, homeDir)
    val trimmed = stripFilePathRefSuffix(path.trim())

    if (!trimmed.startsWith("/") && !trimmed.startsWith("~/") && trimmed != "~") {
        return trimmed.removePrefix("./")
    }
    val abs = normalizeWorkdirKey(trimmed, homeDir)
    if (abs == root) return ""
    return if (abs.startsWith("$root/")) abs.substring(root.length + 1) else null
}

/**
 * The editor's key for [path]: workdir-relative when it is inside [workdir] (as
 * [toWorkdirRelativePath] gives it), otherwise its normalized ABSOLUTE path — a file outside the
 * workdir opens by that, through the host's absolute-path routes. Null only for the workdir itself.
 */
fun toEditorPath(path: String, workdir: String, homeDir: String?): String? {
    val home = homeDir ?: inferHomeDir(workdir)
    val trimmed = stripFilePathRefSuffix(path.trim())
    val isRelative = !trimmed.startsWith("/") && !trimmed.startsWith("~/") && trimmed != "~"
    // `.`/`..` segments are resolved first, so `../sibling/x` lands outside and `src/../a` inside.
    val joined = if (isRelative) "${normalizeWorkdirKey(workdir, home)}/$trimmed" else normalizeWorkdirKey(trimmed, home)
    if (!joined.startsWith("/")) return null // `~` with no home to expand it against
    val abs = collapseDotSegments(joined)
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
 * An editor key that names a file OUTSIDE the workdir by its absolute path ([toEditorPath]).
 * Workdir-relative keys never start with `/`: every open path strips it.
 */
fun isAbsoluteEditorPath(path: String): Boolean = path.startsWith("/")

/** Port of normalizeWorkdirKey: expand ~, repair home-prefixed tilde, collapse // , drop trailing /. */
fun normalizeWorkdirKey(workdir: String, homeDir: String?): String {
    val trimmed = workdir.trim()
    val home = normalizeHomeDir(homeDir ?: inferHomeDir(trimmed))
    val expanded = when {
        home != null && trimmed == "~" -> home
        home != null && trimmed.startsWith("~/") -> "$home/${trimmed.substring(2)}"
        else -> expandHomePrefixedTilde(trimmed, home)
    }
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
