package dev.supermux.state

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.advanceLastRead

fun reduceHostFrame(state: HostState, frame: ServerFrame): HostState = when (frame) {
    is ServerFrame.Snapshot -> {
        val lastRead = if (frame.reads.isEmpty()) {
            state.lastRead
        } else {
            val next = state.lastRead.toMutableMap()
            for ((id, ts) in frame.reads) {
                next[id] = advanceLastRead(next[id], ts)
            }
            next
        }
        // An old broker sends neither field: leave the dtos' own projectId (null) alone.
        val catalogKnown = frame.projects.isNotEmpty() || frame.projectMembership.isNotEmpty()
        state.copy(
            sessions = frame.sessions,
            workspaces = if (catalogKnown) applyMembership(frame.workspaces, frame.projectMembership) else frame.workspaces,
            archivedWorkspaces = if (catalogKnown) {
                applyMembership(frame.archivedWorkspaces, frame.projectMembership)
            } else {
                frame.archivedWorkspaces
            },
            projects = frame.projects,
            projectCatalogKnown = catalogKnown,
            messages = snapshotMessages(state, frame),
            completeLogs = snapshotCompleteLogs(state, frame),
            activity = frame.activity + keptExtras(state.activity, frame),
            completeExtras = frame.partialExtras?.let { frame.logs.keys - it.toSet() } ?: frame.logs.keys,
            bgTasks = frame.bgTasks,
            agentState = frame.agentState,
            commands = frame.commands + keptExtras(state.commands, frame),
            commandsResolved = frame.commandsResolved + keptExtras(state.commandsResolved, frame),
            lastRead = lastRead,
            finishJobs = frame.sessions
                .mapNotNull { s -> s.finish_job?.let { s.id to it } }
                .toMap(),
        )
    }
    is ServerFrame.ProjectsChanged -> state.copy(
        projects = frame.projects,
        projectCatalogKnown = true,
        workspaces = applyMembership(state.workspaces, frame.projectMembership),
        archivedWorkspaces = applyMembership(state.archivedWorkspaces, frame.projectMembership),
    )
    is ServerFrame.SessionAdded -> {
        val incoming = frame.session
        val sessions = if (state.sessions.none { it.id == incoming.id }) {
            state.sessions + incoming
        } else {
            state.sessions.map { s ->
                if (s.id != incoming.id) s
                else incoming.copy(
                    status = incoming.status ?: s.status,
                    mute = incoming.mute ?: s.mute,
                    connected = incoming.connected ?: s.connected,
                    model = incoming.model ?: s.model,
                    repo_root = incoming.repo_root ?: s.repo_root,
                    role = incoming.role ?: s.role,
                    session_branch = incoming.session_branch ?: s.session_branch,
                    git = incoming.git ?: s.git,
                    finish_job = incoming.finish_job ?: s.finish_job,
                )
            }
        }
        val finishJobs = incoming.finish_job?.let { job -> state.finishJobs + (incoming.id to job) }
            ?: state.finishJobs
        state.copy(sessions = sessions, finishJobs = finishJobs)
    }
    is ServerFrame.SessionRemoved -> {
        // The per-session agent maps have to go with the session. A kill/archive removes the id,
        // but a resume REUSES it over the same continuous WS with no corrective agent_state frame —
        // so a stale `dead`/`working` entry left behind here would misreport the healthy resumed
        // session (a dead badge on a live agent) until the agent next changed state.
        val hadAgent = state.agentState.containsKey(frame.id) || state.agentErrors.containsKey(frame.id)
        if (state.sessions.none { it.id == frame.id } && !state.bgTasks.containsKey(frame.id) && !hadAgent &&
            frame.id !in state.completeLogs && frame.id !in state.completeExtras
        ) {
            state
        } else {
            state.copy(
                sessions = state.sessions.filterNot { s -> s.id == frame.id },
                bgTasks = state.bgTasks - frame.id,
                agentState = state.agentState - frame.id,
                agentErrors = state.agentErrors - frame.id,
                completeLogs = state.completeLogs - frame.id,
                completeExtras = state.completeExtras - frame.id,
            )
        }
    }
    is ServerFrame.SessionRenamed -> state.copy(
        sessions = state.sessions.map { s -> if (s.id == frame.id) s.copy(name = frame.newName) else s },
    )
    is ServerFrame.SessionsReordered -> {
        val order = frame.orderedIds.withIndex().associate { (i, id) -> id to i }
        if (order.isEmpty()) state
        else state.copy(
            sessions = state.sessions.map { s -> order[s.id]?.let { s.copy(sortOrder = it) } ?: s },
        )
    }
    is ServerFrame.WorkspaceAdded -> state.copy(
        archivedWorkspaces = state.archivedWorkspaces.filter { it.id != frame.workspace.id },
        workspaces = if (state.workspaces.none { it.id == frame.workspace.id }) {
            state.workspaces + frame.workspace
        } else {
            state.workspaces.map { if (it.id == frame.workspace.id) frame.workspace else it }
        },
    )
    is ServerFrame.WorkspaceChanged -> state.copy(
        workspaces = state.workspaces.map { if (it.id == frame.workspace.id) frame.workspace else it },
    )
    is ServerFrame.WorkspaceRemoved -> {
        val moving = state.workspaces.find { it.id == frame.id } ?: return state
        val archived = moving.copy(status = "archived")
        state.copy(
            workspaces = state.workspaces.filter { it.id != frame.id },
            archivedWorkspaces = if (state.archivedWorkspaces.any { it.id == frame.id }) {
                state.archivedWorkspaces.map { if (it.id == frame.id) archived else it }
            } else {
                state.archivedWorkspaces + archived
            },
        )
    }
    is ServerFrame.WorkspacesReordered -> {
        val rank = frame.orderedIds.withIndex().associate { (i, id) -> id to i }
        state.copy(
            workspaces = state.workspaces.map { w -> rank[w.id]?.let { w.copy(sortOrder = it) } ?: w },
        )
    }
    is ServerFrame.ViewAdded -> updateViews(state, frame.workspaceId) { it + frame.view }
    is ServerFrame.ViewRemoved -> updateViews(state, frame.workspaceId) { vs -> vs.filter { it.id != frame.viewId } }
    is ServerFrame.ViewChanged -> updateViews(state, frame.workspaceId) { vs ->
        vs.map { if (it.id == frame.view.id) frame.view else it }
    }
    is ServerFrame.ViewMoved -> {
        var moved: ViewDto? = null
        val stripped = state.workspaces.map { w ->
            if (w.id != frame.fromWorkspaceId) w
            else {
                moved = w.views.firstOrNull { it.id == frame.viewId }
                w.copy(views = w.views.filter { it.id != frame.viewId })
            }
        }
        val v = moved ?: return state.copy(workspaces = stripped)
        state.copy(
            workspaces = stripped.map { w ->
                if (w.id != frame.toWorkspaceId) w
                else w.copy(views = w.views + v.copy(workspaceId = frame.toWorkspaceId))
            },
        )
    }
    is ServerFrame.MessageAppend -> {
        val prev = state.messages[frame.session] ?: emptyList()
        val pruned = if (frame.entry.direction.startsWith("in")) {
            prev.filterNot { it.id.startsWith("local-") && it.text == frame.entry.text }
        } else prev
        state.copy(messages = state.messages + (frame.session to (pruned + frame.entry)))
    }
    is ServerFrame.SessionRead -> {
        val next = advanceLastRead(state.lastRead[frame.session], frame.lastReadAt)
        if (state.lastRead[frame.session] == next) state
        else state.copy(lastRead = state.lastRead + (frame.session to next))
    }
    is ServerFrame.ActivityAppend -> state.copy(
        activity = state.activity + (frame.session to ((state.activity[frame.session] ?: emptyList()) + frame.event)),
    )
    is ServerFrame.BgTasks -> state.copy(bgTasks = state.bgTasks + (frame.session to frame.tasks))
    is ServerFrame.AgentState -> {
        val nextErrors = if (frame.state != "dead") state.agentErrors - frame.session else state.agentErrors
        state.copy(
            agentState = state.agentState + (frame.session to AgentStatus(
                phase = frame.phase, state = frame.state, working = frame.working,
                detail = frame.detail, tool = frame.tool, since = frame.since,
                workingSince = frame.workingSince, waiting = frame.waiting, bgOpen = frame.bgOpen,
            )),
            agentErrors = nextErrors,
        )
    }
    is ServerFrame.AgentError -> state.copy(
        agentErrors = state.agentErrors + (frame.session to frame),
    )
    is ServerFrame.CommandsChanged -> state.copy(
        commands = state.commands + (frame.session to frame.commands),
        commandsResolved = state.commandsResolved + (frame.session to frame.resolved),
    )
    is ServerFrame.FinishJobFrame -> {
        val job = frame.job ?: return state
        state.copy(
            finishJobs = state.finishJobs + (frame.session to job),
            sessions = state.sessions.map { s -> if (s.id == frame.session) s.copy(finish_job = job) else s },
        )
    }
    is ServerFrame.SessionGit -> state.copy(
        sessions = state.sessions.map { s -> if (s.id == frame.session) s.copy(git = frame.git) else s },
    )
    is ServerFrame.LspStatus -> state.copy(
        lspStatus = state.lspStatus + ("${frame.session}|${frame.path}" to frame),
    )
    is ServerFrame.LspReady -> markLspState(state, frame.session, frame.serverId, "ready")
    is ServerFrame.LspError -> markLspState(state, frame.session, frame.serverId, "error", frame.error)
    is ServerFrame.LspExit -> markLspState(state, frame.session, frame.serverId, "exited")
    is ServerFrame.LspInstallProgress -> state.copy(
        lspInstallLog = state.lspInstallLog + (frame.serverId to ((state.lspInstallLog[frame.serverId] ?: emptyList()) + frame.line)),
    )
    is ServerFrame.LspInstallDone -> state.copy(
        lspInstallDone = state.lspInstallDone + (frame.serverId to frame),
    )
    is ServerFrame.DisplayAdded -> state.copy(
        displays = state.displays.filterNot { it.id == frame.display.id } + frame.display,
    )
    is ServerFrame.DisplayRemoved -> {
        if (state.displays.none { it.id == frame.id }) state
        else state.copy(displays = state.displays.filterNot { it.id == frame.id })
    }
    is ServerFrame.WorktreeSizes -> state.copy(
        worktreeSizes = state.worktreeSizes + frame.sizes.associate { it.id to it.bytes },
    )
    is ServerFrame.WorktreesRemoved -> state.copy(
        worktreeSizes = state.worktreeSizes - frame.ids.toSet(),
        removedWorktreeIds = state.removedWorktreeIds + frame.ids,
    )
    else -> state
}

/** Patch each workspace's projectId from a full membership map; absent ids become unresolved. */
private fun applyMembership(ws: List<WorkspaceDto>, m: Map<String, String>): List<WorkspaceDto> =
    ws.map { w -> val p = m[w.id]; if (w.projectId == p) w else w.copy(projectId = p) }

private fun updateViews(state: HostState, workspaceId: String, edit: (List<ViewDto>) -> List<ViewDto>): HostState {
    if (state.workspaces.none { it.id == workspaceId }) return state
    return state.copy(
        workspaces = state.workspaces.map { if (it.id == workspaceId) it.copy(views = edit(it.views)) else it },
    )
}

private fun markLspState(
    state: HostState,
    session: String?,
    serverId: String?,
    lspState: String,
    error: String? = null,
): HostState {
    if (serverId == null) return state
    return state.copy(
        lspStatus = state.lspStatus.mapValues { (_, status) ->
            if (status.session == session && status.serverId == serverId) {
                status.copy(state = lspState, error = error ?: status.error)
            } else {
                status
            }
        },
    )
}

/**
 * A loaded log survives a snapshot that only carries its tail, as long as the tail's newest entry
 * is already in it — nothing arrived while we were away, so the page is still whole. The tail's
 * copy of that entry wins (it may have been edited). Otherwise the tail replaces it.
 */
private fun keepsLoadedLog(state: HostState, id: String, tail: List<LogEntry>): Boolean {
    if (id !in state.completeLogs) return false
    val newest = tail.lastOrNull() ?: return false
    return state.messages[id]?.any { it.id == newest.id } == true
}

private fun snapshotMessages(state: HostState, frame: ServerFrame.Snapshot): Map<String, List<LogEntry>> {
    val partial = frame.partialLogs?.toSet() ?: return frame.logs
    return frame.logs.mapValues { (id, log) ->
        if (id !in partial || !keepsLoadedLog(state, id, log)) {
            log
        } else {
            val newest = log.last()
            state.messages.getValue(id).map { if (it.id == newest.id) newest else it }
        }
    }
}

private fun snapshotCompleteLogs(state: HostState, frame: ServerFrame.Snapshot): Set<String> {
    val partial = frame.partialLogs?.toSet() ?: return frame.logs.keys
    return frame.logs.filter { (id, log) -> id !in partial || keepsLoadedLog(state, id, log) }.keys
}

/**
 * What we already held for sessions a trimmed snapshot sent without extras — shown (possibly a
 * little stale) until the chat's own fetch replaces it, so an open chat never blanks on reconnect.
 */
private fun <V> keptExtras(held: Map<String, V>, frame: ServerFrame.Snapshot): Map<String, V> {
    val trimmed = frame.partialExtras ?: return emptyMap()
    return trimmed.filter { it in frame.logs && it in held }.associateWith { held.getValue(it) }
}

/**
 * Fetched activity plus live `activity_append`s that landed while the fetch was in flight —
 * those carry a higher broker `seq` than anything in the fetched list.
 */
fun mergeFetchedActivity(fetched: List<ActivityEvent>, current: List<ActivityEvent>): List<ActivityEvent> {
    val newest = fetched.mapNotNull { it.seq }.maxOrNull() ?: return if (fetched.isEmpty()) current else fetched
    return fetched + current.filter { (it.seq ?: Int.MIN_VALUE) > newest }
}

/**
 * A fetched history page plus whatever the live buffer gained while the fetch was in flight:
 * entries newer than the page's newest, and optimistic `local-` bubbles. Everything else in the
 * buffer (the snapshot tail) is already inside the page.
 */
fun mergeFetchedLog(fetched: List<LogEntry>, current: List<LogEntry>): List<LogEntry> {
    val newestTs = fetched.lastOrNull()?.ts ?: return current
    val known = fetched.mapTo(HashSet()) { it.id }
    return fetched + current.filter { it.id !in known && (it.ts > newestTs || it.id.startsWith("local-")) }
}
