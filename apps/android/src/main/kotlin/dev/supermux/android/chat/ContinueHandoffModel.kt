package dev.supermux.android.chat

import dev.supermux.net.SpawnRequest
import dev.supermux.session.HandoffPrefill

/** Payload from the continue sheet — agent/model/thinking plus the editable handoff text. */
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
        inheritFrom = sourceSessionId,
        firstMessage = text.ifBlank { null },
    )
}

/** Broker-delivered first turn: never queue a client WS Send for this spawn. */
fun continueQueuesClientSend(request: SpawnRequest): Boolean = request.firstMessage.isNullOrBlank()

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
    inheritFrom = null,
    firstMessage = null,
)

object ChatOverflowTestIds {
    const val CONTINUE = "chat_overflow_continue"
    const val CONTINUE_FIELD = "overflow_continue_field"
    const val CONTINUE_CONFIRM = "overflow_continue_confirm"
}
