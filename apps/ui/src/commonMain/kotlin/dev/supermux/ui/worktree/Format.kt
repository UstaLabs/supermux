package dev.supermux.ui.worktree

// `dev.supermux.ui.update.formatUpdateBytes` exists but is scoped to the download-progress UI:
// non-null `Long` only, no GB tier (a download's payload is always sub-GB), and its wording
// ("1.5 KB") doesn't need the "…" placeholder a not-yet-known worktree size does. A worktree's
// [dev.supermux.net.IgnoredEntryDto.bytes] and total size CAN be null (still being `du`'d) and
// can run into the GBs (a full checkout + node_modules), so this gets its own small helper rather
// than stretching that one across two unrelated features.

fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "…"
    bytes >= 1L shl 30 -> "${(bytes * 10 / (1L shl 30)) / 10.0} GB"
    bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
    bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
    else -> "$bytes B"
}

fun formatAge(nowMs: Long, thenMs: Long): String {
    val d = (nowMs - thenMs).coerceAtLeast(0) / 86_400_000
    return when {
        d >= 1 -> "$d d"
        else -> "${((nowMs - thenMs).coerceAtLeast(0) / 3_600_000)} h"
    }
}

internal fun plural(n: Int, one: String, many: String) = if (n == 1) "$n $one" else "$n $many"

/** Ignored-entry names are broker-reported relative paths (`docs`, `apps/build`, even a nested
 *  `docs/superpowers/plans/x.md`) — collapse anything that isn't tag-safe so a Compose testTag
 *  stays a single flat token. A no-op for the common bare-name case. */
internal fun tagSafe(name: String): String = name.replace(Regex("[^A-Za-z0-9_]"), "_")
