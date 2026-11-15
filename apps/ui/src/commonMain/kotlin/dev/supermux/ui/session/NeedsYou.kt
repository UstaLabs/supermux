package dev.supermux.ui.session

import dev.supermux.proto.PromptRequest
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.chatSessionIds

// #143 (Ahmet picked option B of the prototype): a session blocked on the user (an open permission prompt or question)
// rises into a "Needs you" section at the top of the sidebar, with an amber "Needs you" chip and its question as the
// preview, until it is answered. A permission prompt nobody saw blocked #117 for 23 minutes.

/**
 * Session id → the line its "Needs you" row shows: the first blocking request's question ("Allow Bash? rm -f …" for a
 * permission, [question] for a question set). Non-blocking requests and sessions with none are left out.
 */
fun needsYouAsks(
    requests: Map<String, List<PromptRequest>>,
    allowTitle: (tool: String) -> String,
    question: String,
): Map<String, String> = buildMap {
    for ((sessionId, list) in requests) {
        val ask = list.firstOrNull { it.blocking } ?: continue
        put(sessionId, if (ask.kind == "question") question else permissionLine(ask, allowTitle))
    }
}

/** "Allow <tool>? <the first line of what it asks>", the body's leading tool name dropped (the card does the same). */
private fun permissionLine(ask: PromptRequest, allowTitle: (String) -> String): String {
    val tool = ask.title.trim().ifBlank { "tool" } // i18n-ok: a fallback tool name, never shown in practice
    val first = ask.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val rest = if (first.startsWith(tool) && first.getOrNull(tool.length)?.isWhitespace() == true) first.substring(tool.length).trimStart() else first
    return listOf(allowTitle(tool), rest).filter { it.isNotBlank() }.joinToString(" ")
}

/** The workspaces with a chat (or primary session) in [waiting], in their order. */
fun waitingWorkspaces(workspaces: List<WorkspaceDto>, waiting: Set<String>): List<WorkspaceDto> =
    if (waiting.isEmpty()) emptyList()
    else workspaces.filter { w -> w.primarySessionId in waiting || w.chatSessionIds().any { it in waiting } }
