package dev.supermux.workspace

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private fun chatView(id: String, sessionId: String, workspaceId: String = "w") = ViewDto(
    id = id, workspaceId = workspaceId, kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

private fun ws(
    id: String, name: String, workdir: String,
    repoRoot: String? = null, sortOrder: Int = 0, views: List<ViewDto> = emptyList(),
) = WorkspaceDto(id = id, name = name, workdir = workdir, repoRoot = repoRoot, sortOrder = sortOrder, views = views)

class WorkspaceGroupingTest {

    @Test
    fun groupsByRepoRootFallingBackToWorkdir() {
        val a = ws("w1", "a", "/home/u/.mux/worktrees/x", repoRoot = "/home/u/projects/app")
        val b = ws("w2", "b", "/home/u/.mux/worktrees/y", repoRoot = "/home/u/projects/app")
        val c = ws("w3", "c", "/home/u/projects/other")

        val groups = groupWorkspaces(listOf(a, b, c), home = "/home/u")

        assertEquals(2, groups.size)
        assertEquals(listOf("w1", "w2"), groups.first { it.key == "/home/u/projects/app" }.workspaces.map { it.id })
        assertEquals(listOf("w3"), groups.first { it.key == "/home/u/projects/other" }.workspaces.map { it.id })
    }

    @Test
    fun groupsAreOrderedByLabelAndRowsBySortOrder() {
        val z = ws("w1", "z", "/home/u/projects/zeta", sortOrder = 5)
        val a1 = ws("w2", "a1", "/home/u/projects/alpha", sortOrder = 2)
        val a2 = ws("w3", "a2", "/home/u/projects/alpha", sortOrder = 1)

        val groups = groupWorkspaces(listOf(z, a1, a2), home = "/home/u")

        // formatWorkdir returns …/parent/leaf for paths deeper than one level under home
        // (SessionGroupingTest: formatWorkdir_under_home_shows_last_two_segments).
        assertEquals(listOf("…/projects/alpha", "…/projects/zeta"), groups.map { it.label })
        assertEquals(listOf("w3", "w2"), groups[0].workspaces.map { it.id })
    }

    @Test
    fun archivedWorkspacesAreExcluded() {
        val live = ws("w1", "a", "/p")
        val dead = ws("w2", "b", "/p").copy(status = "archived")
        assertEquals(listOf("w1"), groupWorkspaces(listOf(live, dead), home = "/home/u").flatMap { it.workspaces.map { w -> w.id } })
    }

    @Test
    fun agentStateIsTheBusiestOfTheChatSessions() {
        val w = ws("w1", "a", "/p", views = listOf(chatView("v1", "s1"), chatView("v2", "s2")))
        val states = mapOf(
            "s1" to AgentStatus(phase = "idle", working = false),
            "s2" to AgentStatus(phase = "running", working = true),
        )
        assertEquals(WorkspaceActivity.WORKING, workspaceActivity(w, states))
    }

    @Test
    fun agentStateIsIdleWhenNoChatSessionIsWorking() {
        val w = ws("w1", "a", "/p", views = listOf(chatView("v1", "s1")))
        assertEquals(WorkspaceActivity.IDLE, workspaceActivity(w, mapOf("s1" to AgentStatus(phase = "idle", working = false))))
    }

    @Test
    fun agentStateIsNoneForAWorkspaceWithNoChatView() {
        val w = ws("w1", "a", "/p")
        assertEquals(WorkspaceActivity.NONE, workspaceActivity(w, emptyMap()))
    }

    @Test
    fun multiAgentIsTrueOnlyWithTwoOrMoreChatViews() {
        assertEquals(false, ws("w1", "a", "/p", views = listOf(chatView("v1", "s1"))).isMultiAgent())
        assertEquals(true, ws("w1", "a", "/p", views = listOf(chatView("v1", "s1"), chatView("v2", "s2"))).isMultiAgent())
    }

    @Test
    fun chatSessionIdsReadsTheStateObject() {
        val w = ws("w1", "a", "/p", views = listOf(chatView("v1", "s1"), chatView("v2", "s2")))
        assertEquals(listOf("s1", "s2"), w.chatSessionIds())
    }
}
