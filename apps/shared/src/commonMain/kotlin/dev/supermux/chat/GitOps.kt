package dev.supermux.chat

import dev.supermux.net.GitOpResult
import dev.supermux.proto.GitLiteStatusDto

/** Whether the git menu's third row is Publish (no upstream yet) rather than Push. Mirrors Android's
 *  `session.git?.unpublished == true` gate. Pure so the decision is unit-testable off the DTO. */
fun shouldPublish(git: GitLiteStatusDto?): Boolean = git?.unpublished == true

/** Compact result label for a completed git op — the message when the broker gave one, else its
 *  status, else a generic done; `null` result (any failure, getOrNull-degraded upstream) → "<Op>
 *  failed". */
fun gitOpResultLabel(op: String, result: GitOpResult?): String {
    if (result == null) return "$op failed"
    result.message?.takeIf { it.isNotBlank() }?.let { return it }
    return result.status.ifBlank { "$op done" }
}
