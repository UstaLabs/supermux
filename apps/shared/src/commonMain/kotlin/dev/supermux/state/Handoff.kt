package dev.supermux.state

import dev.supermux.net.SpawnRequest
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.session.HandoffPrefill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Payload from the continue dialog — agent/model/thinking plus the editable handoff text. */
data class ContinueHandoff(
    val message: String,
    val agent: String,
    val model: String?,
    val reasoningLevel: String?,
)

/**
 * Build the POST /sessions body for "Continue in new conversation".
 *
 * [workspaceId] is the SOURCE session's workspace so the new chat joins it.
 * Absent (old broker) leaves [SpawnRequest.workspaceId] null; [inheritFrom] still
 * goes on the wire. [firstMessage] is delivered by the broker after spawn —
 * the client must not also emit [dev.supermux.proto.ClientFrame.Send].
 */
fun continueSpawnRequest(
    sourceWorkdir: String,
    sourceSessionId: String,
    sourceName: String?,
    sourceAgent: String?,
    workspaceId: String?,
    handoff: ContinueHandoff,
): SpawnRequest {
    val text = handoff.message.trim()
    val chosen = handoff.agent.trim().ifEmpty { null }
        ?: HandoffPrefill.defaultAgent(sourceAgent)
    return SpawnRequest(
        workdir = sourceWorkdir,
        name = sourceName?.ifBlank { null },
        agent = chosen,
        model = handoff.model?.ifBlank { null },
        reasoningLevel = handoff.reasoningLevel?.ifBlank { null },
        workspaceId = workspaceId,
        // Same checkout as the source — never a (nested) worktree of its own.
        worktree = false,
        inheritFrom = sourceSessionId,
        firstMessage = text.ifBlank { null },
    )
}

/** True when the broker will deliver [SpawnRequest.firstMessage] — do not also queue a WS Send. */
fun brokerDeliversFirstMessage(request: SpawnRequest): Boolean = !request.firstMessage.isNullOrBlank()

/**
 * Spec §9.1: a chat started in a workspace joins it and starts in its workdir.
 * No inheritFrom / firstMessage.
 */
fun newChatHereRequest(
    workspaceId: String,
    workdir: String,
    agent: String,
    model: String? = null,
    reasoningLevel: String? = null,
): SpawnRequest = SpawnRequest(
    workdir = workdir,
    agent = agent,
    model = model?.ifBlank { null },
    reasoningLevel = reasoningLevel?.ifBlank { null },
    workspaceId = workspaceId,
    // The broker cuts a worktree when this is omitted; a chat started HERE stays in
    // the workspace's own checkout.
    worktree = false,
    inheritFrom = null,
    firstMessage = null,
)

private val HTTP_UNAVAILABLE = Regex(
    """^BrokerApi request unavailable:\s*HTTP\s+(\d{3})\s*(.*)$""",
    RegexOption.DOT_MATCHES_ALL,
)
private val HTTP_CODE = Regex("""HTTP\s+(\d{3})""")
private const val GENERIC_UNAVAILABLE = "BrokerApi request unavailable"

/**
 * User-facing text when POST /sessions (continue / new chat) fails.
 *
 * [dev.supermux.net.BrokerApi] surfaces non-2xx as [CancellationException] whose message is
 * `BrokerApi request unavailable: HTTP <code> <body>`. Prefer the JSON `error` field from that
 * body when present; otherwise an HTTP status or a generic refusal.
 */
fun spawnFailureMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.take(6)
    for (err in chain) {
        val msg = err.message.orEmpty()
        HTTP_UNAVAILABLE.matchEntire(msg)?.let { m ->
            val status = m.groupValues[1]
            val body = m.groupValues[2].trim()
            jsonErrorField(body)?.let { return it }
            return "Broker refused to start the session (HTTP $status)"
        }
        HTTP_CODE.find(msg)?.let { m ->
            if (!msg.startsWith(GENERIC_UNAVAILABLE)) {
                return "Broker refused to start the session (HTTP ${m.groupValues[1]})"
            }
        }
    }
    val top = t.message?.trim().orEmpty()
    if (top.isEmpty() || isBrokerUnavailableMessage(top)) {
        return "Broker refused to start the session"
    }
    return top
}

/** Remap the SKIE-safe spawn cancel into an [IllegalStateException] callers can show. */
fun remapSpawnFailure(t: Throwable): Nothing {
    if (t is CancellationException && !isBrokerUnavailableCancellation(t.message)) throw t
    throw IllegalStateException(spawnFailureMessage(t), t)
}

/**
 * True only for the SKIE-safe BrokerApi cancel shape. Empty/null is *not* a match:
 * a bare [CancellationException] (viewModelScope teardown) must propagate unchanged.
 */
private fun isBrokerUnavailableCancellation(msg: String?): Boolean {
    val m = msg?.trim().orEmpty()
    return m == GENERIC_UNAVAILABLE || m.startsWith("$GENERIC_UNAVAILABLE:")
}

internal fun isBrokerUnavailableMessage(msg: String?): Boolean {
    val m = msg?.trim().orEmpty()
    return m.isEmpty() || isBrokerUnavailableCancellation(msg)
}

/** Best-effort `"error":"..."` from a JSON object body. */
internal fun jsonErrorField(body: String): String? {
    val key = "\"error\""
    val start = body.indexOf(key)
    if (start < 0) return null
    var i = start + key.length
    while (i < body.length && body[i].isWhitespace()) i++
    if (i >= body.length || body[i] != ':') return null
    i++
    while (i < body.length && body[i].isWhitespace()) i++
    if (i >= body.length || body[i] != '"') return null
    i++
    val out = StringBuilder()
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && i + 1 < body.length -> {
                val n = body[i + 1]
                out.append(
                    when (n) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '"' -> '"'
                        '\\' -> '\\'
                        else -> n
                    },
                )
                i += 2
            }
            c == '"' -> return out.toString().takeIf { it.isNotBlank() }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return null
}

enum class SidebarReorderKind { SESSIONS, WORKSPACES }

/** Same rule as the sidebar: empty live workspaces → session reorder, else workspace reorder. */
fun sidebarReorderKind(liveWorkspaces: List<WorkspaceDto>): SidebarReorderKind =
    if (liveWorkspaces.isEmpty()) SidebarReorderKind.SESSIONS else SidebarReorderKind.WORKSPACES

data class ChatViewTarget(val workspaceId: String, val viewId: String)

/**
 * Wait for the broker [ViewAdded] that hosts [sessionId], then return that view.
 * Null if it never appears within [timeoutMs].
 */
suspend fun awaitChatViewForSession(
    workspaces: Flow<List<WorkspaceDto>>,
    sessionId: String,
    timeoutMs: Long = 5_000,
): ChatViewTarget? = withTimeoutOrNull(timeoutMs) {
    val list = workspaces.first { ws ->
        ws.any { w -> w.views.any { v -> v.chatSessionId() == sessionId } }
    }
    val w = list.first { w -> w.views.any { v -> v.chatSessionId() == sessionId } }
    val v: ViewDto = w.views.first { it.chatSessionId() == sessionId }
    ChatViewTarget(w.id, v.id)
}
