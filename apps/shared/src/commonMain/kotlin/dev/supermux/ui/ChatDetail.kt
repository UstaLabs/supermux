package dev.supermux.ui

/**
 * Chat transcript density levels (web/iOS/Android/Desktop/macOS parity).
 * High: terminal windows + file diffs for Bash/Edit/Write; quiet rows for other tools.
 */
enum class ChatDetailLevel {
    LOW,
    MEDIUM,
    HIGH,
    ;

    val wire: String
        get() = when (this) {
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
        }

    val label: String
        get() = when (this) {
            LOW -> "Low"
            MEDIUM -> "Medium"
            HIGH -> "High"
        }

    companion object {
        fun parse(raw: String?): ChatDetailLevel = when (raw?.lowercase()) {
            "low" -> LOW
            "medium" -> MEDIUM
            "high" -> HIGH
            else -> MEDIUM
        }
    }
}

/** All three levels are implemented. */
fun isChatDetailImplemented(level: ChatDetailLevel): Boolean =
    level == ChatDetailLevel.LOW || level == ChatDetailLevel.MEDIUM || level == ChatDetailLevel.HIGH

/** Effective render mode — all implemented levels pass through. */
fun effectiveChatDetail(level: ChatDetailLevel): ChatDetailLevel =
    when (level) {
        ChatDetailLevel.LOW -> ChatDetailLevel.LOW
        ChatDetailLevel.HIGH -> ChatDetailLevel.HIGH
        else -> ChatDetailLevel.MEDIUM
    }

/**
 * Reject unimplemented levels for set-level APIs.
 * @return the level to store, or null if the request should no-op.
 */
fun sanitizeSetLevel(level: ChatDetailLevel): ChatDetailLevel? =
    if (isChatDetailImplemented(level)) level else null

/**
 * Turn boundary for "tools this turn": last **user** message timestamp (ms),
 * else [workingSinceMs], else 0.
 *
 * Native clients treat user messages as `direction == "inbound"` (or `hasPrefix("in")`).
 * Pass [isUserDirection] accordingly. [tsToEpochMs] converts wire `ts` to epoch ms.
 */
fun turnBoundaryMs(
    messages: List<Pair<String, String>>, // direction, ts
    isUserDirection: (String) -> Boolean,
    tsToEpochMs: (String) -> Long,
    workingSinceMs: Long? = null,
): Long {
    for (i in messages.indices.reversed()) {
        val (dir, ts) = messages[i]
        if (isUserDirection(dir)) return tsToEpochMs(ts)
    }
    return workingSinceMs ?: 0L
}

/** Count tool events at or after [sinceMs]. */
fun countToolsSince(
    toolTimestampsMs: List<Long>,
    sinceMs: Long,
): Int = toolTimestampsMs.count { it >= sinceMs }

/**
 * Low-mode working status: platform [baseLabel] + optional tool + count + duration.
 * Segments joined with " · ".
 */
fun formatLowWorkingStatus(
    baseLabel: String,
    detail: String?,
    tool: String?,
    toolCount: Int,
    durationLabel: String,
): String {
    val parts = mutableListOf(baseLabel)
    if (detail == "running" && !tool.isNullOrBlank()) parts.add(tool)
    if (toolCount > 0) {
        parts.add(if (toolCount == 1) "1 tool" else "$toolCount tools")
    }
    if (durationLabel.isNotEmpty()) parts.add(durationLabel)
    return parts.joinToString(" · ")
}
