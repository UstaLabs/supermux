package dev.supermux.ui.session

import dev.supermux.proto.PromptRequest
import dev.supermux.proto.WorkspaceDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** #143 (Ahmet picked option B): sessions blocked on the user rise into a "Needs you" section with their question. */
class NeedsYouTest {
    private val allow = { tool: String -> "Allow $tool?" }

    @Test fun a_blocking_permission_reads_as_its_question_and_first_line() {
        val asks = needsYouAsks(
            mapOf("s1" to listOf(PromptRequest(requestId = "r1", kind = "permission", title = "Bash", body = "Bash rm -f \"\${S:?}\"/*\nmore"))),
            allowTitle = allow, question = "Question",
        )
        assertEquals(mapOf("s1" to "Allow Bash? rm -f \"\${S:?}\"/*"), asks)
    }

    @Test fun a_question_reads_as_question_and_a_non_blocking_request_or_an_empty_list_is_left_out() {
        val asks = needsYouAsks(
            mapOf(
                "q" to listOf(PromptRequest(requestId = "r2", kind = "question", title = "", body = "[]")),
                "nb" to listOf(PromptRequest(requestId = "r3", kind = "permission", title = "Edit", blocking = false)),
                "none" to emptyList(),
            ),
            allowTitle = allow, question = "Question",
        )
        assertEquals(mapOf("q" to "Question"), asks)
    }

    @Test fun waiting_workspaces_are_the_ones_with_a_waiting_chat() {
        val a = WorkspaceDto(id = "w1", name = "a", workdir = "/a", primarySessionId = "s1")
        val b = WorkspaceDto(id = "w2", name = "b", workdir = "/b", primarySessionId = "s2")
        assertEquals(listOf("w1"), waitingWorkspaces(listOf(a, b), setOf("s1")).map { it.id })
    }
}
