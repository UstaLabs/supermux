package dev.supermux.android.session

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.formatWorkdir
import dev.supermux.workspace.WorkspaceActivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceListTest {

    private fun chatView(id: String, sessionId: String, workspaceId: String = "w") = ViewDto(
        id = id,
        workspaceId = workspaceId,
        kind = "chat",
        state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
    )

    private fun termView(id: String, workspaceId: String = "w") = ViewDto(
        id = id,
        workspaceId = workspaceId,
        kind = "terminal",
    )

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
    ) = WorkspaceDto(
        id = id,
        name = name,
        status = status,
        workdir = workdir,
        repoRoot = repoRoot,
        activeViewId = activeViewId,
        primarySessionId = primarySessionId,
        archivedAt = archivedAt,
        views = views,
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
        assertTrue(row.unread)
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

    @Test
    fun restoreWorkspace_movesFromArchivedToLive() {
        val dead = ws(id = "w1", status = "archived", archivedAt = "t")
        val other = ws(id = "w2", status = "archived")
        val next = applyRestoreWorkspace(
            live = emptyList(),
            archived = listOf(dead, other),
            id = "w1",
        )
        assertEquals(listOf("w1"), next.live.map { it.id })
        assertEquals("active", next.live.single().status)
        assertNull(next.live.single().archivedAt)
        assertEquals(listOf("w2"), next.archived.map { it.id })
    }

    @Test
    fun archiveWorkspace_movesFromLiveToArchived() {
        val live = ws(id = "w1")
        val next = applyArchiveWorkspace(listOf(live), emptyList(), "w1")
        assertTrue(next.live.isEmpty())
        assertEquals("archived", next.archived.single().status)
    }
}
