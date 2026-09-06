package dev.supermux.android.workspace

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import dev.supermux.android.AppViewModel
import dev.supermux.ui.display.DisplayPanel
import dev.supermux.ui.display.rememberDisplayActions
import dev.supermux.ui.editor.DiffPane
import dev.supermux.ui.editor.ExplorerPane
import dev.supermux.ui.editor.FilePane
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.widgets.keepAlivePanel
import dev.supermux.ui.theme.Space
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString
import dev.supermux.session.inferHomeDir
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.TestIds
import dev.supermux.ui.editor.DiffState
import dev.supermux.ui.editor.ExplorerState
import dev.supermux.ui.toWorkdirRelativePath
import dev.supermux.ui.workspace.WorkspaceSession
import kotlinx.coroutines.launch
import dev.supermux.ui.chat.ChatActions
import dev.supermux.ui.chat.ChatPanel
import dev.supermux.ui.chat.ChatState
import dev.supermux.ui.chat.ComposerActions
import dev.supermux.ui.prefs.LocalUiPrefs
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first

/** Journey + desktop-parity tags for the workspace chat pane. */
internal object WorkspaceChatPaneTestIds {
    const val CHAT_VIEW = TestIds.CHAT_VIEW
    const val VIEW_CHAT = "view_chat"
}

/**
 * Dispatches a broker view onto native Android panes. Kind + parsed state match desktop ViewHost.
 */
@Composable
fun AndroidViewHost(
    workspace: WorkspaceDto,
    view: ViewDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    onSelectSession: (String) -> Unit = {},
) {
    when (view.kind) {
        "chat" -> {
            val sessionId = view.chatSessionId()
            if (sessionId == null) UnknownViewHint(view.kind, modifier)
            else ChatViewPane(sessionId, workspace, session, vm, modifier, wide, onSelectSession)
        }
        "terminal" -> {
            val scope = view.stateString("scope") ?: "workspace"
            val terminalId = view.stateString("terminalId") ?: "main"
            if (scope == "session") {
                val sessionId = view.stateString("sessionId")
                if (sessionId == null) UnknownViewHint(view.kind, modifier)
                else AgentTerminalPane(vm, sessionId, terminalId, modifier)
            } else {
                key(workspace.id, terminalId) {
                    // Cluster G1: mounted through `Platform.terminalView()` rather than naming
                    // termlib here, so cluster G7 can move this host into `:ui` unchanged.
                    LocalPlatform.current.terminalView().TerminalView(
                        connect = { vm.fleet.connectWorkspaceTerminal(workspace.id, terminalId) },
                        modifier = modifier.fillMaxSize().testTag("terminal-${workspace.id}-$terminalId"),
                        active = true,
                        onExit = null,
                    )
                }
            }
        }
        "editor" -> {
            val path = view.stateString("path")
            when (view.stateString("mode")) {
                "file" ->
                    if (path == null) UnknownViewHint(view.kind, modifier)
                    else FileViewPane(workspace, path, session, vm, modifier)
                "diff" -> DiffViewPane(workspace, view, session, vm, modifier)
                else -> ExplorerViewPane(workspace, session, vm, modifier)
            }
        }
        "display" -> {
            val displayId = view.stateString("displayId")
            DisplayViewPane(workspace, displayId, vm, modifier)
        }
        else -> UnknownViewHint(view.kind, modifier)
    }
}

@Composable
private fun ChatViewPane(
    sessionId: String,
    workspace: WorkspaceDto,
    wsSession: WorkspaceSession,
    vm: AppViewModel,
    modifier: Modifier,
    wide: Boolean,
    onSelectSession: (String) -> Unit,
) {
    val sessions by vm.fleet.sessions.collectAsState()
    val messages by vm.fleet.messages.collectAsState()
    val activity by vm.fleet.activity.collectAsState()
    val agentState by vm.fleet.agentState.collectAsState()
    val pendingSend by vm.fleet.pendingSend.collectAsState()
    val commands by vm.fleet.commands.collectAsState()
    val commandsResolved by vm.fleet.commandsResolved.collectAsState()
    val bgTasksAll by vm.fleet.bgTasks.collectAsState()
    val finishJobs by vm.fleet.finishJobs.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        UnknownViewHint("chat", modifier)
        return
    }
    val context = LocalContext.current
    var nativeView by remember(sessionId) { mutableStateOf(false) }
    var sessionLinks by remember(sessionId) { mutableStateOf<List<dev.supermux.net.ProxyDto>>(emptyList()) }
    LaunchedEffect(sessionId, session.name) {
        sessionLinks = vm.fleet.proxies().filter { it.sessionName == session.name }
    }
    var chatDraft by remember(sessionId) { mutableStateOf("") }
    val chatActions = remember(sessionId) {
        ChatActions(
            send = { text, atts -> vm.fleet.sendWith(sessionId, text, atts) },
            interrupt = { vm.fleet.interrupt(sessionId) },
            upload = { source, name, mime, kind, onProgress ->
                vm.fleet.uploadResumable(sessionId, source, name, mime, kind, onProgress)
            },
            transcribeAudio = { bytes, name -> vm.fleet.transcribeAudio(sessionId, bytes, name) },
            loadBytes = { vm.fleet.fileBytes(it) },
            composer = ComposerActions(
                loadDraft = { vm.fleet.loadDraft(it) },
                saveDraft = { id, t -> vm.fleet.saveDraft(id, t) },
                consumePendingFirst = { id ->
                    vm.fleet.consumePendingFirst(id)?.let { it.text to it.attachments }
                },
                transcribeDraft = { draft -> vm.fleet.transcribeDraft(sessionId, draft) },
                loadGlossary = { vm.fleet.fetchGlossary().orEmpty() },
            ),
            loadModels = { vm.fleet.sessionModels(sessionId) },
            loadReasoning = { vm.fleet.sessionReasoning(sessionId) },
            pickModel = { vm.fleet.switchModel(sessionId, it) },
            pickReasoning = { vm.fleet.switchReasoning(sessionId, it) },
        )
    }
    val chatBody: @Composable (Modifier) -> Unit = { paneMod ->
        ChatPanel(
            session = session,
            state = ChatState(
                messages = messages[sessionId] ?: emptyList(),
                activity = activity[sessionId] ?: emptyList(),
                agent = agentState[sessionId],
                bgTasks = bgTasksAll[sessionId] ?: emptyList(),
                sending = pendingSend.contains(sessionId),
                commands = commands[sessionId] ?: emptyList(),
                commandsResolved = commandsResolved[sessionId] ?: false,
            ),
            actions = chatActions,
            draft = chatDraft,
            onDraftChange = { chatDraft = it },
            showHeader = false,
            active = !nativeView,
            onOpenFile = { ref ->
                val rel = toWorkdirRelativePath(ref.path, workspace.workdir, inferHomeDir(workspace.workdir))
                if (rel == null) {
                    Toast.makeText(context, "File is outside this workspace", Toast.LENGTH_SHORT).show()
                } else {
                    wsSession.fileOpener.open(rel)
                }
            },
            modifier = paneMod.fillMaxSize().testTag(WorkspaceChatPaneTestIds.VIEW_CHAT),
        )
    }
    if (!wide) {
        Box(modifier.fillMaxSize().testTag(WorkspaceChatPaneTestIds.CHAT_VIEW)) {
            chatBody(Modifier)
        }
        return
    }
    Column(modifier.fillMaxSize().testTag(WorkspaceChatPaneTestIds.CHAT_VIEW)) {
        ChatViewHeader(
            session = session,
            working = agentState[sessionId]?.working == true,
            nativeView = nativeView,
            onSetNative = { nativeView = it },
            sessionLinks = sessionLinks,
            finishJob = finishJobs[sessionId],
            onFinishReadiness = { vm.fleet.finishReadiness(sessionId) },
            onFinish = { action, skipVerify, commitFirst, commitMessage, onKickoff ->
                vm.fleet.finish(sessionId, action, skipVerify, commitFirst, commitMessage, onKickoff = onKickoff)
            },
            onClearFinishJob = { vm.fleet.clearFinishJob(sessionId) },
            onVerifySuggest = { vm.fleet.verifySuggest(sessionId) },
            onVerifySave = { vm.fleet.verifySave(sessionId, it) },
            onSendToAgent = { vm.fleet.sendMessage(sessionId, it) },
            onGitOp = { op ->
                val cb: (dev.supermux.net.GitOpResult?) -> Unit = { toastGitOp(context, it) }
                when (op) {
                    "fetch" -> vm.fleet.gitFetch(sessionId, cb)
                    "pull" -> vm.fleet.gitPull(sessionId, cb)
                    "push" -> vm.fleet.gitPush(sessionId, cb)
                    "publish" -> vm.fleet.gitPublish(sessionId, cb)
                }
            },
            onContinue = { handoff ->
                val recordId = vm.fleet.sessionHost.value[sessionId] ?: vm.fleet.activeHost.value
                    ?: throw IllegalStateException("No host")
                vm.fleet.continueInNewConversation(recordId, sessionId, handoff)
            },
            loadContinueAgents = { vm.fleet.agentStatuses().orEmpty().filter { it.installed }.map { it.kind } },
            loadContinueModels = { vm.fleet.launcherModels(it) },
            loadContinueReasoning = { ag, md -> vm.fleet.launcherReasoning(ag, md) },
            onContinued = onSelectSession,
        )
        Box(Modifier.weight(1f).fillMaxSize()) {
            Box(Modifier.keepAlivePanel(!nativeView)) { chatBody(Modifier) }
            if (session.agent == "claude") {
                Box(Modifier.keepAlivePanel(nativeView)) {
                    LocalPlatform.current.terminalView().TerminalView(
                        connect = { vm.fleet.connectAgentTerminal(sessionId) },
                        modifier = Modifier.fillMaxSize(),
                        active = nativeView,
                        onExit = { nativeView = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentTerminalPane(
    vm: AppViewModel,
    sessionId: String,
    terminalId: String,
    modifier: Modifier,
) {
    val terminals = LocalPlatform.current.terminalView()
    key(sessionId, terminalId) {
        terminals.TerminalView(
            connect = {
                if (terminalId == "agent") vm.fleet.connectAgentTerminal(sessionId)
                else vm.fleet.connectTerminal(sessionId, terminalId)
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
 * The three editor panes are the SHARED ones (`:ui` `editor/EditorPanes.kt`) — desktop's
 * ExplorerPane/FilePane/DiffPane, which Android used to re-implement one composable at a time.
 * What stays here is the Android adapter: the view-kind dispatch above, and the `vm.fleet` lambdas
 * each pane asks for.
 */
@Composable
private fun ExplorerViewPane(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    modifier: Modifier,
) {
    // Per-explorer-pane state: two explorer panes may be expanded to different depths.
    val explorer = remember(workspace.id) { ExplorerState() }
    ExplorerPane(
        fsList = { p -> vm.fleet.workspaceFsListResult(workspace.id, p) },
        explorer = explorer,
        workdir = workspace.workdir,
        onOpenFile = { p -> session.fileOpener.open(p) },
        fsSearch = { q -> vm.fleet.workspaceFsSearch(workspace.id, q) },
        modifier = modifier.fillMaxSize().testTag("editor-${workspace.workdir}"),
    )
}

@Composable
private fun FileViewPane(
    workspace: WorkspaceDto,
    path: String,
    session: WorkspaceSession,
    vm: AppViewModel,
    modifier: Modifier,
) {
    val editorPrefs = LocalUiPrefs.current
    // Hold the first frame until the persisted prefs land, so the surface is not born on the
    // defaults and then re-pushed (the panel does the same).
    val loadedPrefs by produceState<Pair<Boolean, Int>?>(null, editorPrefs) {
        value = editorPrefs.editorLineWrap.first() to editorPrefs.editorFontSize.first()
    }
    val (lineWrap, initialFontSize) = loadedPrefs ?: return
    val fontSize by editorPrefs.editorFontSize.collectAsState(initialFontSize)
    val scope = rememberCoroutineScope()
    val lspSessionId = workspace.primarySessionId
    FilePane(
        path = path,
        documents = session.documents,
        fsRead = { p -> vm.fleet.workspaceFsRead(workspace.id, p) },
        workdir = workspace.workdir,
        // LSP is still keyed by session: a workspace with no chat view gets no code intelligence,
        // and the pane says so rather than looking broken.
        lspSessionId = lspSessionId,
        lspStatus = vm.fleet.lspStatus,
        lspRpc = vm.fleet.lspRpc,
        lspStatusQuery = { _, p -> if (lspSessionId != null) vm.fleet.lspStatusQuery(lspSessionId, p) },
        lspOpen = { _, serverId -> if (lspSessionId != null) vm.fleet.lspOpen(lspSessionId, serverId) },
        lspRpcOut = { _, serverId, message ->
            if (lspSessionId != null) vm.fleet.lspRpcOut(lspSessionId, serverId, message)
        },
        lineWrap = lineWrap,
        fontSize = fontSize,
        onFontSize = { px -> scope.launch { editorPrefs.putEditorFontSize(px) } },
        previewMode = session.previewModes[path] == true,
        modifier = modifier.fillMaxSize().testTag("editor-${workspace.workdir}"),
    )
}

@Composable
private fun DiffViewPane(
    workspace: WorkspaceDto,
    view: ViewDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    modifier: Modifier,
) {
    val diff = remember(workspace.id, view.id) {
        DiffState().apply { view.stateString("diffBase")?.let { diffBase = it } }
    }
    val primary = workspace.primarySessionId
    val app = primary?.let { vm.fleet.appFor(it) }
    val sessions by vm.fleet.sessions.collectAsState()
    val reviewSession = primary?.let { id -> sessions.firstOrNull { it.id == id } }
    // Gate the holder READ on the capability too (not only the toggle in DiffPane): a host built
    // without a WalkthroughSeam throws from walkthroughState(), so the cap must protect this site.
    val walkthroughCap = LocalPlatform.current.caps.walkthrough
    val walkthrough = if (walkthroughCap && app != null && reviewSession != null) {
        app.walkthroughState<WalkthroughState>(reviewSession.id)
    } else {
        null
    }
    DiffPane(
        diff = diff,
        walkthrough = walkthrough,
        fsDiff = { spec -> vm.fleet.workspaceFsDiff(workspace.id, spec) },
        fsRefs = { vm.fleet.workspaceFsRefs(workspace.id) },
        getWalkthrough = { if (app != null && reviewSession != null) app.getWalkthrough(reviewSession) else null },
        getWalkthroughComments = {
            if (app != null && reviewSession != null) app.reviewComments(reviewSession) else emptyList()
        },
        getReviewComments = {
            if (app != null && reviewSession != null) app.reviewComments(reviewSession) else emptyList()
        },
        readWalkthroughFile = { repo, path ->
            vm.fleet.workspaceFsRead(workspace.id, if (repo.isBlank()) path else "$repo/$path")
        },
        onOpenWalkthroughFile = { repo, path, _ ->
            session.fileOpener.open(if (repo.isBlank()) path else "$repo/$path")
        },
        onReviewAddComment = { body -> if (primary != null) vm.fleet.reviewAddComment(primary, body) else null },
        onReviewResolve = { id -> if (primary != null) vm.fleet.reviewResolve(primary, id) else false },
        onReviewSubmit = { if (primary != null) vm.fleet.reviewSubmit(primary) else null },
        onClose = {},
        modifier = modifier.fillMaxSize().testTag("editor-${workspace.workdir}"),
    )
}

@Composable
private fun DisplayViewPane(
    workspace: WorkspaceDto,
    displayId: String?,
    vm: AppViewModel,
    modifier: Modifier,
) {
    val live by vm.fleet.displays.collectAsState()
    LaunchedEffect(displayId) { vm.fleet.listDisplays() }
    val stream = when {
        !displayId.isNullOrBlank() -> live.firstOrNull { it.id == displayId }
        else -> live.firstOrNull { it.status == "running" }
    }
    val sessionName = stream?.sessionName ?: workspace.name
    DisplayPanel(
        sessionName = sessionName,
        actions = rememberDisplayActions(vm.fleet),
        modifier = modifier.fillMaxSize().testTag("view_display"),
    )
}

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
            )
            Text(
                "Update Supermux to open this view",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}
