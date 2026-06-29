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
