package dev.supermux.state

import dev.supermux.proto.LiveStreamDto
import dev.supermux.proto.ServerFrame

/**
 * Card #76: a reply (or the agent's thinking) while the agent writes it. Never stored: the stored message
 * (or reasoning row) with the same [stream] takes its place. At most one per kind per session.
 */
data class LiveReply(val stream: String, val kind: String, val text: String) {
    val thinking: Boolean get() = kind == KIND_THINKING

    companion object {
        const val KIND_THINKING = "thinking"
    }
}

/** Thinking first: it comes before the reply it leads to. */
private fun List<LiveReply>.ordered(): List<LiveReply> = sortedBy { if (it.thinking) 0 else 1 }

/**
 * One `reply_stream` frame: `at` 0 is the whole text so far, `at` == the text's length appends, a frame sent
 * twice adds only what is new, and a gap (or a stream first seen past 0) waits for the next `at` 0.
 */
internal fun applyLiveDelta(live: Map<String, List<LiveReply>>, frame: ServerFrame.ReplyStream): Map<String, List<LiveReply>> {
    val list = live[frame.session].orEmpty()
    val have = list.firstOrNull { it.stream == frame.stream }?.text
    val text = when {
        frame.at == 0 -> frame.text
        have == null || frame.at > have.length -> return live
        frame.at == have.length -> have + frame.text
        else -> {
            val known = have.length - frame.at
            if (frame.text.length <= known) return live
            have + frame.text.substring(known)
        }
    }
    val others = list.filter { it.stream != frame.stream && it.kind != frame.kind }
    return live + (frame.session to (others + LiveReply(frame.stream, frame.kind, text)).ordered())
}

/** The live reply [stream] of [session] is over (its stored row arrived, or nothing will be stored). */
internal fun dropLive(live: Map<String, List<LiveReply>>, session: String, stream: String?): Map<String, List<LiveReply>> {
    if (stream == null) return live
    val list = live[session] ?: return live
    val next = list.filterNot { it.stream == stream }
    return when {
        next.size == list.size -> live
        next.isEmpty() -> live - session
        else -> live + (session to next)
    }
}

/** The snapshot's open streams: a reconnect shows the text so far, and nothing older survives it. */
internal fun liveFromSnapshot(streams: Map<String, List<LiveStreamDto>>): Map<String, List<LiveReply>> =
    streams.mapValues { (_, l) -> l.map { LiveReply(it.stream, it.kind, it.text) }.ordered() }.filterValues { it.isNotEmpty() }
