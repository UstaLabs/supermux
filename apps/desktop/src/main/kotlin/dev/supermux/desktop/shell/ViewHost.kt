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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.chat.ChatPanel
import dev.supermux.desktop.display.DisplayPanel
import dev.supermux.desktop.editor.DiffPane
import dev.supermux.desktop.editor.DiffState
import dev.supermux.desktop.editor.DocumentStore
import dev.supermux.desktop.editor.EditorPrefsStore
import dev.supermux.desktop.editor.ExplorerPane
import dev.supermux.desktop.editor.ExplorerState
import dev.supermux.desktop.editor.FilePane
import dev.supermux.desktop.editor.KcefRuntime
import dev.supermux.desktop.editor.KcefState
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.terminal.DesktopTerminalPanel
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.net.TerminalClient
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.panes.PaneHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import dev.supermux.ui.toWorkdirRelativePath
import dev.supermux.session.inferHomeDir

/**
 * Draw one view's body.
 *
 * Only the ACTIVE view of each group reaches here — PaneHost composes nothing
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
 *
 * The kind `editor` is not one pane but three (spec §7.2), chosen by its view
 * state's `mode`: `tree` draws the explorer, `file` draws ONE document, `diff`
 * draws the diff. An absent or unknown mode draws the tree, which is what an
 * `editor` view has always drawn — an old row must keep working.
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
     * The WORKSPACE's open documents. One store for the whole workspace is the point of the
     * split: two `file` panes on one path are two views of one buffer, so a split shows the same
     * unsaved text on both sides and a drag between groups cannot lose an edit.
     */
    documents: DocumentStore? = null,
    /**
     * "Open this workdir-relative path" — from the explorer, from a search result, from a file
     * path tapped in a chat transcript. The workspace decides which group it lands in.
     */
    onOpenFile: (path: String, line: Int?, endLine: Int?) -> Unit = { _, _, _ -> },
    /** Close THIS view — the diff pane's close button is a tab close, not a mode toggle. */
    onCloseView: () -> Unit = {},
    /**
     * Workspace primary session — used only for LSP (still session-keyed in this phase).
     * A workspace with no chat view passes null and the editor says so.
     */
    primarySessionId: String? = null,
    /** After continue-in-new-conversation — select the new chat session. */
    onSelectSession: (String) -> Unit = {},
    workspaceTerminalContent: @Composable (connect: () -> TerminalClient, modifier: Modifier) -> Unit =
        { connect, mod -> DesktopTerminalPanel(connect = connect, modifier = mod) },
    /**
     * Test seams for the `file` pane's code surface, same shape [EditorPanel] uses: KCEF cannot
     * boot under runComposeUiTest, so tests inject a state the engine is never built from (and an
     * init that does nothing). Production uses the live runtime.
     */
    editorKcefState: StateFlow<KcefState> = KcefRuntime.state,
    editorEnsureInit: (CoroutineScope) -> Unit = { KcefRuntime.ensureInit(it) },
) {
    when (view.kind) {
        "chat" -> {
            val sessionId = view.chatSessionId()
            if (sessionId == null) UnknownViewHint(view.kind, modifier)
            else ChatPanelForSession(app, sessionId, workdir, drafts, onSelectSession, onOpenFile, modifier)
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
        "editor" -> {
            val path = view.stateString("path")
            // An absent or unrecognised mode is the tree — the behaviour every existing
            // `editor` row already has. Only a "file" WITH a path is a document pane.
            when (view.stateString("mode")) {
                "file" ->
                    if (path == null) UnknownViewHint(view.kind, modifier)
                    else FilePaneForWorkspace(
                        app = app,
                        workspaceId = workspaceId,
                        workdir = workdir,
                        path = path,
                        documents = documents ?: rememberWorkspaceDocuments(app, workspaceId),
                        // LSP is still keyed by session (see the plan header). A workspace with
                        // no chat view gets no code intelligence — say so rather than looking broken.
                        lspSessionId = primarySessionId,
                        kcefStateFlow = editorKcefState,
                        onEnsureInit = editorEnsureInit,
                        modifier = modifier.testTag("editor-$workdir"),
                    )
                "diff" -> DiffPaneForWorkspace(
                    app = app,
                    workspaceId = workspaceId,
                    base = view.stateString("diffBase"),
                    lspSessionId = primarySessionId,
                    onClose = onCloseView,
                    modifier = modifier.testTag("editor-$workdir"),
                )
                else -> ExplorerPaneForWorkspace(
                    app = app,
                    workspaceId = workspaceId,
                    onOpenFile = { p -> onOpenFile(p, null, null) },
                    modifier = modifier.testTag("editor-$workdir"),
                )
            }
        }
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
        // The three editor panes name themselves differently: a file tab is its FILENAME, which is
        // the whole point of the tab row being one row (spec §7.2).
        "editor" -> when (view.stateString("mode")) {
            "file" -> view.stateString("path")?.substringAfterLast('/')?.ifBlank { null } ?: "File"
            "diff" -> "Changes"
            else -> "Explorer"
        }
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
    workdir: String,
    drafts: SnapshotStateMap<String, String>,
    onSelectSession: (String) -> Unit,
    onOpenFile: (path: String, line: Int?, endLine: Int?) -> Unit,
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
        onSelectSession = onSelectSession,
        // A tap on a file path in the transcript opens a `file` pane. This used to be dropped on
        // the floor here: the parameter defaults to {} and nothing was passed, so the tap did
        // nothing at all in a workspace. Same conversion SessionDetail does — a path outside the
        // workspace has no workdir-relative form and is logged rather than opened.
        onOpenFile = { ref ->
            val rel = workspaceOpenPath(ref, workdir)
            if (rel == null) {
                println("[ViewHost] onOpenFile: '${ref.path}' is outside workspace workdir '$workdir' — dropped")
            } else {
                onOpenFile(rel, ref.line, ref.endLine)
            }
        },
    )
}

/**
 * A tapped file-path reference as a workdir-relative path, or null when it points outside the
 * workspace (nothing the workspace's fs endpoints could read). Split out of the composable so the
 * conversion is testable without hosting a transcript.
 */
internal fun workspaceOpenPath(ref: FilePathRef, workdir: String): String? =
    toWorkdirRelativePath(ref.path, workdir, inferHomeDir(workdir))

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
 * only composed when the tab is active (PaneHost guarantees that), input is
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
 * Fallback store for a `file` pane whose caller did not hand one down. Correct but NOT what the
 * workspace does: a store made here is scoped to THIS pane, so two panes on one path would hold two
 * buffers. AppShell always passes the workspace's own store; this exists so a `file` view still
 * draws (rather than crashing) at any call site that has not been wired yet.
 */
@Composable
private fun rememberWorkspaceDocuments(app: DesktopAppState, workspaceId: String): DocumentStore {
    val scope = rememberCoroutineScope()
    return remember(workspaceId) {
        DocumentStore(
            fsRead = { p -> app.workspaceFsRead(workspaceId, p) },
            fsWrite = { p, content -> app.workspaceFsWrite(workspaceId, p, content) },
            scope = scope,
        )
    }
}

/** Explorer adapter — the file tree + filename search over `/workspaces/:id/fs*`. */
@Composable
private fun ExplorerPaneForWorkspace(
    app: DesktopAppState,
    workspaceId: String,
    onOpenFile: (String) -> Unit,
    modifier: Modifier,
) {
    // Per-explorer-pane state: two explorer panes may be expanded to different depths, which is
    // fine — the tree is a view of the disk, not of anything the workspace owns.
    val explorer = remember(workspaceId) { ExplorerState() }
    ExplorerPane(
        fsList = { p -> app.workspaceFsList(workspaceId, p) },
        explorer = explorer,
        onOpenFile = onOpenFile,
        fsSearch = { q -> app.workspaceFsSearch(workspaceId, q) },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * File adapter. The document comes from the workspace's [documents]; files read/write through
 * `/workspaces/:id/fs*`. LSP stays session-keyed: with [lspSessionId] set we drive LSP through that
 * session, and with it null the pane still edits and shows a quiet one-line note.
 */
@Composable
private fun FilePaneForWorkspace(
    app: DesktopAppState,
    workspaceId: String,
    workdir: String,
    path: String,
    documents: DocumentStore,
    lspSessionId: String?,
    kcefStateFlow: StateFlow<KcefState>,
    onEnsureInit: (CoroutineScope) -> Unit,
    modifier: Modifier,
) {
    val sessions by app.sessions.collectAsState()
    val lspSession = lspSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    val prefsStore = remember { EditorPrefsStore() }
    var prefs by remember { mutableStateOf(prefsStore.load()) }

    FilePane(
        path = path,
        documents = documents,
        fsRead = { p -> app.workspaceFsRead(workspaceId, p) },
        workdir = workdir,
        lspSessionId = lspSession?.id,
        lspStatus = app.lspStatus,
        lspRpc = app.lspRpc,
        lspStatusQuery = { _, p -> if (lspSession != null) app.lspStatusQuery(lspSession, p) },
        lspOpen = { _, serverId -> if (lspSession != null) app.lspOpen(lspSession, serverId) },
        lspRpcOut = { _, serverId, message -> if (lspSession != null) app.lspRpcOut(lspSession, serverId, message) },
        prefs = prefs,
        onFontSize = { px ->
            val next = prefs.copy(fontSize = px).clamped()
            prefs = next
            prefsStore.save(next)
        },
        kcefStateFlow = kcefStateFlow,
        onEnsureInit = onEnsureInit,
        modifier = modifier.fillMaxSize(),
    )
}

/** Diff adapter — `/workspaces/:id/fs/diff` plus the session-keyed review endpoints. */
@Composable
private fun DiffPaneForWorkspace(
    app: DesktopAppState,
    workspaceId: String,
    base: String?,
    lspSessionId: String?,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val sessions by app.sessions.collectAsState()
    val reviewSession = lspSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    // Per-diff-pane state, seeded from the view's own `diffBase` so a saved row reopens on the
    // base it was looking at.
    val diff = remember(workspaceId, base) { DiffState().apply { base?.let { diffBase = it } } }
    DiffPane(
        diff = diff,
        fsDiff = { spec -> app.workspaceFsDiff(workspaceId, spec) },
        fsRefs = { app.workspaceFsRefs(workspaceId) },
        // Review stays session-keyed; only available when we have a primary chat.
        onReviewAddComment = { body -> if (reviewSession != null) app.reviewAddComment(reviewSession, body) else null },
        onReviewResolve = { commentId -> if (reviewSession != null) app.reviewResolve(reviewSession, commentId) else false },
        onReviewSubmit = { if (reviewSession != null) app.reviewSubmit(reviewSession) else null },
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
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
