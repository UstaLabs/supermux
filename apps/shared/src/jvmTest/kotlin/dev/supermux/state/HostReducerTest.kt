package dev.supermux.state

import dev.supermux.net.DisplayStream
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HostReducerTest {
    /** Same SessionInfo shape as [DesktopAppStateReducerTest]. */
    private fun sessionFixture(id: String) =
        SessionInfo(id = id, name = "name-$id", workdir = "/w/$id", agent = "claude")

    private fun ws(id: String, views: List<ViewDto> = emptyList()) =
        WorkspaceDto(
            id = id, name = id, workdir = "/w", views = views,
            layout = LayoutNodeDto.Group(id = "g-$id", viewIds = views.map { it.id }, activeViewId = views.firstOrNull()?.id),
        )

    private fun view(id: String, workspaceId: String) =
        ViewDto(id = id, workspaceId = workspaceId, kind = "editor")

    @Test fun frameThatChangesNothingReturnsSameInstance() {
        val s = HostState()
        // SessionRemoved for an unknown id changes nothing → same instance (cheap no-op for collectors)
        assertSame(s, reduceHostFrame(s, ServerFrame.SessionRemoved(id = "nope")))
    }

    @Test fun sessionsReorderedAppliesOrder() {
        val a = sessionFixture("a"); val b = sessionFixture("b")
        val s = HostState(sessions = listOf(a, b))
        val out = reduceHostFrame(s, ServerFrame.SessionsReordered(orderedIds = listOf("b", "a")))
        assertEquals(0, out.sessions.first { it.id == "b" }.sortOrder)
        assertEquals(1, out.sessions.first { it.id == "a" }.sortOrder)
    }

    @Test fun snapshotPopulatesSessionsAndLogs() {
        val entry = LogEntry(id = "m1", ts = "2026-01-01T00:00:00Z", direction = "outbound", text = "hi")
        val out = reduceHostFrame(
            HostState(),
            ServerFrame.Snapshot(
                sessions = listOf(sessionFixture("s1")),
                logs = mapOf("s1" to listOf(entry)),
                agentState = mapOf("s1" to AgentStatus(phase = "idle")),
                reads = mapOf("s1" to "2026-01-01T00:00:00Z"),
            ),
        )
        assertEquals(listOf("s1"), out.sessions.map { it.id })
        assertEquals(listOf("m1"), out.messages["s1"]?.map { it.id })
        assertEquals("2026-01-01T00:00:00Z", out.lastRead["s1"])
    }

    @Test fun sessionAddedAppendsNew() {
        val out = reduceHostFrame(HostState(), ServerFrame.SessionAdded(sessionFixture("s1")))
        assertEquals(listOf("s1"), out.sessions.map { it.id })
    }

    @Test fun sessionAddedReplacesExistingAndBackfills() {
        val existing = sessionFixture("s1").copy(status = "running", repo_root = "/repo")
        val incoming = sessionFixture("s1").copy(status = null, repo_root = null, model = "opus")
        val out = reduceHostFrame(HostState(sessions = listOf(existing)), ServerFrame.SessionAdded(incoming))
        assertEquals(1, out.sessions.size)
        assertEquals("running", out.sessions.single().status)
        assertEquals("/repo", out.sessions.single().repo_root)
        assertEquals("opus", out.sessions.single().model)
    }

    @Test fun agentErrorStoresAndAgentStateClearsUnlessDead() {
        val err = ServerFrame.AgentError(session = "s1", errorType = "auth", errorMessage = "nope")
        val withErr = reduceHostFrame(HostState(), err)
        assertEquals(err, withErr.agentErrors["s1"])
        val cleared = reduceHostFrame(withErr, ServerFrame.AgentState(session = "s1", phase = "idle", state = "idle"))
        assertEquals(emptyMap(), cleared.agentErrors)
        val deadKeeps = reduceHostFrame(withErr, ServerFrame.AgentState(session = "s1", phase = "dead", state = "dead"))
        assertEquals(err, deadKeeps.agentErrors["s1"])
    }

    @Test fun sessionRemovedDropsKnownId() {
        val s = HostState(sessions = listOf(sessionFixture("s1")), bgTasks = mapOf("s1" to emptyList()))
        val out = reduceHostFrame(s, ServerFrame.SessionRemoved(id = "s1"))
        assertEquals(emptyList(), out.sessions)
        assertEquals(emptyMap(), out.bgTasks)
    }

    @Test fun workspaceRemovedArchives() {
        val s = HostState(workspaces = listOf(ws("w1"), ws("w2")))
        val out = reduceHostFrame(s, ServerFrame.WorkspaceRemoved("w1"))
        assertEquals(listOf("w2"), out.workspaces.map { it.id })
        assertEquals(listOf("w1"), out.archivedWorkspaces.map { it.id })
        assertEquals("archived", out.archivedWorkspaces.single().status)
    }

    @Test fun viewMovedMovesView() {
        val s = HostState(workspaces = listOf(ws("w1", views = listOf(view("v1", "w1"))), ws("w2")))
        val out = reduceHostFrame(s, ServerFrame.ViewMoved("v1", "w1", "w2"))
        val byId = out.workspaces.associateBy { it.id }
        assertEquals(emptyList(), byId["w1"]!!.views.map { it.id })
        assertEquals(listOf("v1"), byId["w2"]!!.views.map { it.id })
        assertEquals("w2", byId["w2"]!!.views[0].workspaceId)
    }

    @Test fun messageAppendPrunesOptimisticLocalEcho() {
        val echo = LogEntry(id = "local-0-1", ts = "2026-01-01T00:00:00Z", direction = "inbound", text = "hello world")
        val s = HostState(messages = mapOf("s1" to listOf(echo)))
        val out = reduceHostFrame(
            s,
            ServerFrame.MessageAppend(
                session = "s1",
                entry = LogEntry(id = "real1", ts = "2026-01-01T00:00:01Z", direction = "inbound", text = "hello world"),
            ),
        )
        assertEquals(listOf("real1"), out.messages["s1"]!!.map { it.id })
    }

    @Test fun sessionReadAdvancesLastRead() {
        val s = HostState()
        val a = reduceHostFrame(s, ServerFrame.SessionRead(session = "s1", lastReadAt = "2026-01-01T00:00:00Z"))
        assertEquals("2026-01-01T00:00:00Z", a.lastRead["s1"])
        val b = reduceHostFrame(a, ServerFrame.SessionRead(session = "s1", lastReadAt = "2026-01-01T00:05:00Z"))
        assertEquals("2026-01-01T00:05:00Z", b.lastRead["s1"])
        val c = reduceHostFrame(b, ServerFrame.SessionRead(session = "s1", lastReadAt = "2026-01-01T00:01:00Z"))
        assertEquals("2026-01-01T00:05:00Z", c.lastRead["s1"])
    }

    @Test fun finishJobFrameUpdatesJobAndSession() {
        val s = HostState(sessions = listOf(sessionFixture("s1")))
        val job = FinishJobDto(sessionId = "s1", action = "merge", status = "running", stage = "merging")
        val out = reduceHostFrame(s, ServerFrame.FinishJobFrame(session = "s1", job = job))
        assertEquals("merging", out.finishJobs["s1"]?.stage)
        assertEquals(job, out.sessions.single().finish_job)
    }

    @Test fun lspInstallProgressAppends() {
        val a = reduceHostFrame(HostState(), ServerFrame.LspInstallProgress(serverId = "pyright", line = "a"))
        val b = reduceHostFrame(a, ServerFrame.LspInstallProgress(serverId = "pyright", line = "b"))
        assertEquals(listOf("a", "b"), b.lspInstallLog["pyright"])
    }

    @Test fun displayAddedReplacesById() {
        val d1 = DisplayStream(id = "d1", sessionName = "demo", provider = "linux-xvfb", transport = "vnc", status = "running")
        val d1b = d1.copy(status = "errored")
        val a = reduceHostFrame(HostState(), ServerFrame.DisplayAdded(d1))
        val b = reduceHostFrame(a, ServerFrame.DisplayAdded(d1b))
        assertEquals(1, b.displays.size)
        assertEquals("errored", b.displays.single().status)
    }
}
