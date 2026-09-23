package dev.supermux.state

import dev.supermux.net.DisplayStream
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.PromptRequest
import dev.supermux.proto.PromptRequestOption
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    // ---- agent_state: dead tracking (iOS AgentDeadStateTests parity) -------------------------

    /**
     * `state == "dead"` is the badge that tells the user their agent process is gone, so it has to
     * be observable from the reduced state — and a later live frame has to CLEAR it, otherwise a
     * recovered agent keeps a dead badge for the rest of the connection.
     */
    @Test fun agentStateDeadIsObservableAndClearedByALaterIdleFrame() {
        val dead = reduceHostFrame(
            HostState(),
            ServerFrame.AgentState(session = "s1", phase = "stalled", state = "dead"),
        )
        assertEquals("dead", dead.agentState["s1"]?.state)

        val idle = reduceHostFrame(dead, ServerFrame.AgentState(session = "s1", phase = "idle", state = "idle"))
        assertEquals("idle", idle.agentState["s1"]?.state)
    }

    /** "working" is not "dead" — the two flags drive different chrome and must not collapse. */
    @Test fun agentStateWorkingIsNotDead() {
        val out = reduceHostFrame(
            HostState(),
            ServerFrame.AgentState(session = "s1", phase = "running", state = "working", working = true, detail = "running"),
        )
        assertEquals("working", out.agentState["s1"]?.state)
        assertEquals(true, out.agentState["s1"]?.working)
    }

    /** A snapshot is the reconnect path: per-session dead/idle has to survive it, not just live frames. */
    @Test fun snapshotCarriesPerSessionDeadAndIdleState() {
        val out = reduceHostFrame(
            HostState(),
            ServerFrame.Snapshot(
                agentState = mapOf(
                    "s1" to AgentStatus(phase = "idle", state = "dead"),
                    "s2" to AgentStatus(phase = "idle", state = "idle"),
                ),
            ),
        )
        assertEquals("dead", out.agentState["s1"]?.state)
        assertEquals("idle", out.agentState["s2"]?.state)
    }

    /**
     * Kill/archive removes the session — but a resume REUSES the same id over a continuous WS with
     * no corrective agent_state frame, so a dead flag surviving removal would misreport the healthy
     * resumed session. The whole per-session agent family has to be pruned with the session.
     */
    @Test fun sessionRemovedPrunesAgentStateAndErrors() {
        val s = reduceHostFrame(
            HostState(sessions = listOf(sessionFixture("s1"), sessionFixture("s2"))),
            ServerFrame.AgentState(session = "s1", phase = "stalled", state = "dead"),
        ).let { reduceHostFrame(it, ServerFrame.AgentError(session = "s1", errorType = "auth")) }
            .let { reduceHostFrame(it, ServerFrame.AgentState(session = "s2", phase = "idle", state = "idle")) }
        assertEquals("dead", s.agentState["s1"]?.state)

        val out = reduceHostFrame(s, ServerFrame.SessionRemoved(id = "s1"))
        assertNull(out.agentState["s1"])
        assertNull(out.agentErrors["s1"])
        // …and only that session's entries go.
        assertEquals("idle", out.agentState["s2"]?.state)
    }

    /** An id with nothing but a stale agent entry still has to be prunable, not short-circuited. */
    @Test fun sessionRemovedForAnIdKnownOnlyByItsAgentStatePrunesIt() {
        val s = HostState(agentState = mapOf("ghost" to AgentStatus(phase = "idle", state = "dead")))
        val out = reduceHostFrame(s, ServerFrame.SessionRemoved(id = "ghost"))
        assertEquals(emptyMap(), out.agentState)
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

    private fun prompt(id: String = "r1", label: String = "Allow once") = PromptRequest(
        requestId = id,
        kind = "permission",
        title = "Bash",
        body = "ls",
        options = listOf(
            PromptRequestOption(id = "allow_once", label = label, kind = "allow_once"),
            PromptRequestOption(id = "reject_once", label = "Reject", kind = "reject_once"),
        ),
    )

    @Test fun snapshotSeedsRequests() {
        val req = prompt()
        val out = reduceHostFrame(
            HostState(),
            ServerFrame.Snapshot(requests = mapOf("s1" to listOf(req))),
        )
        assertEquals(listOf("r1"), out.requests["s1"]?.map { it.requestId })
    }

    @Test fun requestOpenAppendsAndDedupesByRequestId() {
        val first = reduceHostFrame(HostState(), ServerFrame.RequestOpen("s1", prompt()))
        assertEquals(1, first.requests["s1"]?.size)
        val dup = reduceHostFrame(first, ServerFrame.RequestOpen("s1", prompt()))
        assertEquals(1, dup.requests["s1"]?.size)
        val second = reduceHostFrame(dup, ServerFrame.RequestOpen("s1", prompt("r2")))
        assertEquals(listOf("r1", "r2"), second.requests["s1"]?.map { it.requestId })
    }

    @Test fun requestClosedRemovesAndRecordsAnsweredLine() {
        val open = reduceHostFrame(HostState(), ServerFrame.RequestOpen("s1", prompt()))
        val closed = reduceHostFrame(open, ServerFrame.RequestClosed("s1", "r1", "answered", "Allow always"))
        assertEquals(null, closed.requests["s1"])
        assertEquals("answered: Allow always", closed.messages["s1"]?.last()?.text)
        assertEquals("Allow always", closed.closedRequests["s1"]?.single()?.answerLabel)
    }

    /** The bug this replaced: the line was guessed from the first allow option, so a
     *  rejection still read "answered: Allow once". */
    @Test fun requestClosedLineStatesTheRejection() {
        val open = reduceHostFrame(HostState(), ServerFrame.RequestOpen("s1", prompt()))
        val closed = reduceHostFrame(open, ServerFrame.RequestClosed("s1", "r1", "answered", "Reject once"))
        assertEquals("answered: Reject once", closed.messages["s1"]?.last()?.text)
    }

    /** An old broker sends no label: say "answered", never a guess. */
    @Test fun requestClosedWithoutLabelSaysOnlyAnswered() {
        val open = reduceHostFrame(HostState(), ServerFrame.RequestOpen("s1", prompt()))
        val closed = reduceHostFrame(open, ServerFrame.RequestClosed("s1", "r1", "answered"))
        assertEquals("answered", closed.messages["s1"]?.last()?.text)
    }

    @Test fun closedRequestReceiptDropsWhenTheTranscriptMovesOn() {
        val open = reduceHostFrame(HostState(), ServerFrame.RequestOpen("s1", prompt()))
        val closed = reduceHostFrame(open, ServerFrame.RequestClosed("s1", "r1", "answered", "Allow once"))
        assertEquals(1, closed.closedRequests["s1"]?.size)
        val moved = reduceHostFrame(
            closed,
            ServerFrame.MessageAppend("s1", LogEntry(id = "m9", ts = "2024-01-01T00:00:00Z", direction = "outbound", text = "ok")),
        )
        assertEquals(null, moved.closedRequests["s1"])
    }

    @Test fun requestClosedExpiredAndCancelledLines() {
        val open = reduceHostFrame(HostState(), ServerFrame.RequestOpen("s1", prompt()))
        val expired = reduceHostFrame(open, ServerFrame.RequestClosed("s1", "r1", "expired"))
        assertEquals("expired", expired.messages["s1"]?.last()?.text)
        val open2 = reduceHostFrame(HostState(), ServerFrame.RequestOpen("s1", prompt()))
        val cancelled = reduceHostFrame(open2, ServerFrame.RequestClosed("s1", "r1", "cancelled"))
        assertEquals("cancelled", cancelled.messages["s1"]?.last()?.text)
    }

    @Test fun sessionStatePatchesPrompts() {
        val s = HostState(sessions = listOf(sessionFixture("s1")))
        val out = reduceHostFrame(s, ServerFrame.SessionState(session = "s1", permissionMode = "ask"))
        assertEquals("ask", out.sessions.single().permissionMode)
    }
}
