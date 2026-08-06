package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.chat.ChatPanel
import dev.supermux.desktop.display.DisplayPanel
import dev.supermux.desktop.editor.PendingEditorOpen
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.terminal.DesktopTerminalPanel
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString

/**
 * Draw one view's body.
 *
 * Only the ACTIVE view of each group reaches here — LayoutHost composes nothing
 * else. That is load-bearing, not an optimization: the terminal and the editor
 * are heavyweight AWT SwingPanel children, and one live KCEF per background tab
 * would exhaust memory. Do not compose an inactive tab.
 *
 * An unknown kind draws a hint rather than throwing. A future view kind must
 * degrade to "this client does not draw that yet".
 *
 * Adapters wrap the same call shapes SessionDetail uses today — see ChatPanel,
 * DesktopEditorPanel, DesktopTerminalPanel / TerminalTabs, DisplayPanel.
 */
@Composable
fun ViewHost(
    view: ViewDto,
    workspaceId: String,
    workdir: String,
    app: DesktopAppState,
    drafts: SnapshotStateMap<String, String>,
    modifier: Modifier = Modifier,
) {
    when (view.kind) {
        "chat" -> {
            val sessionId = view.chatSessionId()
            if (sessionId == null) UnknownViewHint(view.kind, modifier)
            else ChatPanelForSession(app, sessionId, drafts, modifier)
        }
        "terminal" -> {
            val scope = view.stateString("scope") ?: "workspace"
            val terminalId = view.stateString("terminalId") ?: "main"
            // Phase 4 adds the workspace scope to the broker. Until it lands, a
            // workspace-scoped terminal has nothing to attach to — draw the hint.
            if (scope == "session") {
                val sessionId = view.stateString("sessionId")
                if (sessionId == null) UnknownViewHint(view.kind, modifier)
                else AgentTerminalForSession(app, sessionId, terminalId, modifier)
            } else {
                WorkspaceTerminalPending(modifier) // replaced in Phase 4
            }
        }
        "editor" -> EditorPanelForWorkdir(
            app = app,
            workdir = workdir,
            path = view.stateString("path"),
            mode = view.stateString("mode") ?: "tree",
            modifier = modifier,
        )
        "display" -> {
            val displayId = view.stateString("displayId")
            if (displayId == null) UnknownViewHint(view.kind, modifier)
            else DisplayPanelForStream(app, displayId, modifier)
        }
        else -> UnknownViewHint(view.kind, modifier)
    }
}

/** Tab / accessibility label for a view. Prefers the broker title, then kind-specific fallbacks. */
fun viewTitle(view: ViewDto): String {
    view.title?.takeIf { it.isNotBlank() }?.let { return it }
    return when (view.kind) {
        "chat" -> "Chat"
        "terminal" -> view.stateString("terminalId") ?: "Terminal"
        "editor" -> view.stateString("path")?.substringAfterLast('/')?.ifBlank { null } ?: "Editor"
        "display" -> "Display"
        else -> view.kind
    }
}

/**
 * Chat adapter — same [ChatPanel] call shape as SessionDetail (app, session, draft, …).
 * [showHeader] is true here because ViewHost has no session identity header of its own.
 */
@Composable
private fun ChatPanelForSession(
    app: DesktopAppState,
    sessionId: String,
    drafts: SnapshotStateMap<String, String>,
    modifier: Modifier,
) {
    val sessions by app.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        UnknownViewHint("chat", modifier)
        return
    }
    ChatPanel(
        app = app,
        session = session,
        draft = drafts[sessionId] ?: "",
        onDraftChange = { drafts[sessionId] = it },
        modifier = modifier.fillMaxSize().testTag("view_chat"),
        showHeader = true,
    )
}

/**
 * Session-scoped terminal adapter. Mirrors SessionDetail's terminal / native wiring:
 * - terminalId "agent" → agent PTY via [DesktopAppState.connectAgentTerminal]
 * - any other id → scratch terminal via [DesktopAppState.connectTerminal]
 *
 * key(sessionId, terminalId) so a view switch does not reuse the wrong JediTerm client.
 */
@Composable
private fun AgentTerminalForSession(
    app: DesktopAppState,
    sessionId: String,
    terminalId: String,
    modifier: Modifier,
) {
    key(sessionId, terminalId) {
        if (terminalId == "agent") {
            DesktopTerminalPanel(
                connect = { app.connectAgentTerminal(sessionId) },
                modifier = modifier.fillMaxSize().testTag("view_terminal_agent"),
            )
        } else {
            DesktopTerminalPanel(
                connect = { app.connectTerminal(sessionId, terminalId) },
                modifier = modifier.fillMaxSize().testTag("view_terminal"),
            )
        }
    }
}

/**
 * Editor adapter. Broker fs* APIs are still session-scoped, so we pick a live session
 * whose workdir (or repo root) matches the workspace workdir — the same binding
 * DesktopEditorPanel uses inside SessionDetail.
 */
@Composable
private fun EditorPanelForWorkdir(
    app: DesktopAppState,
    workdir: String,
    path: String?,
    mode: String,
    modifier: Modifier,
) {
    val sessions by app.sessions.collectAsState()
    val session = sessions.firstOrNull { it.workdir == workdir }
        ?: sessions.firstOrNull { it.repo_root == workdir }
    if (session == null) {
        Box(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .testTag("view_editor_pending"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Editor needs a session in $workdir",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        return
    }
    // mode is reserved for tree/diff/file once the view state drives EditorPanel; path opens a tab.
    @Suppress("UNUSED_VARIABLE")
    val editorMode = mode
    val pending = path?.let { PendingEditorOpen(it, null, null) }
    DesktopEditorPanel(
        app = app,
        session = session,
        pendingOpen = pending,
        onPendingOpenConsumed = {},
        modifier = modifier.fillMaxSize().testTag("view_editor"),
    )
}

/**
 * Display adapter for a view that names a stream by id. Resolves the stream's session
 * name and reuses [DisplayPanel] with the same call shape SessionDetail uses.
 */
@Composable
private fun DisplayPanelForStream(
    app: DesktopAppState,
    displayId: String,
    modifier: Modifier,
) {
    val live by app.displays.collectAsState()
    val sessions by app.sessions.collectAsState()
    LaunchedEffect(displayId) { app.listDisplays() }
    val stream = live.firstOrNull { it.id == displayId }
    if (stream == null) {
        Box(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .testTag("view_display_pending"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Display $displayId is not running",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        return
    }
    val session = sessions.firstOrNull { it.name == stream.sessionName }
        ?: SessionInfo(
            id = "display-$displayId",
            name = stream.sessionName,
            workdir = "",
            agent = "unknown",
        )
    DisplayPanel(
        app = app,
        session = session,
        modifier = modifier.fillMaxSize().testTag("view_display"),
    )
}

/**
 * Placeholder until Phase 4 wires workspace-scoped terminals to the broker
 * (`w:<workspaceId>` tmux scope). Do not invent a client-only attach path.
 */
@Composable
private fun WorkspaceTerminalPending(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxSize()
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .testTag("view_terminal_workspace_pending"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Workspace terminal", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(Space.xs))
            Text(
                "arrives in Phase 4",
                color = cs.onSurfaceVariant,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
            )
        }
    }
}

/** Unknown or incomplete view — never throw; a future kind must degrade gracefully. */
@Composable
fun UnknownViewHint(kind: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxSize()
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .testTag("view_unknown"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "This client does not draw “$kind” yet",
                color = cs.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                "Update Supermux to open this view",
                color = cs.onSurfaceVariant,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
            )
        }
    }
}
