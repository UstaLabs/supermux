package dev.supermux.ui.session

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.session.formatWorkdir
import dev.supermux.workspace.WorkspaceActivity
import dev.supermux.workspace.chatSessionIds
import dev.supermux.workspace.isMultiAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.supermux.state.SidebarReorderKind
import dev.supermux.state.sidebarReorderKind

class WorkspaceListTest {

    private fun chatView(id: String, sessionId: String, workspaceId: String = "w") =
        workspaceChatView(id, sessionId, workspaceId)

    private fun termView(id: String, workspaceId: String = "w") = workspaceTermView(id, workspaceId)

    private fun ws(
        id: String = "w1",
        name: String = "Fix it",
        workdir: String = "/home/u/projects/app",
        repoRoot: String? = null,
        views: List<ViewDto> = emptyList(),
        activeViewId: String? = null,
        primarySessionId: String? = null,
        status: String = "active",
        archivedAt: String? = null,
        sortOrder: Int = 0,
    ) = workspaceDto(
        id = id,
        name = name,
        workdir = workdir,
        repoRoot = repoRoot,
        views = views,
        activeViewId = activeViewId,
        primarySessionId = primarySessionId,
        status = status,
        archivedAt = archivedAt,
        sortOrder = sortOrder,
    )

    private fun session(
        id: String,
        name: String = id,
        git: GitLiteStatusDto? = null,
        role: String? = null,
    ) = SessionInfo(
        id = id,
        name = name,
        workdir = "/home/u/projects/app",
        agent = "claude",
        git = git,
        role = role,
    )

    @Test
    fun resolveOpenSession_prefersActiveChatView() {
        val w = ws(
            views = listOf(
                chatView("v1", "s1"),
                chatView("v2", "s2"),
                termView("v3"),
            ),
            activeViewId = "v2",
            primarySessionId = "s1",
        )
        assertEquals("s2", resolveWorkspaceOpenSessionId(w))
    }

    @Test
    fun resolveOpenSession_skipsNonChatActiveView_usesFirstChat() {
        val w = ws(
            views = listOf(termView("v0"), chatView("v1", "s1"), chatView("v2", "s2")),
            activeViewId = "v0",
            primarySessionId = "primary",
        )
        assertEquals("s1", resolveWorkspaceOpenSessionId(w))
    }

    @Test
    fun resolveOpenSession_fallsBackToPrimaryWhenNoChatView() {
        val w = ws(views = listOf(termView("v0")), activeViewId = "v0", primarySessionId = "primary")
        assertEquals("primary", resolveWorkspaceOpenSessionId(w))
    }

    @Test
    fun rowModel_namePathGitActivityMultiAgentUnreadChildren() {
        val git = GitLiteStatusDto(compareRef = "main", dirty = 2)
        val w = ws(
            name = "Feature",
            workdir = "/home/u/.mux/wt/a",
            repoRoot = "/home/u/projects/app",
            views = listOf(chatView("v1", "s1"), chatView("v2", "s2")),
            primarySessionId = "s1",
        )
        val sessions = mapOf(
            "s1" to session("s1", name = "Agent A", git = git),
            "s2" to session("s2", name = "Agent B"),
        )
        val agent = mapOf(
            "s1" to AgentStatus(phase = "idle", working = false),
            "s2" to AgentStatus(phase = "running", working = true),
        )
        val last = mapOf(
            "s2" to LogEntry(id = "m1", ts = "2026-08-01T12:00:00.000Z", direction = "out", text = "hi"),
        )
        val lastRead = mapOf("s1" to "2026-08-01T11:00:00.000Z")

        val row = deriveWorkspaceRow(
            w = w,
            sessionsById = sessions,
            agentState = agent,
            lastBySession = last,
            lastRead = lastRead,
            home = "/home/u",
            selectedSessionId = null,
        )

        assertEquals("Feature", row.name)
        assertEquals(formatWorkdir("/home/u/projects/app", "/home/u"), row.pathLabel)
        assertEquals(git, row.git)
        assertEquals(WorkspaceActivity.WORKING, row.activity)
        assertTrue(row.multiAgent)
        assertFalse(row.unread)
        assertEquals(listOf("s1", "s2"), row.children.map { it.sessionId })
        assertEquals(listOf("Agent A", "Agent B"), row.children.map { it.name })
        assertEquals("s1", row.primarySessionId)
    }

    @Test
    fun rowModel_singleChatHasNoChildren() {
        val w = ws(views = listOf(chatView("v1", "s1")), primarySessionId = "s1")
        val row = deriveWorkspaceRow(
            w = w,
            sessionsById = mapOf("s1" to session("s1")),
            agentState = emptyMap(),
            lastBySession = emptyMap(),
            lastRead = emptyMap(),
            home = "/home/u",
            selectedSessionId = null,
        )
        assertFalse(row.multiAgent)
        assertEquals(emptyList(), row.children)
    }

    @Test
    fun unread_isAnyChatSessionUnread_notJustPrimary() {
        val w = ws(
            views = listOf(chatView("v1", "s1"), chatView("v2", "s2")),
            primarySessionId = "s1",
        )
        val last = mapOf(
            "s2" to LogEntry(id = "m", ts = "2026-08-01T12:00:00.000Z", direction = "out"),
        )
        val lastRead = mapOf(
            "s1" to "2026-08-01T12:00:00.000Z",
            "s2" to "2026-08-01T11:00:00.000Z",
        )
        val row = deriveWorkspaceRow(
            w, mapOf("s1" to session("s1"), "s2" to session("s2")),
            emptyMap(), last, lastRead, "/home/u", selectedSessionId = "s1",
        )
        assertTrue(row.unread)
    }

    @Test
    fun unread_isFalseWhenThatChatIsWorking() {
        val w = ws(
            views = listOf(chatView("v1", "s1"), chatView("v2", "s2")),
            primarySessionId = "s1",
        )
        val last = mapOf(
            "s2" to LogEntry(id = "m", ts = "2026-08-01T12:00:00.000Z", direction = "out"),
        )
        val lastRead = mapOf("s2" to "2026-08-01T11:00:00.000Z")
        val agent = mapOf("s2" to AgentStatus(phase = "running", working = true))
        val row = deriveWorkspaceRow(
            w, mapOf("s1" to session("s1"), "s2" to session("s2")),
            agent, last, lastRead, "/home/u", selectedSessionId = null,
        )
        assertFalse(row.unread)
    }

    @Test
    fun archivedFold_isIndependentOfLiveWorkspaces() {
        assertFalse(sessionListShowsArchivedWorkspaceFold(emptyList()))
        assertTrue(sessionListShowsArchivedWorkspaceFold(listOf(ws(id = "arch", status = "archived"))))
    }

    @Test
    fun sidebarReorderKind_emptyLiveWorkspacesUsesSessions() {
        assertEquals(SidebarReorderKind.SESSIONS, sidebarReorderKind(emptyList()))
        assertEquals(SidebarReorderKind.WORKSPACES, sidebarReorderKind(listOf(ws())))
    }

    @Test
    fun archivedRow_namePathDate() {
        val w = ws(
            name = "Old",
            workdir = "/home/u/projects/app",
            status = "archived",
            archivedAt = "2026-08-02T00:00:00Z",
        )
        val row = deriveArchivedWorkspaceRow(w, home = "/home/u")
        assertEquals("Old", row.name)
        assertEquals(formatWorkdir("/home/u/projects/app", "/home/u"), row.pathLabel)
        assertEquals("2026-08-02T00:00:00Z", row.archivedAt)
    }

    /**
     * Desktop's `WorkspaceListEntry` computed activity / primary session / git / multi-agent
     * children inline; cluster F2 pointed it at [deriveWorkspaceRow]. This pins the two against
     * each other so the swap stayed byte-identical for the sidebar.
     */
    @Test
    fun rowModel_matchesTheInlineDerivationDesktopUsedToDo() {
        val git = GitLiteStatusDto(compareRef = "main", dirty = 1)
        val rows = listOf(
            ws(id = "w1", views = listOf(chatView("v1", "s1")), primarySessionId = "s1"),
            ws(
                id = "w2",
                views = listOf(chatView("v1", "s1", "w2"), chatView("v2", "s2", "w2")),
                primarySessionId = null,
            ),
            ws(id = "w3", views = listOf(termView("v1", "w3"))),
        )
        val sessions = mapOf(
            "s1" to session("s1", git = git),
            "s2" to session("s2"),
        )
        val agent = mapOf("s2" to AgentStatus(phase = "running", working = true))

        for (w in rows) {
            // The old inline block, verbatim.
            val inlineActivity = dev.supermux.workspace.workspaceActivity(w, agent)
            val inlinePrimarySid = w.primarySessionId
                ?: w.chatSessionIds().firstOrNull()
            val inlineGit = inlinePrimarySid?.let { sessions[it] }?.git
            val inlineMulti = w.isMultiAgent()
            val inlineChildren =
                if (inlineMulti) w.chatSessionIds() else emptyList()

            val model = deriveWorkspaceRow(
                w = w,
                sessionsById = sessions,
                agentState = agent,
                lastBySession = emptyMap(),
                lastRead = emptyMap(),
                home = "",
                selectedSessionId = null,
            )

            assertEquals(inlineActivity, model.activity, w.id)
            assertEquals(inlinePrimarySid, model.primarySessionId, w.id)
            assertEquals(inlineGit, model.git, w.id)
            assertEquals(inlineMulti, model.multiAgent, w.id)
            assertEquals(inlineChildren, model.children.map { it.sessionId }, w.id)
        }
    }

}
