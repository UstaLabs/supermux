package dev.supermux.workspace

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.PA_GROUP_KEY
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
    fun groupArchivedWorkspaces_onlyArchived_byProject() {
        val live = ws("w1", "a", "/home/u/projects/app")
        val deadA = ws("w2", "old", "/home/u/projects/app").copy(status = "archived", archivedAt = "2026-08-02T00:00:00Z")
        val deadB = ws("w3", "older", "/home/u/projects/app").copy(status = "archived", archivedAt = "2026-08-01T00:00:00Z")
        val other = ws("w4", "gone", "/home/u/projects/other").copy(status = "archived", archivedAt = "2026-08-03T00:00:00Z")
        val groups = groupArchivedWorkspaces(listOf(live, deadA, deadB, other), home = "/home/u")
        assertEquals(listOf("w2", "w3"), groups.first { it.key == "/home/u/projects/app" }.workspaces.map { it.id })
        assertEquals(listOf("w4"), groups.first { it.key == "/home/u/projects/other" }.workspaces.map { it.id })
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

    @Test
    fun personalAssistantWorkspacesPinInTheirOwnGroup() {
        // WorkspaceDto has no role — the caller looks up the primary session's role
        // (SessionInfo.role == "personal_assistant") and passes isPersonalAssistant.
        val pa = ws(
            "w-pa", "My Assistant", "/home/u/.mux/personal",
            views = listOf(chatView("v1", "s-pa", "w-pa")),
        ).copy(primarySessionId = "s-pa")
        val proj = ws("w1", "Fix Renaming", "/home/u/projects/app")

        val groups = groupWorkspaces(listOf(pa, proj), home = "/home/u") { w ->
            w.primarySessionId == "s-pa"
        }

        assertEquals(2, groups.size)
        assertEquals(PA_GROUP_KEY, groups[0].key)
        assertEquals("Personal Assistants", groups[0].label)
        assertEquals(listOf("w-pa"), groups[0].workspaces.map { it.id })
        assertEquals(listOf("w1"), groups[1].workspaces.map { it.id })
    }

    @Test
    fun personalAssistantGroupIsAbsentWhenNobodyIsAPa() {
        // Default isPersonalAssistant = { false }: existing callers and empty PA
        // fleets stay on plain project groups only.
        val proj = ws("w1", "a", "/home/u/projects/app")
        val groups = groupWorkspaces(listOf(proj), home = "/home/u")
        assertEquals(1, groups.size)
        assertEquals(false, groups.any { it.key == PA_GROUP_KEY })
    }

    // ── Persistent projects ──────────────────────────────────────────────────

    private fun proj(id: String, name: String, sortOrder: Int = 0, hostId: String = "") =
        ProjectRef(hostId, ProjectDto(id = id, name = name, sortOrder = sortOrder))

    @Test
    fun workspacesInDifferentReposWithTheSameProjectShareOneGroup() {
        val a = ws("w1", "a", "/home/u/projects/app").copy(projectId = "p1")
        val b = ws("w2", "b", "/home/u/work/app-fork", repoRoot = "/home/u/work/app-fork").copy(projectId = "p1")

        val groups = groupWorkspaces(listOf(a, b), home = "/home/u", projects = listOf(proj("p1", "Supermux")))

        val g = groups.single()
        assertEquals("Supermux", g.label)
        assertEquals(projectGroupKey("", "p1"), g.key)
        assertEquals("p1", g.project?.id)
        assertEquals("", g.hostId)
        assertEquals(listOf("w1", "w2"), g.workspaces.map { it.id })
    }

    @Test
    fun anEmptyProjectYieldsAnEmptyGroup() {
        val groups = groupWorkspaces(emptyList(), home = "/home/u", projects = listOf(proj("p1", "Empty")))
        assertEquals(listOf("Empty"), groups.map { it.label })
        assertEquals(emptyList(), groups.single().workspaces)
    }

    @Test
    fun projectOrderFollowsSortOrderNotName() {
        val groups = groupWorkspaces(
            emptyList(), home = "/home/u",
            projects = listOf(proj("p1", "Alpha", sortOrder = 2), proj("p2", "Zeta", sortOrder = 0), proj("p3", "Mid", sortOrder = 1)),
        )
        assertEquals(listOf("Zeta", "Mid", "Alpha"), groups.map { it.label })
    }

    @Test
    fun projectOrderTieBreaksByNameWhenSortOrderTies() {
        val groups = groupWorkspaces(
            emptyList(), home = "/home/u",
            projects = listOf(proj("p1", "Zeta", sortOrder = 0), proj("p2", "Alpha", sortOrder = 0)),
        )
        assertEquals(listOf("Alpha", "Zeta"), groups.map { it.label })
    }

    @Test
    fun projectOrderTieBreaksByIdWhenSortOrderAndNameTie() {
        val groups = groupWorkspaces(
            emptyList(), home = "/home/u",
            projects = listOf(proj("p2", "Same", sortOrder = 0), proj("p1", "Same", sortOrder = 0)),
        )
        assertEquals(listOf("p1", "p2"), groups.map { it.project?.id })
    }

    @Test
    fun rowsWithinAProjectGroupFollowSortOrderThenId() {
        // Mixed sortOrder: w3 (0) sorts first; w1/w2 tie at 1 and break by id.
        val a = ws("w2", "a", "/p/app", sortOrder = 1).copy(projectId = "p1")
        val b = ws("w1", "b", "/p/app", sortOrder = 1).copy(projectId = "p1")
        val c = ws("w3", "c", "/p/app", sortOrder = 0).copy(projectId = "p1")

        val groups = groupWorkspaces(listOf(a, b, c), home = "/home/u", projects = listOf(proj("p1", "App")))

        assertEquals(listOf("w3", "w1", "w2"), groups.single().workspaces.map { it.id })
    }

    @Test
    fun anUnknownProjectIdFallsBackToPathGroupingAfterProjects() {
        val known = ws("w1", "a", "/home/u/projects/app").copy(projectId = "p1")
        val orphan = ws("w2", "b", "/home/u/projects/other").copy(projectId = "gone")

        val groups = groupWorkspaces(listOf(orphan, known), home = "/home/u", projects = listOf(proj("p1", "Zzz")))

        assertEquals(listOf(projectGroupKey("", "p1"), "/home/u/projects/other"), groups.map { it.key })
        assertEquals(listOf("w2"), groups[1].workspaces.map { it.id })
        assertEquals(null, groups[1].project)
    }

    @Test
    fun theSameProjectIdOnTwoHostsStaysTwoGroups() {
        val a = ws("w1", "a", "/p/app").copy(projectId = "p")
        val b = ws("w2", "b", "/p/app").copy(projectId = "p")
        val host = mapOf("w1" to "h1", "w2" to "h2")

        val groups = groupWorkspaces(
            listOf(a, b), home = "/home/u",
            projects = listOf(proj("p", "App", hostId = "h1"), proj("p", "App", hostId = "h2")),
            hostOf = { host.getValue(it.id) },
        )

        assertEquals(listOf(projectGroupKey("h1", "p"), projectGroupKey("h2", "p")), groups.map { it.key })
        assertEquals(listOf("w1"), groups[0].workspaces.map { it.id })
        assertEquals(listOf("w2"), groups[1].workspaces.map { it.id })
    }

    @Test
    fun personalAssistantsStayFirstEvenWithAProject() {
        val pa = ws("w1", "pa", "/home/u").copy(projectId = "p1")
        val groups = groupWorkspaces(
            listOf(pa), home = "/home/u", isPersonalAssistant = { it.id == "w1" },
            projects = listOf(proj("p1", "Home")),
        )
        assertEquals(listOf(PA_GROUP_KEY, projectGroupKey("", "p1")), groups.map { it.key })
        assertEquals(listOf("w1"), groups[0].workspaces.map { it.id })
        assertEquals(emptyList(), groups[1].workspaces)
    }

    @Test
    fun fleetProjectsStayGroupedByHostRankNotInterleaved() {
        // h1's two projects (sortOrder 0, 1) must both precede h2's, even though h2's
        // project #0 has a lower sortOrder than h1's project #1.
        val groups = groupWorkspaces(
            emptyList(), home = "/home/u",
            projects = listOf(
                proj("h1p0", "H1-Zero", sortOrder = 0, hostId = "h1"),
                proj("h1p1", "H1-One", sortOrder = 1, hostId = "h1"),
                proj("h2p0", "H2-Zero", sortOrder = 0, hostId = "h2"),
                proj("h2p1", "H2-One", sortOrder = 1, hostId = "h2"),
            ),
        )
        assertEquals(
            listOf(
                projectGroupKey("h1", "h1p0"), projectGroupKey("h1", "h1p1"),
                projectGroupKey("h2", "h2p0"), projectGroupKey("h2", "h2p1"),
            ),
            groups.map { it.key },
        )
    }

    @Test
    fun archivedFleetProjectsStayGroupedByHostRankNotInterleaved() {
        val a1 = ws("w1", "a1", "/h1/app").copy(status = "archived", archivedAt = "2026-01-01", projectId = "h1p0")
        val a2 = ws("w2", "a2", "/h1/app2").copy(status = "archived", archivedAt = "2026-01-01", projectId = "h1p1")
        val b1 = ws("w3", "b1", "/h2/app").copy(status = "archived", archivedAt = "2026-01-01", projectId = "h2p0")
        val b2 = ws("w4", "b2", "/h2/app2").copy(status = "archived", archivedAt = "2026-01-01", projectId = "h2p1")
        val host = mapOf("w1" to "h1", "w2" to "h1", "w3" to "h2", "w4" to "h2")

        val groups = groupArchivedWorkspaces(
            listOf(a1, a2, b1, b2), home = "/home/u",
            projects = listOf(
                proj("h1p0", "H1-Zero", sortOrder = 0, hostId = "h1"),
                proj("h1p1", "H1-One", sortOrder = 1, hostId = "h1"),
                proj("h2p0", "H2-Zero", sortOrder = 0, hostId = "h2"),
                proj("h2p1", "H2-One", sortOrder = 1, hostId = "h2"),
            ),
            hostOf = { host.getValue(it.id) },
        )

        assertEquals(
            listOf(
                projectGroupKey("h1", "h1p0"), projectGroupKey("h1", "h1p1"),
                projectGroupKey("h2", "h2p0"), projectGroupKey("h2", "h2p1"),
            ),
            groups.map { it.key },
        )
    }

    @Test
    fun archivedGroupingResolvesProjectsAndDropsEmptyOnes() {
        val a = ws("w1", "a", "/home/u/projects/app").copy(status = "archived", archivedAt = "2026-01-01", projectId = "p1")
        val b = ws("w2", "b", "/home/u/work/fork").copy(status = "archived", archivedAt = "2026-02-01", projectId = "p1")
        val c = ws("w3", "c", "/home/u/projects/other").copy(status = "archived", archivedAt = "2026-01-01")

        val groups = groupArchivedWorkspaces(
            listOf(a, b, c), home = "/home/u",
            projects = listOf(proj("p1", "App", sortOrder = 1), proj("p2", "Empty", sortOrder = 0)),
        )

        assertEquals(listOf(projectGroupKey("", "p1"), "/home/u/projects/other"), groups.map { it.key })
        assertEquals("App", groups[0].label)
        assertEquals(listOf("w2", "w1"), groups[0].workspaces.map { it.id })
    }

    // ── Live visibility of empty project groups ──────────────────────────────

    private fun dead(id: String, projectId: String) =
        ws(id, id, "/p/$id").copy(status = "archived", archivedAt = "2026-01-01", projectId = projectId)

    @Test
    fun aProjectWithAnActiveWorkspaceIsShownEvenIfItAlsoHasArchivedOnes() {
        val live = ws("w1", "a", "/p/app").copy(projectId = "p1")
        val groups = groupWorkspaces(
            listOf(live), home = "/home/u",
            projects = listOf(proj("p1", "App")),
            archived = listOf(dead("w2", "p1")),
        )
        assertEquals(listOf(projectGroupKey("", "p1")), groups.map { it.key })
        assertEquals(listOf("w1"), groups.single().workspaces.map { it.id })
    }

    @Test
    fun aNeverUsedProjectIsShown() {
        val groups = groupWorkspaces(
            emptyList(), home = "/home/u",
            projects = listOf(proj("p1", "Fresh")),
            archived = listOf(dead("w9", "other")),
        )
        assertEquals(listOf("Fresh"), groups.map { it.label })
    }

    @Test
    fun aProjectWhoseWorkspacesAreAllArchivedIsHiddenFromTheLiveList() {
        val groups = groupWorkspaces(
            emptyList(), home = "/home/u",
            projects = listOf(proj("p1", "Old"), proj("p2", "Fresh")),
            archived = listOf(dead("w1", "p1"), dead("w2", "p1")),
        )
        assertEquals(listOf("Fresh"), groups.map { it.label })
    }

    @Test
    fun archivedRowsInTheLiveInputAlsoCountAsHistory() {
        val groups = groupWorkspaces(
            listOf(dead("w1", "p1")), home = "/home/u",
            projects = listOf(proj("p1", "Old")),
            archived = listOf(dead("w1", "p1")),
        )
        assertEquals(emptyList(), groups)
    }

    @Test
    fun archivedHistoryIsHostQualified() {
        // p on h1 was used and archived; the same id on h2 was never used — only h2's stays.
        val host = mapOf("w1" to "h1")
        val groups = groupWorkspaces(
            emptyList(), home = "/home/u",
            projects = listOf(proj("p", "App", hostId = "h1"), proj("p", "App", hostId = "h2")),
            hostOf = { host[it.id] ?: "" },
            archived = listOf(dead("w1", "p")),
        )
        assertEquals(listOf(projectGroupKey("h2", "p")), groups.map { it.key })
    }

    @Test
    fun withoutArchivedInfoEmptyProjectsStayVisible() {
        val groups = groupWorkspaces(emptyList(), home = "/home/u", projects = listOf(proj("p1", "Any")))
        assertEquals(listOf("Any"), groups.map { it.label })
    }
}
