package dev.supermux.android.terminal

internal const val TERMINAL_CREATE_GRACE_MS = 15_000L

/**
 * Reconcile the broker-owned terminal list with short-lived local UI operations. The broker list
 * is authoritative across devices; pending creates are kept briefly until their websocket creates
 * the tmux terminal, and pending closes stay hidden until the broker confirms removal.
 */
internal fun reconcileTerminalTabs(
    remoteIds: List<String>,
    localIds: List<String>,
    pendingCreates: Map<String, Long>,
    pendingCloses: Set<String>,
    nowMs: Long,
): List<String> {
    val remote = remoteIds.filterNot { it in pendingCloses }
    val localPending = localIds.filter { id ->
        id !in remoteIds && id !in pendingCloses &&
            pendingCreates[id]?.let { nowMs - it < TERMINAL_CREATE_GRACE_MS } == true
    }
    return (remote + localPending).distinct()
}

internal fun activeTerminalAfterSync(
    ids: List<String>,
    current: String,
    preferredIndex: Int = 0,
): String {
    if (current in ids) return current
    return ids.getOrNull(preferredIndex) ?: ids.getOrNull(preferredIndex - 1) ?: ids.firstOrNull().orEmpty()
}
