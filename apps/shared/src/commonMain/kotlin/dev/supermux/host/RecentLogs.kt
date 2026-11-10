package dev.supermux.host

import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo

/** Card #121: how many chats the offline cache keeps messages for, how many each, and how long one may be. */
const val CACHED_CHATS = 20
const val CACHED_MESSAGES_PER_CHAT = 30
const val CACHED_MESSAGE_CHARS = 4_000

/**
 * The newest [perChat] messages of [sessions]' [chats] most recently active chats, each text cut to
 * [maxText] characters. Local echoes are left out: an unsent message is not the broker's yet.
 */
fun recentLogs(
    sessions: List<SessionInfo>,
    logs: Map<String, List<LogEntry>>,
    chats: Int = CACHED_CHATS,
    perChat: Int = CACHED_MESSAGES_PER_CHAT,
    maxText: Int = CACHED_MESSAGE_CHARS,
): Map<String, List<LogEntry>> =
    sessions.mapNotNull { s ->
        logs[s.id]?.filterNot { it.id.startsWith("local-") }?.takeIf { it.isNotEmpty() }?.let { s.id to it }
    }
        .sortedByDescending { (_, log) -> log.last().ts }
        .take(chats)
        .associate { (id, log) ->
            id to log.takeLast(perChat).map { e -> if ((e.text?.length ?: 0) > maxText) e.copy(text = e.text!!.take(maxText)) else e }
        }

/**
 * One step down for a store with a quota (the browser's localStorage): each host keeps only the more
 * recent half of its cached chats. Null once no host has any left, so the caller stops trying.
 */
fun shrinkRecentLogs(snapshots: List<HostSnapshot>): List<HostSnapshot>? {
    if (snapshots.all { it.recentLogs.isEmpty() }) return null
    return snapshots.map { snap ->
        val keep = snap.recentLogs.entries.sortedByDescending { it.value.lastOrNull()?.ts.orEmpty() }.take(snap.recentLogs.size / 2)
        snap.copy(recentLogs = keep.associate { it.key to it.value })
    }
}
