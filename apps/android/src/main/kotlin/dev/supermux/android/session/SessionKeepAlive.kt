package dev.supermux.android.session

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import dev.supermux.android.AppViewModel
import dev.supermux.android.DevConfig
import dev.supermux.android.chat.ChatScreen
import dev.supermux.android.ui.keepAlivePanel
import dev.supermux.android.workspace.SessionWorkspaceDetail
import dev.supermux.android.workspace.WorkspaceLayout
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand

/** Tracks session ids the user has opened; pruned when broker removes a session. */
@Composable
fun rememberVisitedSessions(
    selected: String?,
    liveSessionIds: Set<String>,
): Pair<Set<String>, (String) -> Unit> {
    var visited by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(selected) {
        selected?.let { id -> visited = visited + id }
    }

    LaunchedEffect(liveSessionIds) {
        visited = visited.intersect(liveSessionIds)
    }

    val remove: (String) -> Unit = { id -> visited = visited - id }
    return visited to remove
}

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
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
    onOpenDisplays: () -> Unit,
) {
    SharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
            visited.forEach { sessionId ->
                val session = sessions.firstOrNull { it.id == sessionId } ?: return@forEach
                val visible = sessionId == selected
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
                            vm.kill(sessionId) {
                                onRemoveVisited(sessionId)
                                if (selected == sessionId) onClearSelected()
                            }
                        },
                        onOpenDisplays = onOpenDisplays,
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
                        agentState = agentState,
                        onNewSession = { onNavigate("new") },
                        loadProjects = { vm.listProjects() },
                        validatePath = { vm.validatePath(it) },
                        onNavigate = onNavigate,
                        sharedScope = this@SharedTransitionLayout,
                        animScope = this,
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
    visited: Set<String>,
    onRemoveVisited: (String) -> Unit,
    sessions: List<SessionInfo>,
    messages: Map<String, List<LogEntry>>,
    activityMap: Map<String, List<ActivityEvent>>,
    agentState: Map<String, AgentStatus?>,
    pendingSend: Set<String> = emptySet(),
    commands: Map<String, List<SlashCommand>>,
    commandsResolved: Map<String, Boolean>,
    vm: AppViewModel,
    wide: Boolean,
    layout: WorkspaceLayout,
    onOpenDisplays: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        if (visited.isEmpty() && selected == null) {
            return@Box
        }
        visited.forEach { sessionId ->
            val session = sessions.firstOrNull { it.id == sessionId } ?: return@forEach
            val visible = sessionId == selected
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
                    wide = wide,
                    layout = layout,
                    onBack = {},
                    onKill = {
                        vm.kill(sessionId) {
                            onRemoveVisited(sessionId)
                        }
                    },
                    onOpenDisplays = onOpenDisplays,
                    sharedScope = null,
                    animScope = null,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
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
    // Wide (tablet / unfolded-foldable) renders the multi-pane workspace instead of ChatScreen.
    // Phone hosts leave these defaulted, keeping the single-pane chat path unchanged.
    wide: Boolean = false,
    layout: WorkspaceLayout? = null,
    onBack: () -> Unit,
    onKill: () -> Unit,
    onOpenDisplays: () -> Unit,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
) {
    var gestureProgress by remember(session.id) { mutableFloatStateOf(0f) }
    var editorConsumesBack by remember(session.id) { mutableStateOf(false) }

    // Collect the finish-job flow once at this layer (consistent with messages/activity/agent);
    // the per-session value drives ChatScreen's Finish button + sheet.
    val finishJobs by vm.finishJobs.collectAsState()
    val finishJob = finishJobs[session.id]

    if (visible) {
        BackHandler(enabled = !editorConsumesBack) { onBack() }
        PredictiveBackHandler(enabled = !editorConsumesBack) { backEvents ->
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
        // Wide screens render the multi-pane workspace; the phone/compact path falls through to
        // ChatScreen below (unchanged). `layout` is always non-null when `wide` (set by the tablet
        // host); the null-guard keeps this a safe fallback if ever called wide without a layout.
        val ws = layout
        if (wide && ws != null) {
            SessionWorkspaceDetail(
                session = session,
                messages = messages,
                activity = activity,
                agent = agent,
                sending = sending,
                layout = ws,
                onSendWith = { text, atts -> vm.sendWith(session.id, text, atts) },
                onInterrupt = { vm.interrupt(session.id) },
                commands = commands,
                commandsResolved = commandsResolved,
                onUpload = { bytes, name, mime, kind -> vm.upload(session.id, bytes, name, mime, kind) },
                loadBytes = { vm.fileBytes(it) },
                transcribeAudio = { bytes, name -> vm.transcribeAudio(session.id, bytes, name) },
                transcribeDraft = { draft -> vm.transcribeDraft(session.id, draft) },
                loadGlossary = { vm.fetchGlossary() },
                vmModels = { vm.fetchModels(it) },
                vmReasoning = { vm.fetchReasoning(it) },
                onPickModel = { vm.switchModel(session.id, it) },
                onPickEffort = { vm.switchReasoning(session.id, it) },
                loadDraft = { vm.loadDraft(it) },
                saveDraft = { id, t -> vm.saveDraft(id, t) },
                consumePendingFirst = { vm.consumePendingFirst(it) },
                onRename = { vm.rename(session.id, it) },
                onMute = { vm.setMute(session.id, it) },
                onKill = onKill,
                fsList = { vm.fsList(session.id, it) },
                fsRead = { vm.fsRead(session.id, it) },
                fsWrite = { p, ct -> vm.fsWrite(session.id, p, ct) },
                fsSearch = { vm.fsSearch(session.id, it) },
                fsDiff = { vm.fsDiff(session.id) },
                reviewAddComment = { body -> vm.reviewAddComment(session.id, body) },
                reviewResolve = { commentId -> vm.reviewResolve(session.id, commentId) },
                reviewSubmit = { vm.reviewSubmit(session.id) },
                fsChanges = vm.fsChanges,
                lspStatus = vm.lspStatus,
                lspRpc = vm.lspRpc,
                editorOpen = { vm.editorOpen(it) },
                editorClose = { vm.editorClose(it) },
                lspStatusQuery = { s, p -> vm.lspStatusQuery(s, p) },
                lspOpen = { s, sid -> vm.lspOpen(s, sid) },
                lspRpcOut = { s, sid, m -> vm.lspRpcOut(s, sid, m) },
                lspClose = { s, sid -> vm.lspClose(s, sid) },
                onEditorConsumesBackChange = { editorConsumesBack = it },
                connectTerminal = { vm.connectTerminal(session.id) },
                connectAgentTerminal = { vm.connectAgentTerminal(session.id) },
                listDisplays = { vm.listDisplays() },
                connectScrcpy = { vm.connectScrcpy(it) },
                connectVnc = { vm.connectVnc(it) },
                displays = vm.displays,
                onStartDisplay = { vm.startDisplay(session.name) },
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }
        ChatScreen(
            session = session,
            messages = messages,
            activity = activity,
            agent = agent,
            sending = sending,
            onBack = onBack,
            onSendWith = { text, atts -> vm.sendWith(session.id, text, atts) },
            onUpload = { bytes, name, mime, kind -> vm.upload(session.id, bytes, name, mime, kind) },
            transcribeAudio = { bytes, name -> vm.transcribeAudio(session.id, bytes, name) },
            transcribeDraft = { draft -> vm.transcribeDraft(session.id, draft) },
            loadGlossary = { vm.fetchGlossary() },
            onRename = { vm.rename(session.id, it) },
            onMute = { vm.setMute(session.id, it) },
            onKill = onKill,
            vmModels = { vm.fetchModels(it) },
            vmReasoning = { vm.fetchReasoning(it) },
            onPickModel = { vm.switchModel(session.id, it) },
            onPickEffort = { vm.switchReasoning(session.id, it) },
            commands = commands,
            commandsResolved = commandsResolved,
            onInterrupt = { vm.interrupt(session.id) },
            loadDraft = { vm.loadDraft(it) },
            saveDraft = { id, t -> vm.saveDraft(id, t) },
            loadBytes = { vm.fileBytes(it) },
            fsList = { vm.fsList(session.id, it) },
            fsRead = { vm.fsRead(session.id, it) },
            fsWrite = { p, ct -> vm.fsWrite(session.id, p, ct) },
            fsSearch = { vm.fsSearch(session.id, it) },
            // Editor diff + inline code-review (bound to this session).
            fsDiff = { vm.fsDiff(session.id) },
            reviewAddComment = { body -> vm.reviewAddComment(session.id, body) },
            reviewResolve = { commentId -> vm.reviewResolve(session.id, commentId) },
            reviewSubmit = { vm.reviewSubmit(session.id) },
            // Editor LSP + live file-watch — app-wide flows + session-bound senders.
            fsChanges = vm.fsChanges,
            lspStatus = vm.lspStatus,
            lspRpc = vm.lspRpc,
            editorOpen = { vm.editorOpen(it) },
            editorClose = { vm.editorClose(it) },
            lspStatusQuery = { s, p -> vm.lspStatusQuery(s, p) },
            lspOpen = { s, sid -> vm.lspOpen(s, sid) },
            lspRpcOut = { s, sid, m -> vm.lspRpcOut(s, sid, m) },
            lspClose = { s, sid -> vm.lspClose(s, sid) },
            connectTerminal = { vm.connectTerminal(session.id) },
            connectAgentTerminal = { vm.connectAgentTerminal(session.id) },
            listDisplays = { vm.listDisplays() },
            connectScrcpy = { vm.connectScrcpy(it) },
            connectVnc = { vm.connectVnc(it) },
            displays = vm.displays,
            onStartDisplay = { vm.startDisplay(session.name) },
            onOpenDisplays = onOpenDisplays,
            consumePendingFirst = { vm.consumePendingFirst(it) },
            onEditorConsumesBackChange = { editorConsumesBack = it },
            finishJob = finishJob,
            onFinishReadiness = { vm.finishReadiness(session.id) },
            onFinish = { action, skipVerify, commitFirst, commitMessage, onKickoff ->
                vm.finish(session.id, action, skipVerify, commitFirst, commitMessage, onKickoff = onKickoff)
            },
            onClearFinishJob = { vm.clearFinishJob(session.id) },
            onVerifySuggest = { vm.verifySuggest(session.id) },
            onVerifySave = { vm.verifySave(session.id, it) },
            onSendToAgent = { vm.sendMessage(session.id, it) },
            sharedScope = sharedScope,
            animScope = animScope,
        )
    }
}
