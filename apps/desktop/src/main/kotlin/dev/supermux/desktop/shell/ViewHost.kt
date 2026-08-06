package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.chat.ChatPanel
import dev.supermux.desktop.display.DisplayPanel
import dev.supermux.desktop.editor.EditorPanel
import dev.supermux.desktop.editor.EditorPrefsStore
import dev.supermux.desktop.editor.PendingEditorOpen
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.terminal.DesktopTerminalPanel
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.net.TerminalClient
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
 *
 * [workspaceTerminalContent] is a test seam: SwingPanel/JediTerm cannot be hosted
 * under runComposeUiTest, so UI tests inject a pure-Compose stand-in. Production
 * always uses the default real [DesktopTerminalPanel].
 */
@Composable
fun ViewHost(
    view: ViewDto,
    workspaceId: String,
    workdir: String,
    app: DesktopAppState,
    drafts: SnapshotStateMap<String, String>,
    modifier: Modifier = Modifier,
    /**
     * Workspace primary session — used only for LSP (still session-keyed in this phase).
     * A workspace with no chat view passes null and the editor says so.
     */
    primarySessionId: String? = null,
    workspaceTerminalContent: @Composable (connect: () -> TerminalClient, modifier: Modifier) -> Unit =
        { connect, mod -> DesktopTerminalPanel(connect = connect, modifier = mod) },
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
            if (scope == "session") {
                val sessionId = view.stateString("sessionId")
                if (sessionId == null) UnknownViewHint(view.kind, modifier)
                else AgentTerminalForSession(app, sessionId, terminalId, modifier)
            } else {
                WorkspaceTerminalPanel(
                    app = app,
                    workspaceId = workspaceId,
                    terminalId = terminalId,
                    content = workspaceTerminalContent,
                    modifier = modifier.testTag("terminal-$workspaceId-$terminalId"),
                )
            }
        }
        "editor" -> EditorPanelForWorkdir(
            app = app,
            workdir = workdir,
            workspaceId = workspaceId,
            path = view.stateString("path"),
            mode = view.stateString("mode") ?: "tree",
            // LSP is still keyed by session (see the plan header). A workspace with
            // no chat view gets no code intelligence — say so rather than looking broken.
            lspSessionId = primarySessionId,
            modifier = modifier.testTag("editor-$workdir"),
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
 * Workspace-scoped terminal — a plain shell in the workspace work directory.
 * Attaches with `?workspace=<id>` (spec §7.3). Same JediTerm rules as SessionDetail:
 * only composed when the tab is active (LayoutHost guarantees that), input is
 * marshaled off non-EDT threads by TerminalClient, and nothing Compose paints
 * can appear above the heavyweight Swing child.
 */
@Composable
private fun WorkspaceTerminalPanel(
    app: DesktopAppState,
    workspaceId: String,
    terminalId: String,
    content: @Composable (connect: () -> TerminalClient, modifier: Modifier) -> Unit,
    modifier: Modifier,
) {
    key(workspaceId, terminalId) {
        content(
            { app.connectWorkspaceTerminal(workspaceId, terminalId) },
            modifier.fillMaxSize(),
        )
    }
}

/**
 * Editor adapter. Files read/write through `/workspaces/:id/fs*`. LSP stays
 * session-keyed: when [lspSessionId] is set we drive LSP and editor_open through
 * that session; when null the editor still works and shows a quiet one-line note.
 */
@Composable
private fun EditorPanelForWorkdir(
    app: DesktopAppState,
    workdir: String,
    workspaceId: String,
    path: String?,
    mode: String,
    lspSessionId: String?,
    modifier: Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val editorMode = mode
    val sessions by app.sessions.collectAsState()
    val lspSession = lspSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    val pending = path?.let { PendingEditorOpen(it, null, null) }
    val prefsStore = remember { EditorPrefsStore() }
    var prefs by remember { mutableStateOf(prefsStore.load()) }
    // Editor state is keyed on this id — use the workspace so a chatless workspace still
    // has a stable identity without inventing a fake session.
    val editorKey = lspSession?.id ?: "workspace:$workspaceId"

    Column(modifier.fillMaxSize()) {
        if (lspSession == null) {
            Text(
                "No agent in this workspace — code intelligence is off.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = Space.sm, vertical = Space.xs)
                    .testTag("editor-no-lsp"),
            )
        }
        EditorPanel(
            sessionId = editorKey,
            workdir = workdir,
            fsList = { p -> app.workspaceFsList(workspaceId, p) },
            fsRead = { p -> app.workspaceFsRead(workspaceId, p) },
            fsWrite = { p, content -> app.workspaceFsWrite(workspaceId, p, content) },
            fsSearch = { q -> app.workspaceFsSearch(workspaceId, q) },
            fsDiff = { base -> app.workspaceFsDiff(workspaceId, base) },
            fsRefs = { app.workspaceFsRefs(workspaceId) },
            // Review stays session-keyed; only available when we have a primary chat.
            onReviewAddComment = { body ->
                if (lspSession != null) app.reviewAddComment(lspSession, body) else null
            },
            onReviewResolve = { commentId ->
                if (lspSession != null) app.reviewResolve(lspSession, commentId) else false
            },
            onReviewSubmit = {
                if (lspSession != null) app.reviewSubmit(lspSession) else null
            },
            fsChanges = app.fsChanges,
            lspStatus = app.lspStatus,
            lspRpc = app.lspRpc,
            lspStatusQuery = { _, p ->
                if (lspSession != null) app.lspStatusQuery(lspSession, p)
            },
            lspOpen = { _, serverId ->
                if (lspSession != null) app.lspOpen(lspSession, serverId)
            },
            lspRpcOut = { _, serverId, message ->
                if (lspSession != null) app.lspRpcOut(lspSession, serverId, message)
            },
            editorOpen = {
                if (lspSession != null) app.editorOpen(lspSession)
            },
            editorClose = {
                if (lspSession != null) app.editorClose(lspSession)
            },
            pendingOpen = pending,
            onPendingOpenConsumed = {},
            prefs = prefs,
            onFontSize = { px ->
                val next = prefs.copy(fontSize = px).clamped()
                prefs = next
                prefsStore.save(next)
            },
            modifier = Modifier.fillMaxSize().weight(1f),
        )
    }
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

/** Unknown or incomplete view — never throw; a future kind must degrade gracefully. */
@Composable
fun UnknownViewHint(kind: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxSize()
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .testTag("view-unknown"),
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
