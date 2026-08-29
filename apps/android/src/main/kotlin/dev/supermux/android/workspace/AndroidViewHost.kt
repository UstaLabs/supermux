package dev.supermux.android.workspace

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.supermux.android.editor.FileTree
import dev.supermux.android.editor.WebCodeEditor
import dev.supermux.android.editor.rememberEditorEngine
import dev.supermux.android.terminal.TerminalPanel
import dev.supermux.android.theme.Space
import dev.supermux.net.AddCommentBody
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString
import dev.supermux.session.inferHomeDir
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.editor.DiffState
import dev.supermux.ui.editor.ExplorerState
import dev.supermux.ui.toWorkdirRelativePath
import dev.supermux.ui.workspace.WorkspaceSession
import kotlinx.coroutines.launch

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
) {
    when (view.kind) {
        "chat" -> {
            val sessionId = view.chatSessionId()
            if (sessionId == null) UnknownViewHint(view.kind, modifier)
            else ChatViewPane(sessionId, workspace, session, vm, modifier)
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
                        connect = { vm.connectWorkspaceTerminal(workspace.id, terminalId) },
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
) {
    val sessions by vm.sessions.collectAsState()
    val messages by vm.messages.collectAsState()
    val activity by vm.activity.collectAsState()
    val agentState by vm.agentState.collectAsState()
    val pendingSend by vm.pendingSend.collectAsState()
    val commands by vm.commands.collectAsState()
    val commandsResolved by vm.commandsResolved.collectAsState()
    val bgTasksAll by vm.bgTasks.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        UnknownViewHint("chat", modifier)
        return
    }
    val context = LocalContext.current
    ChatPanel(
        session = session,
        messages = messages[sessionId] ?: emptyList(),
        activity = activity[sessionId] ?: emptyList(),
        agent = agentState[sessionId],
        bgTasks = bgTasksAll[sessionId] ?: emptyList(),
        sending = pendingSend.contains(sessionId),
        activePanel = SessionPanel.Chat,
        onSendWith = { text, atts -> vm.sendWith(sessionId, text, atts) },
        onInterrupt = { vm.interrupt(sessionId) },
        commands = commands[sessionId] ?: emptyList(),
        commandsResolved = commandsResolved[sessionId] ?: false,
        onUpload = { source, name, mime, kind, onProgress ->
            vm.uploadResumable(sessionId, source, name, mime, kind, onProgress)
        },
        loadBytes = { vm.fileBytes(it) },
        transcribeAudio = { bytes, name -> vm.transcribeAudio(sessionId, bytes, name) },
        transcribeDraft = { draft -> vm.transcribeDraft(sessionId, draft) },
        loadGlossary = { vm.fetchGlossary() },
        vmModels = { vm.fetchModels(it) },
        vmReasoning = { vm.fetchReasoning(it) },
        onPickModel = { vm.switchModel(sessionId, it) },
        onPickEffort = { vm.switchReasoning(sessionId, it) },
        loadDraft = { vm.loadDraft(it) },
        saveDraft = { id, t -> vm.saveDraft(id, t) },
        consumePendingFirst = { vm.consumePendingFirst(it) },
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
        modifier = modifier.fillMaxSize().testTag("view_chat"),
    )
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
                if (terminalId == "agent") vm.connectAgentTerminal(sessionId)
                else vm.connectTerminal(sessionId, terminalId)
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
        fsList = { p -> vm.workspaceFsList(workspace.id, p) },
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
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cmux-editor-settings", Context.MODE_PRIVATE)
    val engine = rememberEditorEngine(
        lineWrap = prefs.getBoolean("lineWrap", true),
        fontSize = prefs.getInt("fontSize", 13),
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
        fontSize = prefs.getInt("fontSize", 13),
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
            fsDiff = { spec -> vm.workspaceFsDiff(workspace.id, spec) },
            fsRefs = { vm.workspaceFsRefs(workspace.id) },
        )
    }
    DiffView(
        repos = diff.diffRepos,
        comments = diff.diffComments,
        base = diff.diffBase,
        refs = diff.diffRefs,
        onSetBase = { base ->
            scope.launch {
                diff.setDiffBase(base) { spec -> vm.workspaceFsDiff(workspace.id, spec) }
            }
        },
        onAddComment = { repo, path, line, ctx, hunk, body ->
            if (primary != null) {
                vm.reviewAddComment(
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
        onResolve = { id -> if (primary != null) vm.reviewResolve(primary, id) },
        onSubmit = { if (primary != null) vm.reviewSubmit(primary) },
        onReload = { scope.launch { diff.reloadDiff { spec -> vm.workspaceFsDiff(workspace.id, spec) } } },
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
    val live by vm.displays.collectAsState()
    LaunchedEffect(displayId) { vm.listDisplays() }
    val stream = when {
        !displayId.isNullOrBlank() -> live.firstOrNull { it.id == displayId }
        else -> live.firstOrNull { it.status == "running" }
    }
    val sessionName = stream?.sessionName
        ?: workspace.primarySessionId?.let { pid ->
            // Fall back to workspace name so Start Display still has a session key.
            workspace.name
        }
        ?: workspace.name
    DisplayPanel(
        sessionName = sessionName,
        displays = vm.displays,
        listDisplays = { vm.listDisplays() },
        connectScrcpy = { vm.connectScrcpy(it) },
        connectVnc = { vm.connectVnc(it) },
        onStartDisplay = { vm.startDisplay(sessionName) },
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
