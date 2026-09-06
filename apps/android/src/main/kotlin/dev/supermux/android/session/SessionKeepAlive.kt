package dev.supermux.android.session

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.zIndex
import dev.supermux.android.AppViewModel
import dev.supermux.android.DevConfig
import dev.supermux.android.chat.ChatScreen
import dev.supermux.ui.widgets.keepAlivePanel
import dev.supermux.host.workspaceForSession
import dev.supermux.android.workspace.AndroidWorkspaceKeepAliveHost
import dev.supermux.android.workspace.WorkspaceScreen
import dev.supermux.android.workspace.rememberVisitedWorkspaces
import dev.supermux.net.ArchivedDto
import dev.supermux.net.ProxyDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.session.rememberSessionListActions
import dev.supermux.proto.SlashCommand
import dev.supermux.session.asSettledSession
import dev.supermux.state.spawnFailureMessage
import dev.supermux.state.SidebarReorderKind
import dev.supermux.state.sidebarReorderKind

/**
 * Tracks session ids the user has opened; pruned when the broker removes a live session.
 *
 * The currently [selected] id is always retained even when it is not in [liveSessionIds]
 * (archived/settled rows opened from the task list) — otherwise the list slides away and no
 * chat layer is composed, which was a solid black screen.
 */
@Composable
fun rememberVisitedSessions(
    selected: String?,
    liveSessionIds: Set<String>,
): Pair<Set<String>, (String) -> Unit> {
    var visited by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(selected) {
        selected?.let { id -> visited = visited + id }
    }

    LaunchedEffect(liveSessionIds, selected) {
        val kept = visited.intersect(liveSessionIds)
        visited = if (selected != null) kept + selected else kept
    }

    val remove: (String) -> Unit = { id -> visited = visited - id }
    return visited to remove
}

/** Live session first; fall back to an archived row so settled-from-archive opens don't blank. */
private fun resolveSession(
    sessionId: String,
    sessions: List<SessionInfo>,
    archived: List<ArchivedDto>,
): SessionInfo? =
    sessions.firstOrNull { it.id == sessionId }
        ?: archived.firstOrNull { it.id == sessionId }?.asSettledSession()

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SessionKeepAlivePhoneHost(
    selected: String?,
    onSelect: (String) -> Unit,
    onClearSelected: () -> Unit,
    visited: Set<String>,
    onRemoveVisited: (String) -> Unit,
    sessions: List<SessionInfo>,
    messages: Map<String, List<LogEntry>>,
    activityMap: Map<String, List<ActivityEvent>>,
    agentState: Map<String, AgentStatus?>,
    pendingSend: Set<String> = emptySet(),
    commands: Map<String, List<SlashCommand>>,
    commandsResolved: Map<String, Boolean>,
    lastBySession: Map<String, LogEntry?>,
    /** Bare sessionId → ISO last_read_at (server + optimistic marks). */
    lastRead: Map<String, String> = emptyMap(),
    archived: List<ArchivedDto> = emptyList(),
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
    onOpenDraft: (String) -> Unit = {},
    onOpenDisplays: () -> Unit,
    // Multi-host (spec §5): threaded straight to the phone SessionListScreen (chips + badges).
    hosts: List<dev.supermux.host.HostView> = emptyList(),
    sessionHost: Map<String, String> = emptyMap(),
    hostFilter: String? = null,
    onHostFilter: (String?) -> Unit = {},
    onAddHost: () -> Unit = {},
    workspaces: List<dev.supermux.proto.WorkspaceDto> = emptyList(),
    archivedWorkspaces: List<dev.supermux.proto.WorkspaceDto> = emptyList(),
    /** Collapsed project groups (cluster F1) — read synchronously by `MainActivity.onCreate`. */
    initialCollapsedPaths: Set<String> = emptySet(),
    onCollapsedPathsChange: (Set<String>) -> Unit = {},
) {
    val listActions = rememberSessionListActions(vm.fleet)
    // Phone AnimatedContent disposes SessionListScreen while a chat is open. Keep scroll
    // state here (survives that dispose + process death) so back returns to the same offset.
    val sessionListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(0, 0)
    }
    val selectedWorkspace = selected?.let { workspaceForSession(workspaces, it) }
    val liveWorkspaceIds = remember(workspaces) { workspaces.map { it.id }.toSet() }
    val context = LocalContext.current
    val newChatScope = rememberCoroutineScope()
    val onNewChatInWorkspace: (dev.supermux.proto.WorkspaceDto) -> Unit = { w ->
        newChatScope.launch {
            val recordId = vm.fleet.activeHost.value
            if (recordId == null) {
                Toast.makeText(context, "No host connected", Toast.LENGTH_SHORT).show()
                return@launch
            }
            runCatching {
                val id = vm.fleet.newChatInWorkspace(recordId, w.id, w.workdir)
                onSelect(id)
            }.onFailure {
                Toast.makeText(context, spawnFailureMessage(it), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val retainedWorkspaces = rememberVisitedWorkspaces(selectedWorkspace?.id, liveWorkspaceIds)
    val workspaceBySession = remember(workspaces) {
        workspaces.flatMap { ws ->
            ws.views.mapNotNull { v -> v.chatSessionId()?.let { it to ws } }
        }.toMap()
    }

    SharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
            AndroidWorkspaceKeepAliveHost(
                activeWorkspaceId = selectedWorkspace?.id,
                retainedIds = retainedWorkspaces,
                workspaces = workspaces,
            ) { ws, visible ->
                PhoneWorkspaceBackLayer(visible = visible, onBack = onClearSelected) {
                    WorkspaceScreen(
                        workspace = ws,
                        vm = vm,
                        wide = false,
                        modifier = Modifier.fillMaxSize(),
                        onSelectSession = onSelect,
                    )
                }
            }
            visited.forEach { sessionId ->
                if (workspaceBySession[sessionId] != null) return@forEach
                val session = resolveSession(sessionId, sessions, archived) ?: return@forEach
                val visible = sessionId == selected && selectedWorkspace == null
                key(sessionId) {
                    SessionChatLayer(
                        session = session,
                        visible = visible,
                        messages = messages[sessionId] ?: emptyList(),
                        activity = activityMap[sessionId] ?: emptyList(),
                        agent = agentState[sessionId],
                        sending = pendingSend.contains(sessionId),
                        commands = commands[sessionId] ?: emptyList(),
                        commandsResolved = commandsResolved[sessionId] ?: false,
                        vm = vm,
                        onBack = onClearSelected,
                        onKill = {
                            vm.fleet.kill(sessionId) {
                                onRemoveVisited(sessionId)
                                if (selected == sessionId) onClearSelected()
                            }
                        },
                        onOpenDisplays = onOpenDisplays,
                        onSelectSession = onSelect,
                        sharedScope = this@SharedTransitionLayout,
                        animScope = null,
                    )
                }
            }

            AnimatedContent(
                targetState = selected == null,
                transitionSpec = {
                    val showList = targetState
                    val enter = slideInHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        initialOffsetX = { if (showList) -it / 3 else it },
                    ) + fadeIn(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                    val exit = slideOutHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        targetOffsetX = { if (showList) it else -it / 3 },
                    ) + fadeOut(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                    enter togetherWith exit
                },
                label = "sessionListOverlay",
                modifier = Modifier.zIndex(2f),
            ) { showList ->
                if (showList) {
                    SessionListScreen(
                        sessions = sessions,
                        home = DevConfig.HOME,
                        activeId = null,
                        onOpen = onSelect,
                        lastBySession = lastBySession,
                        lastRead = lastRead,
                        agentState = agentState,
                        onNewSession = { onNavigate("new") },
                        // Long-press row actions were never wired on the phone list host, so
                        // Kill/Rename/Mute opened their dialogs but the confirm was a no-op
                        // (SessionListScreen defaults these to {}). The cluster-F1 holder wires
                        // them all; kill still prunes the kept-alive layer, now via [onKilled].
                        actions = listActions,
                        onKilled = { id ->
                            onRemoveVisited(id)
                            if (selected == id) onClearSelected()
                        },
                        onNavigate = onNavigate,
                        archived = archived,
                        onOpenDraft = onOpenDraft,
                        onReorder = { ids -> if (sidebarReorderKind(workspaces) == SidebarReorderKind.SESSIONS) listActions.reorderSessions(ids) else listActions.reorderWorkspaces(ids) },
                        workspaces = workspaces,
                        archivedWorkspaces = archivedWorkspaces,
                        onNewChatInWorkspace = onNewChatInWorkspace,
                        initialCollapsedPaths = initialCollapsedPaths,
                        onCollapsedPathsChange = onCollapsedPathsChange,
                        hosts = hosts,
                        sessionHost = sessionHost,
                        hostFilter = hostFilter,
                        onHostFilter = onHostFilter,
                        onAddHost = onAddHost,
                        sharedScope = this@SharedTransitionLayout,
                        animScope = this,
                        listState = sessionListState,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SessionKeepAliveTabletHost(
    selected: String?,
    onSelect: (String) -> Unit = {},
    visited: Set<String>,
    onRemoveVisited: (String) -> Unit,
    sessions: List<SessionInfo>,
    messages: Map<String, List<LogEntry>>,
    activityMap: Map<String, List<ActivityEvent>>,
    agentState: Map<String, AgentStatus?>,
    pendingSend: Set<String> = emptySet(),
    commands: Map<String, List<SlashCommand>>,
    commandsResolved: Map<String, Boolean>,
    /** Archived rows for settled-from-archive open (same as phone host). */
    archived: List<ArchivedDto> = emptyList(),
    vm: AppViewModel,
    wide: Boolean,
    workspaces: List<dev.supermux.proto.WorkspaceDto> = emptyList(),
    onNavigate: (String) -> Unit,
    onOpenDisplays: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Release keyboard focus whenever the visible session changes, so a hidden (kept-alive)
    // session's terminal/composer can't keep the IME and swallow keystrokes meant for the new one
    // (each session stays composed for instant switching, but only the visible one should type).
    val focusManager = LocalFocusManager.current
    LaunchedEffect(selected) { focusManager.clearFocus(force = true) }
    val selectedWorkspace = selected?.let { workspaceForSession(workspaces, it) }
    val liveWorkspaceIds = remember(workspaces) { workspaces.map { it.id }.toSet() }
    val retainedWorkspaces = rememberVisitedWorkspaces(selectedWorkspace?.id, liveWorkspaceIds)
    val workspaceBySession = remember(workspaces) {
        workspaces.flatMap { ws ->
            ws.views.mapNotNull { v -> v.chatSessionId()?.let { it to ws } }
        }.toMap()
    }
    Box(modifier.fillMaxSize()) {
        if (visited.isEmpty() && selected == null && retainedWorkspaces.isEmpty()) {
            return@Box
        }
        AndroidWorkspaceKeepAliveHost(
            activeWorkspaceId = selectedWorkspace?.id,
            retainedIds = retainedWorkspaces,
            workspaces = workspaces,
        ) { ws, _ ->
            WorkspaceScreen(
                workspace = ws,
                vm = vm,
                wide = wide,
                modifier = Modifier.fillMaxSize(),
                onSelectSession = onSelect,
            )
        }
        visited.forEach { sessionId ->
            if (workspaceBySession[sessionId] != null) return@forEach
            val session = resolveSession(sessionId, sessions, archived) ?: return@forEach
            val visible = sessionId == selected && selectedWorkspace == null
            key(sessionId) {
                SessionChatLayer(
                    session = session,
                    visible = visible,
                    messages = messages[sessionId] ?: emptyList(),
                    activity = activityMap[sessionId] ?: emptyList(),
                    agent = agentState[sessionId],
                    sending = pendingSend.contains(sessionId),
                    commands = commands[sessionId] ?: emptyList(),
                    commandsResolved = commandsResolved[sessionId] ?: false,
                    vm = vm,
                    wide = false,
                    onNavigate = onNavigate,
                    onBack = {},
                    onKill = {
                        vm.fleet.kill(sessionId) {
                            onRemoveVisited(sessionId)
                        }
                    },
                    onOpenDisplays = onOpenDisplays,
                    onSelectSession = onSelect,
                    sharedScope = null,
                    animScope = null,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneWorkspaceBackLayer(
    visible: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    var gestureProgress by remember { mutableFloatStateOf(0f) }
    if (visible) {
        val imeVisible = WindowInsets.isImeVisible
        // Editor panes register their own BackHandler later in composition
        // (EditorScreen.kt searchOpen ~244, treeDrawerOpen ~249). Compose dispatches to the
        // innermost enabled handler first, so those consume BACK before this layer. Do not
        // pass editorConsumesBack here.
        val backAction = phoneLayerBackAction(
            wide = false,
            editorConsumesBack = false,
            imeVisible = imeVisible,
        )
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        BackHandler(enabled = backAction == PhoneLayerBackAction.HideIme) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        BackHandler(enabled = backAction == PhoneLayerBackAction.ClearSelection) { onBack() }
        PredictiveBackHandler(enabled = backAction == PhoneLayerBackAction.ClearSelection) { backEvents ->
            try {
                backEvents.collect { event -> gestureProgress = event.progress }
                onBack()
            } catch (_: Exception) {
            }
            gestureProgress = 0f
        }
    }
    Box(
        Modifier.graphicsLayer {
            if (visible) {
                val scale = 1f - gestureProgress * 0.05f
                scaleX = scale
                scaleY = scale
                alpha = 1f - gestureProgress * 0.3f
            }
        },
    ) {
        content()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SessionChatLayer(
    session: SessionInfo,
    visible: Boolean,
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    agent: AgentStatus?,
    sending: Boolean = false,
    commands: List<SlashCommand>,
    commandsResolved: Boolean,
    vm: AppViewModel,
    // Old-broker fallback (workspaces empty): session-only chat + agent terminal (4f).
    wide: Boolean = false,
    // Management-screen nav from the wide workspace header overflow. Phone/ChatScreen path defaults
    // to a no-op (it has its own overflow), keeping the single-pane chat path unchanged.
    onNavigate: (String) -> Unit = {},
    onBack: () -> Unit,
    onKill: () -> Unit,
    onOpenDisplays: () -> Unit,
    onSelectSession: (String) -> Unit = {},
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
) {
    var gestureProgress by remember(session.id) { mutableFloatStateOf(0f) }
    var editorConsumesBack by remember(session.id) { mutableStateOf(false) }
    val context = LocalContext.current

    // Collect the finish-job flow once at this layer (consistent with messages/activity/agent);
    // the per-session value drives ChatScreen's Finish button + sheet.
    val finishJobs by vm.fleet.finishJobs.collectAsState()
    val finishJob = finishJobs[session.id]

    // Background tasks (bg shells / subagents / workflows) for the chips row + waiting state.
    val bgTasksAll by vm.fleet.bgTasks.collectAsState()
    val bgTasks = bgTasksAll[session.id] ?: emptyList()

    // Exposed proxy links for this session (iOS parity) — loaded on open, filtered by session name.
    var sessionLinks by remember(session.id) { mutableStateOf<List<ProxyDto>>(emptyList()) }
    LaunchedEffect(session.id) { sessionLinks = vm.fleet.proxies().filter { it.sessionName == session.name } }

    if (visible) {
        // Soft keyboard up: Back must dismiss the IME only — not leave the session. An always-on
        // session BackHandler was consuming the event before the platform/IME could hide the
        // keyboard. Explicit hide+clearFocus covers chat/editor Compose focus and termlib's
        // showSoftKeyboard path (TerminalPanel watches isImeVisible → clears wantKeyboard).
        // Predictive exit gesture stays disabled while the IME is up so Back never animates out.
        val imeVisible = WindowInsets.isImeVisible
        val backAction = phoneLayerBackAction(wide, editorConsumesBack, imeVisible)
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        BackHandler(enabled = backAction == PhoneLayerBackAction.HideIme) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        // Phone: Back returns to the session list (onBack). On the wide/tablet path onBack is a
        // no-op (the list is always on-screen), so DON'T consume Back there — let it background the
        // app. The editor pane keeps its own Back-consume via its own BackHandler + editorConsumesBack.
        BackHandler(enabled = backAction == PhoneLayerBackAction.ClearSelection) { onBack() }
        PredictiveBackHandler(enabled = backAction == PhoneLayerBackAction.ClearSelection) { backEvents ->
            try {
                backEvents.collect { event -> gestureProgress = event.progress }
                onBack()
            } catch (_: Exception) {
            }
            gestureProgress = 0f
        }
    }

    Box(
        Modifier
            .keepAlivePanel(visible)
            .graphicsLayer {
                if (visible) {
                    val scale = 1f - gestureProgress * 0.05f
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - gestureProgress * 0.3f
                }
            },
    ) {
        // 4f — old broker (`workspaces` empty): session-only chat + agent terminal. Editor/diff
        // panes from the private layout are gone; do not crash if a session has no workspace.
        ChatScreen(
            session = session,
            messages = messages,
            activity = activity,
            agent = agent,
            bgTasks = bgTasks,
            sending = sending,
            onBack = onBack,
            onSendWith = { text, atts -> vm.fleet.sendWith(session.id, text, atts) },
            onUpload = { source, name, mime, kind, onProgress -> vm.fleet.uploadResumable(session.id, source, name, mime, kind, onProgress) },
            transcribeAudio = { bytes, name -> vm.fleet.transcribeAudio(session.id, bytes, name) },
            transcribeDraft = { draft -> vm.fleet.transcribeDraft(session.id, draft) },
            loadGlossary = { vm.fleet.fetchGlossary().orEmpty() },
            onRename = { vm.fleet.rename(session.id, it) },
            onMute = { vm.fleet.setMute(session.id, it) },
            onKill = onKill,
            sessionLinks = sessionLinks,
            vmModels = { vm.fleet.sessionModels(it) },
            vmReasoning = { vm.fleet.sessionReasoning(it) },
            onPickModel = { vm.fleet.switchModel(session.id, it) },
            onPickEffort = { vm.fleet.switchReasoning(session.id, it) },
            commands = commands,
            commandsResolved = commandsResolved,
            onInterrupt = { vm.fleet.interrupt(session.id) },
            loadDraft = { vm.fleet.loadDraft(it) },
            saveDraft = { id, t -> vm.fleet.saveDraft(id, t) },
            loadBytes = { vm.fleet.fileBytes(it) },
            fsList = { vm.fleet.fsListResult(session.id, it) },
            fsRead = { vm.fleet.fsRead(session.id, it) },
            fsWrite = { p, ct -> vm.fleet.fsWrite(session.id, p, ct) },
            fsSearch = { vm.fleet.fsSearch(session.id, it) },
            // Editor diff + inline code-review (bound to this session).
            fsDiff = { base -> vm.fleet.fsDiff(session.id, base) },
            fsRefs = { vm.fleet.fsRefs(session.id) },
            reviewAddComment = { body -> vm.fleet.reviewAddComment(session.id, body) },
            reviewResolve = { commentId -> vm.fleet.reviewResolve(session.id, commentId) },
            reviewSubmit = { vm.fleet.reviewSubmit(session.id) },
            // Editor LSP + live file-watch — app-wide flows + session-bound senders.
            fsChanges = vm.fleet.fsChanges,
            lspStatus = vm.fleet.lspStatus,
            lspRpc = vm.fleet.lspRpc,
            editorOpen = { vm.fleet.editorOpen(it) },
            editorClose = { vm.fleet.editorClose(it) },
            lspStatusQuery = { s, p -> vm.fleet.lspStatusQuery(s, p) },
            lspOpen = { s, sid -> vm.fleet.lspOpen(s, sid) },
            lspRpcOut = { s, sid, m -> vm.fleet.lspRpcOut(s, sid, m) },
            lspClose = { s, sid -> vm.fleet.lspClose(s, sid) },
            connectTerminal = { terminalId -> vm.fleet.connectTerminal(session.id, terminalId) },
            listTerminals = { vm.fleet.listTerminals(session.id) },
            closeTerminal = { terminalId -> vm.fleet.closeTerminal(session.id, terminalId) },
            connectAgentTerminal = { vm.fleet.connectAgentTerminal(session.id) },
            listDisplays = { vm.fleet.listDisplays() },
            connectScrcpy = { vm.fleet.connectScrcpy(it) },
            connectVnc = { vm.fleet.connectVnc(it) },
            displays = vm.fleet.displays,
            onStartDisplay = { vm.fleet.startDisplay(session.name) },
            onOpenDisplays = onOpenDisplays,
            consumePendingFirst = { vm.fleet.consumePendingFirst(it) },
            onContinue = { handoff ->
                val recordId = vm.fleet.sessionHost.value[session.id] ?: vm.fleet.activeHost.value
                    ?: throw IllegalStateException("No host")
                vm.fleet.continueInNewConversation(recordId, session.id, handoff)
            },
            loadContinueAgents = {
                vm.fleet.agentStatuses().orEmpty().filter { it.installed }.map { it.kind }
            },
            loadContinueModels = { vm.fleet.launcherModels(it) },
            loadContinueReasoning = { ag, md -> vm.fleet.launcherReasoning(ag, md) },
            onContinued = onSelectSession,
            onEditorConsumesBackChange = { editorConsumesBack = it },
            finishJob = finishJob,
            onFinishReadiness = { vm.fleet.finishReadiness(session.id) },
            onFinish = { action, skipVerify, commitFirst, commitMessage, onKickoff ->
                vm.fleet.finish(session.id, action, skipVerify, commitFirst, commitMessage, onKickoff = onKickoff)
            },
            onClearFinishJob = { vm.fleet.clearFinishJob(session.id) },
            onVerifySuggest = { vm.fleet.verifySuggest(session.id) },
            onVerifySave = { vm.fleet.verifySave(session.id, it) },
            onSendToAgent = { vm.fleet.sendMessage(session.id, it) },
            sharedScope = sharedScope,
            animScope = animScope,
        )
    }
}
