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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.zIndex
import dev.supermux.android.AppViewModel
import dev.supermux.android.DevConfig
import dev.supermux.android.chat.ChatScreen
import dev.supermux.android.ui.keepAlivePanel
import dev.supermux.android.workspace.SessionWorkspaceDetail
import dev.supermux.android.workspace.WorkspaceLayout
import dev.supermux.net.GitOpResult
import dev.supermux.net.ProxyDto
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand

/** Short human text for a git-op result (parity with iOS SessionChrome.gitResultText). */
private fun gitOpResultText(r: GitOpResult?): String = when (r?.status) {
    null -> "Failed"
    "pushed" -> "Pushed"
    "up_to_date" -> "Up to date"
    "clean" -> "Pulled"
    "rejected_non_ff" -> "Push rejected — pull first"
    "conflict" -> "Conflict in ${r.files.size} file(s)"
    "dirty" -> "Uncommitted changes block the pull"
    "auth_failed" -> "Auth failed"
    "error" -> r.message ?: "Error"
    else -> r.status
}

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
    // Multi-host (spec §5): threaded straight to the phone SessionListScreen (chips + badges).
    hosts: List<dev.supermux.android.host.HostView> = emptyList(),
    sessionHost: Map<String, String> = emptyMap(),
    hostFilter: String? = null,
    onHostFilter: (String?) -> Unit = {},
    onAddHost: () -> Unit = {},
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
                        // Long-press row actions were never wired on the phone list host, so
                        // Kill/Rename/Mute opened their dialogs but the confirm was a no-op
                        // (SessionListScreen defaults these to {}). Mirror the tablet host +
                        // MainActivity wiring; kill also prunes the kept-alive layer.
                        onRename = { id, name -> vm.rename(id, name) },
                        onKill = { id ->
                            vm.kill(id) {
                                onRemoveVisited(id)
                                if (selected == id) onClearSelected()
                            }
                        },
                        onMute = { id, m -> vm.setMute(id, m) },
                        hosts = hosts,
                        sessionHost = sessionHost,
                        hostFilter = hostFilter,
                        onHostFilter = onHostFilter,
                        onAddHost = onAddHost,
                        onRenameHost = { id, name -> vm.renameHost(id, name) },
                        onForgetHost = { id -> vm.forgetHost(id) },
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
    onNavigate: (String) -> Unit,
    onOpenDisplays: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Release keyboard focus whenever the visible session changes, so a hidden (kept-alive)
    // session's terminal/composer can't keep the IME and swallow keystrokes meant for the new one
    // (each session stays composed for instant switching, but only the visible one should type).
    val focusManager = LocalFocusManager.current
    LaunchedEffect(selected) { focusManager.clearFocus(force = true) }
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
                    onNavigate = onNavigate,
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
    // Management-screen nav from the wide workspace header overflow. Phone/ChatScreen path defaults
    // to a no-op (it has its own overflow), keeping the single-pane chat path unchanged.
    onNavigate: (String) -> Unit = {},
    onBack: () -> Unit,
    onKill: () -> Unit,
    onOpenDisplays: () -> Unit,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
) {
    var gestureProgress by remember(session.id) { mutableFloatStateOf(0f) }
    var editorConsumesBack by remember(session.id) { mutableStateOf(false) }
    val context = LocalContext.current

    // Collect the finish-job flow once at this layer (consistent with messages/activity/agent);
    // the per-session value drives ChatScreen's Finish button + sheet.
    val finishJobs by vm.finishJobs.collectAsState()
    val finishJob = finishJobs[session.id]

    // Background tasks (bg shells / subagents / workflows) for the chips row + waiting state.
    val bgTasksAll by vm.bgTasks.collectAsState()
    val bgTasks = bgTasksAll[session.id] ?: emptyList()

    // Exposed proxy links for this session (iOS parity) — loaded on open, filtered by session name.
    var sessionLinks by remember(session.id) { mutableStateOf<List<ProxyDto>>(emptyList()) }
    LaunchedEffect(session.id) { sessionLinks = vm.proxies().filter { it.sessionName == session.name } }

    if (visible) {
        // Phone: Back returns to the session list (onBack). On the wide/tablet path onBack is a
        // no-op (the list is always on-screen), so DON'T consume Back there — let it background the
        // app. The editor pane keeps its own Back-consume via its own BackHandler + editorConsumesBack.
        BackHandler(enabled = !editorConsumesBack && !wide) { onBack() }
        PredictiveBackHandler(enabled = !editorConsumesBack && !wide) { backEvents ->
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
                bgTasks = bgTasks,
                sending = sending,
                layout = ws,
                onSendWith = { text, atts -> vm.sendWith(session.id, text, atts) },
                onInterrupt = { vm.interrupt(session.id) },
                commands = commands,
                commandsResolved = commandsResolved,
                onUpload = { source, name, mime, kind, onProgress -> vm.uploadResumable(session.id, source, name, mime, kind, onProgress) },
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
                onNavigate = onNavigate,
                onGitOp = { op ->
                    val cb: (GitOpResult?) -> Unit = { r ->
                        Toast.makeText(context, gitOpResultText(r), Toast.LENGTH_SHORT).show()
                    }
                    when (op) {
                        "fetch" -> vm.gitFetch(session.id, cb)
                        "push" -> vm.gitPush(session.id, cb)
                        "pull" -> vm.gitPull(session.id, cb)
                        "publish" -> vm.gitPublish(session.id, cb)
                    }
                },
                sessionLinks = sessionLinks,
                // Finish flow — same VM-backed lambdas ChatScreen receives (see below).
                finishJob = finishJob,
                onFinishReadiness = { vm.finishReadiness(session.id) },
                onFinish = { action, skipVerify, commitFirst, commitMessage, onKickoff ->
                    vm.finish(session.id, action, skipVerify, commitFirst, commitMessage, onKickoff = onKickoff)
                },
                onClearFinishJob = { vm.clearFinishJob(session.id) },
                onVerifySuggest = { vm.verifySuggest(session.id) },
                onVerifySave = { vm.verifySave(session.id, it) },
                onSendToAgent = { vm.sendMessage(session.id, it) },
                fsList = { vm.fsList(session.id, it) },
                fsRead = { vm.fsRead(session.id, it) },
                fsWrite = { p, ct -> vm.fsWrite(session.id, p, ct) },
                fsSearch = { vm.fsSearch(session.id, it) },
                fsDiff = { base -> vm.fsDiff(session.id, base) },
                fsRefs = { vm.fsRefs(session.id) },
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
            bgTasks = bgTasks,
            sending = sending,
            onBack = onBack,
            onSendWith = { text, atts -> vm.sendWith(session.id, text, atts) },
            onUpload = { source, name, mime, kind, onProgress -> vm.uploadResumable(session.id, source, name, mime, kind, onProgress) },
            transcribeAudio = { bytes, name -> vm.transcribeAudio(session.id, bytes, name) },
            transcribeDraft = { draft -> vm.transcribeDraft(session.id, draft) },
            loadGlossary = { vm.fetchGlossary() },
            onRename = { vm.rename(session.id, it) },
            onMute = { vm.setMute(session.id, it) },
            onKill = onKill,
            sessionLinks = sessionLinks,
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
            fsDiff = { base -> vm.fsDiff(session.id, base) },
            fsRefs = { vm.fsRefs(session.id) },
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
