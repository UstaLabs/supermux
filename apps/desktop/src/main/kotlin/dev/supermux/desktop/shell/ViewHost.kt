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
import dev.supermux.ui.chat.ChatPanel
import dev.supermux.ui.chat.FinishBindings
import dev.supermux.ui.chat.rememberChatActions
import dev.supermux.ui.chat.rememberChatState
import dev.supermux.ui.chat.ComposerExternalAttach
import dev.supermux.ui.chat.ComposerExternalDictate
import dev.supermux.desktop.display.DisplayPanel
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.editor.DiffPane
import dev.supermux.ui.editor.ExplorerPane
import dev.supermux.ui.editor.FilePane
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.DiffState
import dev.supermux.ui.editor.DocumentStore
import dev.supermux.ui.editor.ExplorerState
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.net.ProxyDto
import dev.supermux.net.TerminalClient
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.panes.PaneHost
import dev.supermux.ui.toWorkdirRelativePath
import dev.supermux.workspace.viewTitle
import dev.supermux.session.inferHomeDir
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.EDITOR_LINE_WRAP_DEFAULT
import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import kotlinx.coroutines.launch

/**
 * Draw one view's body.
 *
 * Only the ACTIVE view of each group reaches here — PaneHost composes nothing
 * else. That is load-bearing, not an optimization: the terminal and the editor
 * are heavyweight AWT SwingPanel children, and one live JCEF per background tab
 * would exhaust memory. Do not compose an inactive tab.
 *
 * An unknown kind draws a hint rather than throwing. A future view kind must
 * degrade to "this client does not draw that yet".
 *
 * Adapters wrap the same call shapes SessionDetail uses today — see ChatPanel,
 * DesktopEditorPanel, the terminal seam / TerminalTabs, DisplayPanel.
 *
 * Terminals are mounted through `Platform.terminalView()` (cluster G1) rather than
 * by naming JediTerm here, so cluster G7 can move this host into `:ui` unchanged.
 * [workspaceTerminalContent] is a test seam: SwingPanel/JediTerm cannot be hosted
 * under runComposeUiTest, so UI tests inject a pure-Compose stand-in. Production
 * always uses the default, which is the platform's own engine.
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
    app: HostStore,
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
    /** Reveal/create the singleton Changes pane and switch it into walkthrough mode. */
    onOpenWalkthrough: (sessionId: String, stepId: String?) -> Unit = { _, _ -> },
    /** Session whose walkthrough the singleton Changes pane currently presents. */
    walkthroughSessionId: String? = null,
    onWalkthroughClosed: () -> Unit = {},
    appForSession: (String) -> HostStore = { app },
    /** Markdown preview per view id — the file's TAB owns the toggle now, so the state is hoisted. */
    previewModeFor: (String) -> Boolean = { false },
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
        { connect, mod ->
            LocalPlatform.current.terminalView()
                .TerminalView(connect = connect, modifier = mod, active = true, onExit = null)
        },
    /**
     * The agent's raw PTY, drawn behind a chat view's Chat⇄Native pill. Same test-seam reason as
     * [workspaceTerminalContent]: the desktop engine is a SwingPanel and cannot be hosted under
     * runComposeUiTest, so tests inject a pure-Compose stand-in.
     */
    chatNativeContent: @Composable (connect: () -> TerminalClient, onExit: () -> Unit) -> Unit =
        { connect, onExit ->
            LocalPlatform.current.terminalView().TerminalView(
                connect = connect,
                modifier = Modifier.fillMaxSize(),
                active = true,
                onExit = onExit,
            )
        },
    /**
     * Per-session proxy load for a chat view's links menu — the globe dropdown that moved off the
     * old session header. Defaults to the real broker fetch.
     */
    loadProxies: (suspend () -> List<ProxyDto>)? = null,
    /** Off-by-default headless hook (SM_LINKS_MENU): the session whose links menu to force-open. */
    forceLinksMenuFor: String? = null,
    onForceLinksMenuConsumed: () -> Unit = {},
    /**
     * One-shot composer requests, each addressed to ONE session so only that session's chat view
     * consumes it. These used to reach the composer through the deleted session header:
     * `SM_CHAT_ATTACH`, `SM_DICTATE`, and — the one that is NOT a test hook — Edit ▸ Paste image
     * in the native menu bar, which targets the SELECTED session.
     */
    externalAttach: Pair<String, ComposerExternalAttach>? = null,
    onExternalAttachConsumed: () -> Unit = {},
    externalDictate: Pair<String, ComposerExternalDictate>? = null,
    onExternalDictateConsumed: () -> Unit = {},
    pasteImageFor: String? = null,
    pasteImageRequestNonce: Long = 0L,
    onPasteImageRequestConsumed: () -> Unit = {},
    /**
     * Test seam for the `file` pane's code surface: JCEF cannot boot under runComposeUiTest, so
     * tests inject a factory that never builds an engine. Null → this platform's own.
     */
    editorEngineFactory: EditorEngineFactory? = null,
) {
    when (view.kind) {
        "chat" -> {
            val sessionId = view.chatSessionId()
            if (sessionId == null) UnknownViewHint(view.kind, modifier)
            else ChatPanelForSession(
                app = app,
                sessionId = sessionId,
                workdir = workdir,
                drafts = drafts,
                onSelectSession = onSelectSession,
                onOpenFile = onOpenFile,
                onOpenWalkthrough = { stepId -> onOpenWalkthrough(sessionId, stepId) },
                nativeContent = chatNativeContent,
                loadProxies = loadProxies,
                forceLinksMenu = forceLinksMenuFor == sessionId,
                onForceLinksMenuConsumed = onForceLinksMenuConsumed,
                externalAttach = externalAttach?.takeIf { it.first == sessionId }?.second,
                onExternalAttachConsumed = onExternalAttachConsumed,
                externalDictate = externalDictate?.takeIf { it.first == sessionId }?.second,
                onExternalDictateConsumed = onExternalDictateConsumed,
                // Addressed to one session so a workspace showing two chats pastes into the
                // selected one, not both.
                pasteImageRequestNonce = if (pasteImageFor == sessionId) pasteImageRequestNonce else 0L,
                onPasteImageRequestConsumed = onPasteImageRequestConsumed,
                modifier = modifier,
            )
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
                        previewMode = previewModeFor(view.id),
                        app = app,
                        workspaceId = workspaceId,
                        workdir = workdir,
                        path = path,
                        documents = documents ?: rememberWorkspaceDocuments(app, workspaceId),
                        // LSP is still keyed by session (see the plan header). A workspace with
                        // no chat view gets no code intelligence — say so rather than looking broken.
                        lspSessionId = primarySessionId,
                        engineFactory = editorEngineFactory,
                        modifier = modifier.testTag("editor-$workdir"),
                    )
                "diff" -> DiffPaneForWorkspace(
                    app = app,
                    appForSession = appForSession,
                    workspaceId = workspaceId,
                    base = view.stateString("diffBase"),
                    lspSessionId = primarySessionId,
                    walkthroughSessionId = walkthroughSessionId,
                    onWalkthroughClosed = onWalkthroughClosed,
                    onClose = onCloseView,
                    onOpenFile = onOpenFile,
                    modifier = modifier.testTag("editor-$workdir"),
                )
                else -> ExplorerPaneForWorkspace(
                    app = app,
                    workspaceId = workspaceId,
                    workdir = workdir,
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

/**
 * Chat adapter — same [ChatPanel] call shape as SessionDetail (app, session, draft, …).
 * [showHeader] is true here because ViewHost has no session identity header of its own.
 */
@Composable
private fun ChatPanelForSession(
    app: HostStore,
    sessionId: String,
    workdir: String,
    drafts: SnapshotStateMap<String, String>,
    onSelectSession: (String) -> Unit,
    onOpenFile: (path: String, line: Int?, endLine: Int?) -> Unit,
    onOpenWalkthrough: (stepId: String?) -> Unit,
    nativeContent: @Composable (connect: () -> TerminalClient, onExit: () -> Unit) -> Unit,
    loadProxies: (suspend () -> List<ProxyDto>)?,
    forceLinksMenu: Boolean,
    onForceLinksMenuConsumed: () -> Unit,
    externalAttach: ComposerExternalAttach?,
    onExternalAttachConsumed: () -> Unit,
    externalDictate: ComposerExternalDictate?,
    onExternalDictateConsumed: () -> Unit,
    pasteImageRequestNonce: Long,
    onPasteImageRequestConsumed: () -> Unit,
    modifier: Modifier,
) {
    val sessions by app.sessions.collectAsState()
    val finishJobs by app.finishJobs.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        UnknownViewHint("chat", modifier)
        return
    }
    ChatPanel(
        session = session,
        state = rememberChatState(app, sessionId),
        actions = rememberChatActions(app, session, loadProxies),
        draft = drafts[sessionId] ?: "",
        onDraftChange = { drafts[sessionId] = it },
        modifier = modifier.fillMaxSize().testTag("view_chat"),
        showHeader = true,
        onOpenWalkthrough = onOpenWalkthrough,
        // Mute is the one session control this panel can perform without a dialog; rename/kill live
        // in OverflowMenu (the header slot below), so they stay out of the slash menu here.
        onRequestMute = { app.setMute(sessionId, !(session.mute ?: false)) },
        // NEW ON DESKTOP (cluster D4): the Finish flow, which existed as dead code until now.
        finish = FinishBindings(
            job = finishJobs[sessionId],
            readiness = { app.finishReadiness(sessionId) },
            // On the STORE's scope, not this panel's: switching views mid-kickoff must not
            // cancel the request (Android routes through FleetStore.finish for the same reason).
            finish = { action, skipVerify, commitFirst, commitMessage, onKickoff ->
                app.kickoffFinish(sessionId, action, skipVerify, commitFirst, commitMessage, onKickoff)
            },
            clearJob = { app.clearFinishJob(sessionId) },
            verifySuggest = { app.verifySuggest(sessionId) },
            verifySave = { app.verifySave(sessionId, it) },
            sendToAgent = { app.sendMessage(sessionId, it) },
        ),
        // The globe (links) menu + the ⋮ overflow stay in desktop's shell/ (cluster G moves them);
        // the panel reaches them through these slots and only OWNS the proxy load-on-open.
        forceLinksMenu = forceLinksMenu,
        onForceLinksMenuConsumed = onForceLinksMenuConsumed,
        headerLinks = { proxies, force, onForceConsumed ->
            SessionLinksMenu(
                session = session,
                proxies = proxies,
                forceOpen = force,
                onForceOpenConsumed = onForceConsumed,
            )
        },
        headerActions = {
            OverflowMenu(
                session = session,
                onRename = { name -> app.rename(sessionId, name) },
                onToggleMute = { muted -> app.setMute(sessionId, muted) },
                onKill = { app.kill(sessionId) },
                onContinue = { handoff ->
                    app.continueConversation(
                        session,
                        handoff.message,
                        handoff.agent,
                        handoff.model,
                        handoff.reasoningLevel,
                    )
                },
                loadContinueAgents = { app.launcherAgents() },
                loadContinueModels = { app.launcherModels(it) },
                loadContinueReasoning = { agent, model -> app.launcherReasoning(agent, model) },
                onContinued = onSelectSession,
                showManagementRows = false,
            )
        },
        // key(sessionId) so a view rebound to another session never reuses the previous session's
        // agent PTY: a terminal surface's `remember { connect() }` is deliberately unkeyed.
        nativeContent = { onExit ->
            key(sessionId) { nativeContent({ app.connectAgentTerminal(sessionId) }, onExit) }
        },
        externalAttach = externalAttach,
        onExternalAttachConsumed = onExternalAttachConsumed,
        externalDictate = externalDictate,
        onExternalDictateConsumed = onExternalDictateConsumed,
        pasteImageRequestNonce = pasteImageRequestNonce,
        onPasteImageRequestConsumed = onPasteImageRequestConsumed,
        // A tap on a file path in the transcript opens a `file` pane. A path outside the workspace
        // has no workdir-relative form and is logged rather than opened.
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
 * - terminalId "agent" → agent PTY via [HostStore.connectAgentTerminal]
 * - any other id → scratch terminal via [HostStore.connectTerminal]
 *
 * key(sessionId, terminalId) so a view switch does not reuse the wrong terminal client.
 */
@Composable
private fun AgentTerminalForSession(
    app: HostStore,
    sessionId: String,
    terminalId: String,
    modifier: Modifier,
) {
    val terminals = LocalPlatform.current.terminalView()
    key(sessionId, terminalId) {
        if (terminalId == "agent") {
            terminals.TerminalView(
                connect = { app.connectAgentTerminal(sessionId) },
                modifier = modifier.fillMaxSize().testTag("view_terminal_agent"),
                active = true,
                onExit = null,
            )
        } else {
            terminals.TerminalView(
                connect = { app.connectTerminal(sessionId, terminalId) },
                modifier = modifier.fillMaxSize().testTag("view_terminal"),
                active = true,
                onExit = null,
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
    app: HostStore,
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
private fun rememberWorkspaceDocuments(app: HostStore, workspaceId: String): DocumentStore {
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
    app: HostStore,
    workspaceId: String,
    workdir: String,
    onOpenFile: (String) -> Unit,
    modifier: Modifier,
) {
    // Per-explorer-pane state: two explorer panes may be expanded to different depths, which is
    // fine — the tree is a view of the disk, not of anything the workspace owns.
    val explorer = remember(workspaceId) { ExplorerState() }
    ExplorerPane(
        fsList = { p -> app.workspaceFsListResult(workspaceId, p) },
        explorer = explorer,
        workdir = workdir,
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
    previewMode: Boolean,
    app: HostStore,
    workspaceId: String,
    workdir: String,
    path: String,
    documents: DocumentStore,
    lspSessionId: String?,
    engineFactory: EditorEngineFactory?,
    modifier: Modifier,
) {
    val sessions by app.sessions.collectAsState()
    val lspSession = lspSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    // Editor prefs come from the shared SettingsStore now (ui/prefs/UiPrefs.kt): the collected
    // values start at the defaults for one frame, then settle on what was persisted.
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val lineWrap by prefs.editorLineWrap.collectAsState(EDITOR_LINE_WRAP_DEFAULT)
    val fontSize by prefs.editorFontSize.collectAsState(EDITOR_FONT_DEFAULT)

    FilePane(
        previewMode = previewMode,
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
        lineWrap = lineWrap,
        fontSize = fontSize,
        onFontSize = { px -> scope.launch { prefs.putEditorFontSize(px) } },
        engineFactory = engineFactory,
        modifier = modifier.fillMaxSize(),
    )
}

/** Diff adapter — `/workspaces/:id/fs/diff` plus the session-keyed review endpoints. */
@Composable
private fun DiffPaneForWorkspace(
    app: HostStore,
    appForSession: (String) -> HostStore,
    workspaceId: String,
    base: String?,
    lspSessionId: String?,
    walkthroughSessionId: String?,
    onWalkthroughClosed: () -> Unit,
    onClose: () -> Unit,
    onOpenFile: (path: String, line: Int?, endLine: Int?) -> Unit,
    modifier: Modifier,
) {
    val sessions by app.sessions.collectAsState()
    val reviewSession = lspSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    val selectedWalkthroughId = walkthroughSessionId ?: lspSessionId
    val walkthroughApp = selectedWalkthroughId?.let(appForSession) ?: app
    val walkthroughSessions by walkthroughApp.sessions.collectAsState()
    val walkthroughSession = selectedWalkthroughId?.let { id -> walkthroughSessions.firstOrNull { it.id == id } }
    // Per-diff-pane state, seeded from the view's own `diffBase` so a saved row reopens on the
    // base it was looking at.
    val diff = remember(workspaceId, base) { DiffState().apply { base?.let { diffBase = it } } }
    // Gate the holder READ on the capability (a host without a WalkthroughSeam throws here).
    val walkthroughCap = LocalPlatform.current.caps.walkthrough
    val walkthrough = walkthroughSession?.takeIf { walkthroughCap }?.let { walkthroughApp.walkthroughState<WalkthroughState>(it.id) }
    val reviewWalkthrough = reviewSession?.takeIf { walkthroughCap }?.let { app.walkthroughState<WalkthroughState>(it.id) }
    DiffPane(
        diff = diff,
        walkthrough = walkthrough,
        reviewWalkthrough = reviewWalkthrough,
        fsDiff = { spec -> app.workspaceFsDiff(workspaceId, spec) },
        fsRefs = { app.workspaceFsRefs(workspaceId) },
        getWalkthrough = { if (walkthroughSession != null) walkthroughApp.getWalkthrough(walkthroughSession) else null },
        getWalkthroughComments = {
            if (walkthroughSession != null) walkthroughApp.reviewComments(walkthroughSession) else emptyList()
        },
        getReviewComments = { if (reviewSession != null) app.reviewComments(reviewSession) else emptyList() },
        readWalkthroughFile = { repo, path ->
            app.workspaceFsRead(workspaceId, if (repo.isBlank()) path else "$repo/$path")
        },
        onOpenWalkthroughFile = { repo, path, line ->
            onOpenFile(if (repo.isBlank()) path else "$repo/$path", line, null)
        },
        // Review stays session-keyed; only available when we have a primary chat.
        onReviewAddComment = { body -> if (reviewSession != null) app.reviewAddComment(reviewSession, body) else null },
        onReviewResolve = { commentId -> if (reviewSession != null) app.reviewResolve(reviewSession, commentId) else false },
        onWalkthroughAddComment = { body ->
            if (walkthroughSession != null) walkthroughApp.reviewAddComment(walkthroughSession, body) else null
        },
        onWalkthroughResolve = { commentId ->
            if (walkthroughSession != null) walkthroughApp.reviewResolve(walkthroughSession, commentId) else false
        },
        onWalkthroughClosed = onWalkthroughClosed,
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
    app: HostStore,
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
