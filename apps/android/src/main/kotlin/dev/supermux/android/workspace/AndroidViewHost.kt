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
import dev.supermux.android.chat.ChatPanel
import dev.supermux.android.chat.SessionPanel
import dev.supermux.android.display.DisplayPanel
import dev.supermux.android.editor.DiffView
import dev.supermux.ui.editor.FileTree
import dev.supermux.android.editor.WebCodeEditor
import dev.supermux.android.editor.rememberEditorEngine
import dev.supermux.ui.widgets.keepAlivePanel
import dev.supermux.android.terminal.TerminalPanel
import dev.supermux.ui.theme.Space
import dev.supermux.net.AddCommentBody
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
                    TerminalPanel(
                        connect = { vm.fleet.connectWorkspaceTerminal(workspace.id, terminalId) },
                        modifier = modifier.fillMaxSize().testTag("terminal-${workspace.id}-$terminalId"),
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
                "diff" -> DiffViewPane(workspace, view, vm, modifier)
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
    val chatBody: @Composable (Modifier) -> Unit = { paneMod ->
        ChatPanel(
            session = session,
            messages = messages[sessionId] ?: emptyList(),
            activity = activity[sessionId] ?: emptyList(),
            agent = agentState[sessionId],
            bgTasks = bgTasksAll[sessionId] ?: emptyList(),
            sending = pendingSend.contains(sessionId),
            activePanel = if (nativeView) SessionPanel.Native else SessionPanel.Chat,
            onSendWith = { text, atts -> vm.fleet.sendWith(sessionId, text, atts) },
            onInterrupt = { vm.fleet.interrupt(sessionId) },
            commands = commands[sessionId] ?: emptyList(),
            commandsResolved = commandsResolved[sessionId] ?: false,
            onUpload = { source, name, mime, kind, onProgress ->
                vm.fleet.uploadResumable(sessionId, source, name, mime, kind, onProgress)
            },
            loadBytes = { vm.fleet.fileBytes(it) },
            transcribeAudio = { bytes, name -> vm.fleet.transcribeAudio(sessionId, bytes, name) },
            transcribeDraft = { draft -> vm.fleet.transcribeDraft(sessionId, draft) },
            loadGlossary = { vm.fleet.fetchGlossary() },
            vmModels = { vm.fleet.sessionModels(it) },
            vmReasoning = { vm.fleet.sessionReasoning(it) },
            onPickModel = { vm.fleet.switchModel(sessionId, it) },
            onPickEffort = { vm.fleet.switchReasoning(sessionId, it) },
            loadDraft = { vm.fleet.loadDraft(it) },
            saveDraft = { id, t -> vm.fleet.saveDraft(id, t) },
            consumePendingFirst = { vm.fleet.consumePendingFirst(it) },
            onOpenFile = { ref ->
                val rel = toWorkdirRelativePath(ref.path, workspace.workdir, inferHomeDir(workspace.workdir))
                if (rel == null) {
                    Toast.makeText(context, "File is outside this workspace", Toast.LENGTH_SHORT).show()
                } else {
                    wsSession.fileOpener.open(rel)
                }
            },
            onRequestRename = {},
            onRequestMute = {},
            onRequestKill = {},
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
            loadContinueAgents = { vm.fleet.agentStatuses().filter { it.installed }.map { it.kind } },
            loadContinueModels = { vm.fleet.launcherModels(it) },
            loadContinueReasoning = { ag, md -> vm.fleet.launcherReasoning(ag, md) },
            onContinued = onSelectSession,
        )
        Box(Modifier.weight(1f).fillMaxSize()) {
            Box(Modifier.keepAlivePanel(!nativeView)) { chatBody(Modifier) }
            if (session.agent == "claude") {
                Box(Modifier.keepAlivePanel(nativeView)) {
                    TerminalPanel(
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
    key(sessionId, terminalId) {
        TerminalPanel(
            connect = {
                if (terminalId == "agent") vm.fleet.connectAgentTerminal(sessionId)
                else vm.fleet.connectTerminal(sessionId, terminalId)
            },
            modifier = modifier.fillMaxSize().testTag(
                if (terminalId == "agent") "view_terminal_agent" else "view_terminal",
            ),
        )
    }
}

@Composable
private fun ExplorerViewPane(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    modifier: Modifier,
) {
    val explorer = remember(workspace.id) { ExplorerState() }
    FileTree(
        fsList = { p -> vm.fleet.workspaceFsList(workspace.id, p) },
        explorer = explorer,
        onOpenFile = { p -> session.fileOpener.open(p) },
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
    val documents = session.documents
    LaunchedEffect(path) { documents.open(path) }
    val doc = documents.get(path)
    // `rememberEditorEngine` keys on `lineWrap`, so the pane waits for the persisted value rather
    // than mounting on the default and rebuilding the WebView a frame later (see EditorScreen).
    val editorPrefs = LocalUiPrefs.current
    val loadedPrefs by produceState<Pair<Boolean, Int>?>(null, editorPrefs) {
        value = editorPrefs.editorLineWrap.first() to editorPrefs.editorFontSize.first()
    }
    val (lineWrap, initialFontSize) = loadedPrefs ?: return
    val fontSize by editorPrefs.editorFontSize.collectAsState(initialFontSize)
    val engine = rememberEditorEngine(
        lineWrap = lineWrap,
        fontSize = fontSize,
        onChange = { content -> documents.update(path, content) },
        onSave = { documents.get(path)?.let { documents.save(it) } },
    )
    if (doc == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Opening…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }
    WebCodeEditor(
        engine = engine,
        content = doc.content,
        filename = path.substringAfterLast('/'),
        fontSize = fontSize,
        scrollTop = doc.scrollTop,
        revealLine = doc.revealLine,
        onChange = { documents.update(path, it) },
        onSave = { documents.save(doc) },
        onRevealConsumed = { doc.revealLine = null },
        modifier = modifier.fillMaxSize().testTag("editor-${workspace.workdir}"),
    )
}

@Composable
private fun DiffViewPane(
    workspace: WorkspaceDto,
    view: ViewDto,
    vm: AppViewModel,
    modifier: Modifier,
) {
    val diff = remember(workspace.id, view.id) {
        DiffState().apply { view.stateString("diffBase")?.let { diffBase = it } }
    }
    val scope = rememberCoroutineScope()
    val primary = workspace.primarySessionId
    LaunchedEffect(workspace.id, diff.diffBase) {
        diff.loadDiff(
            fsDiff = { spec -> vm.fleet.workspaceFsDiff(workspace.id, spec) },
            fsRefs = { vm.fleet.workspaceFsRefs(workspace.id) },
        )
    }
    DiffView(
        repos = diff.diffRepos,
        comments = diff.diffComments,
        base = diff.diffBase,
        refs = diff.diffRefs,
        onSetBase = { base ->
            scope.launch {
                diff.setDiffBase(base) { spec -> vm.fleet.workspaceFsDiff(workspace.id, spec) }
            }
        },
        onAddComment = { repo, path, line, ctx, hunk, body ->
            if (primary != null) {
                vm.fleet.reviewAddComment(
                    primary,
                    AddCommentBody(
                        repo = repo,
                        path = path,
                        side = "RIGHT",
                        anchorLine = line,
                        anchorContext = ctx,
                        body = body,
                        diffHunkHeader = hunk,
                    ),
                )
            }
        },
        onResolve = { id -> if (primary != null) vm.fleet.reviewResolve(primary, id) },
        onSubmit = { if (primary != null) vm.fleet.reviewSubmit(primary) },
        onReload = { scope.launch { diff.reloadDiff { spec -> vm.fleet.workspaceFsDiff(workspace.id, spec) } } },
        onClose = {},
        modifier = modifier.fillMaxSize(),
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
        displays = vm.fleet.displays,
        listDisplays = { vm.fleet.listDisplays() },
        connectScrcpy = { vm.fleet.connectScrcpy(it) },
        connectVnc = { vm.fleet.connectVnc(it) },
        onStartDisplay = { vm.fleet.startDisplay(sessionName) },
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
