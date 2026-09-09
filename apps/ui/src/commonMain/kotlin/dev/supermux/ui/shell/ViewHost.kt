// Cluster G7: ONE view host for both apps.
//
// Desktop's `shell/ViewHost.kt` is the base — every view kind, the three editor modes, the
// walkthrough / LSP / review wiring — re-expressed over the [ShellActions] holder (G1) so it names
// no store. Android's `workspace/AndroidViewHost.kt` is folded in as the Compact/Touch branch: the
// only thing it did differently was the CHAT view's chrome (a phone pane has none, a tablet pane
// has its own [ChatViewHeader] plus a keep-alive Chat⇄Native pair), which is now
// [ChatHeaderMode]. Everything Android lacked — walkthrough state, the review endpoints, the
// per-session LSP routing, the `display` pending hint — it gains by adoption.
//
// Removed: Android's `previewModes` read. `WorkspaceSession.previewModes` is written only by
// desktop's file TAB (the tab owns the markdown-preview toggle); Android's tab strip has no such
// affordance, so `session.previewModes[path]` there was permanently false — a slot nothing writes.
// The shared host takes desktop's [previewModeFor] lambda, which Android simply does not pass.
package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import dev.supermux.net.ProxyDto
import dev.supermux.net.TerminalClient
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString
import dev.supermux.session.inferHomeDir
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.TestIds
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.ChatActions
import dev.supermux.ui.chat.ChatPanel
import dev.supermux.ui.chat.ChatState
import dev.supermux.ui.chat.ComposerExternalAttach
import dev.supermux.ui.chat.ComposerExternalDictate
import dev.supermux.ui.chat.FinishBindings
import dev.supermux.ui.display.DisplayPanel
import dev.supermux.ui.editor.DiffPane
import dev.supermux.ui.editor.DiffState
import dev.supermux.ui.editor.DocumentStore
import dev.supermux.ui.editor.ExplorerPane
import dev.supermux.ui.editor.ExplorerState
import dev.supermux.ui.editor.FilePane
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.ui.prefs.EDITOR_LINE_WRAP_DEFAULT
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.toWorkdirRelativePath
import dev.supermux.ui.widgets.KeepAlivePanel
import kotlinx.coroutines.launch

/** Journey + desktop-parity tags for the workspace chat pane. */
object WorkspaceChatPaneTestIds {
    const val CHAT_VIEW = TestIds.CHAT_VIEW
    const val VIEW_CHAT = "view_chat"
}

/**
 * How a `chat` view carries its identity/actions chrome.
 *
 * Not a platform switch — a SHAPE switch, and both hosts can ask for any of the three:
 *  - [PANEL] — the chat panel's own one-line header, with the links + overflow SLOTS filled by
 *    [SessionLinksMenu] / [OverflowMenu]. Desktop's shape.
 *  - [BAR] — a separate [ChatViewHeader] above a header-less panel, with the Chat⇄Native pair kept
 *    alive side by side. Android's tablet-workspace shape.
 *  - [NONE] — no chrome at all: the host above draws it (Android's phone tab strip, whose trailing
 *    slot holds [PhoneTabChatOverflow]).
 */
enum class ChatHeaderMode { PANEL, BAR, NONE }

/**
 * The shape a host gets when it does not ask for one: a POINTER host draws the panel header (its
 * slots need a mouse to be worth having), a touch host draws the bar when there is room for it and
 * nothing at all on a phone, where the screen's own header is right above.
 */
@Composable
fun defaultChatHeaderMode(): ChatHeaderMode = when {
    LocalPointerAvailable.current -> ChatHeaderMode.PANEL
    LocalWindowWidthClass.current == WindowWidthClass.Compact -> ChatHeaderMode.NONE
    else -> ChatHeaderMode.BAR
}

/**
 * Draw one view's body.
 *
 * Only the ACTIVE view of each group reaches here — PaneHost composes nothing else. That is
 * load-bearing, not an optimization: the terminal and the editor are heavyweight native children,
 * and one live browser per background tab would exhaust memory. Do not compose an inactive tab.
 *
 * An unknown kind draws a hint rather than throwing. A future view kind must degrade to "this
 * client does not draw that yet".
 *
 * Terminals are mounted through `Platform.terminalView()` (cluster G1) rather than by naming an
 * engine here. [workspaceTerminalContent] is a test seam: a SwingPanel/AndroidView cannot be hosted
 * under runComposeUiTest, so UI tests inject a pure-Compose stand-in. Production always uses the
 * default, which is the platform's own engine.
 *
 * The kind `editor` is not one pane but three (spec §7.2), chosen by its view state's `mode`:
 * `tree` draws the explorer, `file` draws ONE document, `diff` draws the diff. An absent or unknown
 * mode draws the tree, which is what an `editor` view has always drawn — an old row must keep
 * working.
 */
@Composable
fun ViewHost(
    view: ViewDto,
    workspaceId: String,
    workdir: String,
    actions: ShellActions,
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
    /** Markdown preview per view id — the file's TAB owns the toggle, so the state is hoisted. A
     *  host whose tabs have no preview affordance leaves this at the default. */
    previewModeFor: (String) -> Boolean = { false },
    /** Close THIS view — the diff pane's close button is a tab close, not a mode toggle. */
    onCloseView: () -> Unit = {},
    /**
     * Workspace primary session — used only for LSP + review (still session-keyed in this phase).
     * A workspace with no chat view passes null and the editor says so.
     */
    primarySessionId: String? = null,
    /** After continue-in-new-conversation — select the new chat session. */
    onSelectSession: (String) -> Unit = {},
    /** Which chat chrome this host wants; see [ChatHeaderMode]. */
    chatHeaderMode: ChatHeaderMode = defaultChatHeaderMode(),
    /** What the chat panel READS, per session id. A host with a store passes its own builder. */
    chatState: @Composable (sessionId: String) -> ChatState = { ChatState() },
    /** What the chat panel DOES, per session. A host with a store passes its own builder. */
    chatActions: @Composable (session: SessionInfo) -> ChatActions = { ChatActions() },
    workspaceTerminalContent: @Composable (connect: () -> TerminalClient, modifier: Modifier) -> Unit =
        { connect, mod ->
            LocalPlatform.current.terminalView()
                .TerminalView(connect = connect, modifier = mod, active = true, onExit = null)
        },
    /**
     * The agent's raw PTY, drawn behind a chat view's Chat⇄Native pill. Same test-seam reason as
     * [workspaceTerminalContent].
     */
    chatNativeContent: @Composable (connect: () -> TerminalClient, active: Boolean, onExit: () -> Unit) -> Unit =
        { connect, active, onExit ->
            LocalPlatform.current.terminalView().TerminalView(
                connect = connect,
                modifier = Modifier.fillMaxSize(),
                active = active,
                onExit = onExit,
            )
        },
    /** Off-by-default headless hook (SM_LINKS_MENU): the session whose links menu to force-open. */
    forceLinksMenuFor: String? = null,
    onForceLinksMenuConsumed: () -> Unit = {},
    /**
     * One-shot composer requests, each addressed to ONE session so only that session's chat view
     * consumes it: `SM_CHAT_ATTACH`, `SM_DICTATE`, and — the one that is NOT a test hook — Edit ▸
     * Paste image in the native menu bar, which targets the SELECTED session.
     */
    externalAttach: Pair<String, ComposerExternalAttach>? = null,
    onExternalAttachConsumed: () -> Unit = {},
    externalDictate: Pair<String, ComposerExternalDictate>? = null,
    onExternalDictateConsumed: () -> Unit = {},
    pasteImageFor: String? = null,
    pasteImageRequestNonce: Long = 0L,
    onPasteImageRequestConsumed: () -> Unit = {},
    /**
     * Test seam for the `file` pane's code surface: a browser cannot boot under runComposeUiTest,
     * so tests inject a factory that never builds an engine. Null → this platform's own.
     */
    editorEngineFactory: EditorEngineFactory? = null,
) {
    when (view.kind) {
        "chat" -> {
            val sessionId = view.chatSessionId()
            if (sessionId == null) UnknownViewHint(view.kind, modifier)
            else ChatViewPane(
                actions = actions,
                sessionId = sessionId,
                workdir = workdir,
                drafts = drafts,
                headerMode = chatHeaderMode,
                chatState = chatState,
                chatActions = chatActions,
                onSelectSession = onSelectSession,
                onOpenFile = onOpenFile,
                onOpenWalkthrough = { stepId -> onOpenWalkthrough(sessionId, stepId) },
                nativeContent = chatNativeContent,
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
                else AgentTerminalForSession(actions, sessionId, terminalId, modifier)
            } else {
                key(workspaceId, terminalId) {
                    workspaceTerminalContent(
                        { actions.connectWorkspaceTerminal(workspaceId, terminalId).orFail(workspaceId) },
                        modifier.fillMaxSize().testTag("terminal-$workspaceId-$terminalId"),
                    )
                }
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
                        actions = actions,
                        workspaceId = workspaceId,
                        workdir = workdir,
                        path = path,
                        documents = documents ?: rememberWorkspaceDocuments(actions, workspaceId),
                        // LSP is still keyed by session. A workspace with no chat view gets no code
                        // intelligence — say so rather than looking broken.
                        lspSessionId = primarySessionId,
                        engineFactory = editorEngineFactory,
                        modifier = modifier.testTag("editor-$workdir"),
                    )
                "diff" -> DiffPaneForWorkspace(
                    actions = actions,
                    workspaceId = workspaceId,
                    viewId = view.id,
                    base = view.stateString("diffBase"),
                    lspSessionId = primarySessionId,
                    walkthroughSessionId = walkthroughSessionId,
                    onWalkthroughClosed = onWalkthroughClosed,
                    onClose = onCloseView,
                    onOpenFile = onOpenFile,
                    modifier = modifier.testTag("editor-$workdir"),
                )
                else -> ExplorerPaneForWorkspace(
                    actions = actions,
                    workspaceId = workspaceId,
                    workdir = workdir,
                    onOpenFile = { p -> onOpenFile(p, null, null) },
                    modifier = modifier.testTag("editor-$workdir"),
                )
            }
        }
        "display" -> {
            // Not `?: hint`: BOTH hosts mint a Display view with an EMPTY displayId (the stream is
            // started separately), and `stateString` hands that back as "". A blank id therefore
            // means "whatever is running here", not "a stream that has gone away".
            DisplayPanelForStream(actions, view.stateString("displayId").orEmpty(), modifier)
        }
        else -> UnknownViewHint(view.kind, modifier)
    }
}

/**
 * Chat adapter. [headerMode] picks the chrome:
 *  - PANEL: the panel's own header, with the links/overflow slots filled.
 *  - BAR:   a [ChatViewHeader] above a header-less panel, and the native PTY kept alive beside it.
 *  - NONE:  a bare panel.
 */
@Composable
private fun ChatViewPane(
    actions: ShellActions,
    sessionId: String,
    workdir: String,
    drafts: SnapshotStateMap<String, String>,
    headerMode: ChatHeaderMode,
    chatState: @Composable (String) -> ChatState,
    chatActions: @Composable (SessionInfo) -> ChatActions,
    onSelectSession: (String) -> Unit,
    onOpenFile: (path: String, line: Int?, endLine: Int?) -> Unit,
    onOpenWalkthrough: (stepId: String?) -> Unit,
    nativeContent: @Composable (connect: () -> TerminalClient, active: Boolean, onExit: () -> Unit) -> Unit,
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
    val sessions by actions.sessions.collectAsState()
    val finishJobs by actions.finishJobs.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        UnknownViewHint("chat", modifier)
        return
    }
    val notices = LocalPlatform.current.notices
    val finish = FinishBindings(
        job = finishJobs[sessionId],
        readiness = { actions.finishReadiness(sessionId) },
        // On the STORE's scope, not this panel's: switching views mid-kickoff must not cancel the
        // request.
        finish = { action, skipVerify, commitFirst, commitMessage, onKickoff ->
            actions.kickoffFinish(sessionId, action, skipVerify, commitFirst, commitMessage, onKickoff)
        },
        clearJob = { actions.clearFinishJob(sessionId) },
        verifySuggest = { actions.verifySuggest(sessionId) },
        verifySave = { actions.verifySave(sessionId, it) },
        sendToAgent = { actions.sendMessage(sessionId, it) },
    )
    // A tap on a file path in the transcript opens a `file` pane. A path outside the workspace has
    // no workdir-relative form and is reported rather than opened.
    val openTappedPath: (FilePathRef) -> Unit = { ref ->
        val rel = workspaceOpenPath(ref, workdir)
        if (rel == null) notices.show("File is outside this workspace") else onOpenFile(rel, ref.line, ref.endLine)
    }
    val state = chatState(sessionId)
    val acts = chatActions(session)

    if (headerMode == ChatHeaderMode.PANEL) {
        ChatPanel(
            session = session,
            state = state,
            actions = acts,
            draft = drafts[sessionId] ?: "",
            onDraftChange = { drafts[sessionId] = it },
            modifier = modifier.fillMaxSize().testTag(WorkspaceChatPaneTestIds.VIEW_CHAT),
            showHeader = true,
            onOpenWalkthrough = onOpenWalkthrough,
            // Mute is the one session control this panel can perform without a dialog; rename/kill
            // live in OverflowMenu (the header slot below), so they stay out of the slash menu here.
            onRequestMute = { actions.setMute(sessionId, !(session.mute ?: false)) },
            finish = finish,
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
                    onRename = { name -> actions.rename(sessionId, name) },
                    onToggleMute = { muted -> actions.setMute(sessionId, muted) },
                    onKill = { actions.kill(sessionId) },
                    onContinue = { handoff ->
                        actions.continueConversation(
                            session,
                            handoff.message,
                            handoff.agent,
                            handoff.model,
                            handoff.reasoningLevel,
                        )
                    },
                    loadContinueAgents = { actions.launcherAgents() },
                    loadContinueModels = { actions.launcherModels(it) },
                    loadContinueReasoning = { agent, model -> actions.launcherReasoning(agent, model) },
                    onContinued = onSelectSession,
                    showManagementRows = false,
                )
            },
            // key(sessionId) so a view rebound to another session never reuses the previous
            // session's agent PTY: a terminal surface's `remember { connect() }` is unkeyed.
            nativeContent = { onExit ->
                key(sessionId) {
                    nativeContent({ actions.connectAgentTerminal(sessionId).orFail(sessionId) }, true, onExit)
                }
            },
            externalAttach = externalAttach,
            onExternalAttachConsumed = onExternalAttachConsumed,
            externalDictate = externalDictate,
            onExternalDictateConsumed = onExternalDictateConsumed,
            pasteImageRequestNonce = pasteImageRequestNonce,
            onPasteImageRequestConsumed = onPasteImageRequestConsumed,
            onOpenFile = openTappedPath,
        )
        return
    }

    // ── Touch branches: the panel draws no header of its own ──────────────────────────────────
    var nativeView by remember(sessionId) { mutableStateOf(false) }
    val body: @Composable (Modifier) -> Unit = { paneMod ->
        ChatPanel(
            session = session,
            state = state,
            actions = acts,
            draft = drafts[sessionId] ?: "",
            onDraftChange = { drafts[sessionId] = it },
            showHeader = false,
            active = !nativeView,
            onOpenWalkthrough = onOpenWalkthrough,
            onRequestMute = { actions.setMute(sessionId, !(session.mute ?: false)) },
            externalAttach = externalAttach,
            onExternalAttachConsumed = onExternalAttachConsumed,
            externalDictate = externalDictate,
            onExternalDictateConsumed = onExternalDictateConsumed,
            pasteImageRequestNonce = pasteImageRequestNonce,
            onPasteImageRequestConsumed = onPasteImageRequestConsumed,
            onOpenFile = openTappedPath,
            modifier = paneMod.fillMaxSize().testTag(WorkspaceChatPaneTestIds.VIEW_CHAT),
        )
    }
    if (headerMode == ChatHeaderMode.NONE) {
        Box(modifier.fillMaxSize().testTag(WorkspaceChatPaneTestIds.CHAT_VIEW)) { body(Modifier) }
        return
    }

    var sessionLinks by remember(sessionId) { mutableStateOf<List<ProxyDto>>(emptyList()) }
    // Load-on-open through the panel's OWN holder, so the bar and the panel header cannot disagree
    // about which proxies a session has.
    LaunchedEffect(sessionId, session.name, acts) { sessionLinks = acts.loadProxies() }
    val gitOp = rememberGitOpRunner(sessionId, actions)
    Column(modifier.fillMaxSize().testTag(WorkspaceChatPaneTestIds.CHAT_VIEW)) {
        ChatViewHeader(
            session = session,
            working = state.agent?.working == true,
            nativeView = nativeView,
            onSetNative = { nativeView = it },
            sessionLinks = sessionLinks,
            finish = finish,
            onGitOp = gitOp,
            onRename = { name -> actions.rename(sessionId, name) },
            onToggleMute = { muted -> actions.setMute(sessionId, muted) },
            onKill = { actions.kill(sessionId) },
            onContinue = { handoff ->
                actions.continueConversation(
                    session,
                    handoff.message,
                    handoff.agent,
                    handoff.model,
                    handoff.reasoningLevel,
                )
            },
            loadContinueAgents = { actions.launcherAgents() },
            loadContinueModels = { actions.launcherModels(it) },
            loadContinueReasoning = { agent, model -> actions.launcherReasoning(agent, model) },
            onContinued = onSelectSession,
        )
        Box(Modifier.weight(1f).fillMaxSize()) {
            // KeepAlivePanel rather than the alpha modifier: the native half below IS a terminal
            // on every host that has one, and a terminal is a platform view its compositor draws
            // outside the Compose layer (UIKit interop on iOS, a heavyweight SwingPanel on
            // desktop). Alpha does not hide either, so the hidden half would paint over the shown
            // one. Android's actual is the same alpha hide as before.
            KeepAlivePanel(visible = !nativeView) { body(Modifier) }
            if (session.agent == "claude") {
                KeepAlivePanel(visible = nativeView) {
                    key(sessionId) {
                        nativeContent(
                            { actions.connectAgentTerminal(sessionId).orFail(sessionId) },
                            nativeView,
                        ) { nativeView = false }
                    }
                }
            }
        }
    }
}

/**
 * [id] when a live session actually carries it, else null.
 *
 * Desktop resolved every session-keyed pane parameter off the store's own list before handing it
 * down; the holder is keyed by id, so the check lives here.
 */
@Composable
private fun ShellActions.resolvedSessionId(id: String?): String? {
    if (id == null) return null
    val live by sessions.collectAsState()
    return id.takeIf { wanted -> live.any { it.id == wanted } }
}

/**
 * The holder's terminal members are nullable ("no host owns that id"); the engine seam is not, and
 * both real builders return a client for every id a mounted view can name. A null here is a wiring
 * bug in the shell above, not a state the pane can draw — say which id, loudly. The call is LAZY
 * (see `LazyTerminalClient`), so nothing is evaluated until a terminal is actually shown.
 */
private fun TerminalClient?.orFail(id: String): TerminalClient =
    this ?: error("No host owns '$id' — cannot open a terminal")

/**
 * A tapped file-path reference as a workdir-relative path, or null when it points outside the
 * workspace (nothing the workspace's fs endpoints could read). Split out of the composable so the
 * conversion is testable without hosting a transcript.
 */
fun workspaceOpenPath(ref: FilePathRef, workdir: String): String? =
    toWorkdirRelativePath(ref.path, workdir, inferHomeDir(workdir))

/**
 * Session-scoped terminal adapter:
 * - terminalId "agent" → the agent PTY
 * - any other id → a session scratch terminal
 *
 * key(sessionId, terminalId) so a view switch does not reuse the wrong terminal client.
 */
@Composable
private fun AgentTerminalForSession(
    actions: ShellActions,
    sessionId: String,
    terminalId: String,
    modifier: Modifier,
) {
    val terminals = LocalPlatform.current.terminalView()
    key(sessionId, terminalId) {
        terminals.TerminalView(
            connect = {
                if (terminalId == "agent") actions.connectAgentTerminal(sessionId).orFail(sessionId)
                else actions.connectTerminal(sessionId, terminalId).orFail(sessionId)
            },
            modifier = modifier.fillMaxSize().testTag(
                if (terminalId == "agent") "view_terminal_agent" else "view_terminal",
            ),
            active = true,
            onExit = null,
        )
    }
}

/**
 * Fallback store for a `file` pane whose caller did not hand one down. Correct but NOT what the
 * workspace does: a store made here is scoped to THIS pane, so two panes on one path would hold two
 * buffers. The shells always pass the workspace's own store; this exists so a `file` view still
 * draws (rather than crashing) at any call site that has not been wired yet.
 */
@Composable
private fun rememberWorkspaceDocuments(actions: ShellActions, workspaceId: String): DocumentStore {
    val scope = rememberCoroutineScope()
    return remember(workspaceId, actions) {
        DocumentStore(
            fsRead = { p -> actions.workspaceFsRead(workspaceId, p) },
            fsWrite = { p, content -> actions.workspaceFsWrite(workspaceId, p, content) },
            scope = scope,
        )
    }
}

/** Explorer adapter — the file tree + filename search over `/workspaces/:id/fs*`. */
@Composable
private fun ExplorerPaneForWorkspace(
    actions: ShellActions,
    workspaceId: String,
    workdir: String,
    onOpenFile: (String) -> Unit,
    modifier: Modifier,
) {
    // Per-explorer-pane state: two explorer panes may be expanded to different depths, which is
    // fine — the tree is a view of the disk, not of anything the workspace owns.
    val explorer = remember(workspaceId) { ExplorerState() }
    ExplorerPane(
        fsList = { p -> actions.workspaceFsListResult(workspaceId, p) },
        explorer = explorer,
        workdir = workdir,
        onOpenFile = onOpenFile,
        fsSearch = { q -> actions.workspaceFsSearch(workspaceId, q) },
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
    actions: ShellActions,
    workspaceId: String,
    workdir: String,
    path: String,
    documents: DocumentStore,
    lspSessionId: String?,
    engineFactory: EditorEngineFactory?,
    modifier: Modifier,
) {
    // Resolve the id against the LIVE session list: a workspace whose primary session has been
    // killed still carries its id, and claiming LSP for a session no host owns would light the
    // pane's status up while every call no-ops. Unresolved -> the quiet "code intelligence is off"
    // note the pane already draws for a chat-less workspace.
    val lspSession = actions.resolvedSessionId(lspSessionId)
    // Editor prefs come from the shared SettingsStore (ui/prefs/UiPrefs.kt): the collected values
    // start at the defaults for one frame, then settle on what was persisted.
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val lineWrap by prefs.editorLineWrap.collectAsState(EDITOR_LINE_WRAP_DEFAULT)
    val fontSize by prefs.editorFontSize.collectAsState(EDITOR_FONT_DEFAULT)

    FilePane(
        previewMode = previewMode,
        path = path,
        documents = documents,
        fsRead = { p -> actions.workspaceFsRead(workspaceId, p) },
        workdir = workdir,
        lspSessionId = lspSession,
        lspStatus = actions.lspStatus,
        lspRpc = actions.lspRpc,
        lspStatusQuery = { id, p -> actions.lspStatusQuery(id, p) },
        lspOpen = { id, serverId -> actions.lspOpen(id, serverId) },
        lspRpcOut = { id, serverId, message -> actions.lspRpcOut(id, serverId, message) },
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
    actions: ShellActions,
    workspaceId: String,
    viewId: String,
    base: String?,
    lspSessionId: String?,
    walkthroughSessionId: String?,
    onWalkthroughClosed: () -> Unit,
    onClose: () -> Unit,
    onOpenFile: (path: String, line: Int?, endLine: Int?) -> Unit,
    modifier: Modifier,
) {
    // Same resolution rule as the file pane: review and walkthrough are session-keyed, and a
    // stale id would offer comment/submit affordances that can only no-op.
    val reviewSessionId = actions.resolvedSessionId(lspSessionId)
    val selectedWalkthroughId = actions.resolvedSessionId(walkthroughSessionId ?: lspSessionId)
    // Per-diff-pane state, seeded from the view's own `diffBase` so a saved row reopens on the
    // base it was looking at.
    val diff = remember(workspaceId, viewId, base) { DiffState().apply { base?.let { diffBase = it } } }
    // The holder is null where the host installed no walkthrough seam (the builder checks the cap).
    val walkthrough = selectedWalkthroughId?.let { actions.walkthroughState(it) }
    val reviewWalkthrough = reviewSessionId?.let { actions.walkthroughState(it) }
    DiffPane(
        diff = diff,
        walkthrough = walkthrough,
        reviewWalkthrough = reviewWalkthrough,
        fsDiff = { spec -> actions.workspaceFsDiff(workspaceId, spec) },
        fsRefs = { actions.workspaceFsRefs(workspaceId) },
        getWalkthrough = { selectedWalkthroughId?.let { actions.getWalkthrough(it) } },
        getWalkthroughComments = {
            selectedWalkthroughId?.let { actions.reviewComments(it) }.orEmpty()
        },
        getReviewComments = { reviewSessionId?.let { actions.reviewComments(it) }.orEmpty() },
        readWalkthroughFile = { repo, path ->
            actions.workspaceFsRead(workspaceId, if (repo.isBlank()) path else "$repo/$path")
        },
        onOpenWalkthroughFile = { repo, path, line ->
            onOpenFile(if (repo.isBlank()) path else "$repo/$path", line, null)
        },
        // Review stays session-keyed; only available when we have a primary chat.
        onReviewAddComment = { body -> reviewSessionId?.let { actions.reviewAddComment(it, body) } },
        onReviewResolve = { commentId -> reviewSessionId?.let { actions.reviewResolve(it, commentId) } == true },
        onWalkthroughAddComment = { body ->
            selectedWalkthroughId?.let { actions.reviewAddComment(it, body) }
        },
        onWalkthroughResolve = { commentId ->
            selectedWalkthroughId?.let { actions.reviewResolve(it, commentId) } == true
        },
        onWalkthroughClosed = onWalkthroughClosed,
        onReviewSubmit = { reviewSessionId?.let { actions.reviewSubmit(it) } },
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Display adapter for a view that names a stream by id. Resolves the stream's session name and
 * reuses the shared [DisplayPanel] (cluster G4).
 */
@Composable
private fun DisplayPanelForStream(
    actions: ShellActions,
    displayId: String,
    modifier: Modifier,
) {
    val live by actions.display.displays.collectAsState()
    LaunchedEffect(displayId) { actions.display.listDisplays() }
    // A named id resolves to THAT stream; a blank one (a view added before any stream exists —
    // both hosts create it that way) adopts whichever stream is running.
    val stream =
        if (displayId.isNotBlank()) live.firstOrNull { it.id == displayId }
        else live.firstOrNull { it.status == "running" }
    if (stream == null) {
        Box(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .testTag("view_display_pending"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (displayId.isBlank()) "No display is running"
                else "Display $displayId is not running",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        return
    }
    DisplayPanel(
        sessionName = stream.sessionName,
        actions = actions.display,
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
