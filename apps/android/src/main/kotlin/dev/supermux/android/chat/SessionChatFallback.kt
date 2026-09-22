// The session-only chat: a session that belongs to NO workspace (an old broker, or a row opened
// from the settled fold). It is the `chatFallback` slot of the shared `SupermuxApp` — the only
// screen-shaped thing left on Android outside `:ui`.
//
// Cluster G8 deviation, recorded deliberately: `ChatScreen`'s phone header is NOT the shared
// `ChatViewHeader`. The two are not equivalent — this one carries the list⇄chat shared-element
// avatar, a back arrow, the workdir/git sub-label, the chat-DETAIL level submenu and the
// continue-conversation + Displays rows, none of which `ChatViewHeader` has a slot for, and it is
// the only place a `SharedTransitionScope` reaches a chat. What it already shares: `SessionAvatar`,
// `FinishHeaderButton`/`FinishBindings`, `ChatPanel`, `TerminalTabs`, `DisplayPanel`, `EditorPanel`.
// Every WORKSPACE chat — which is every chat on a current broker — goes through the shared
// `ViewHost`/`ChatViewHeader` instead.
package dev.supermux.android.chat

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.supermux.android.AppViewModel
import dev.supermux.net.ProxyDto
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.display.rememberDisplayActions

/**
 * One kept-alive chat layer for [session]. [visible] follows the shell's selection; the layer stays
 * composed either way so a transcript, a PTY and a WebView survive a switch.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SessionChatFallback(
    session: SessionInfo,
    visible: Boolean,
    vm: AppViewModel,
    onBack: () -> Unit,
    onSelectSession: (String) -> Unit,
    onOpenDisplays: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    // The editor pane reports whether IT owns BACK. Since cluster G8 the root owns the single
    // BackHandler and the editor's own (search overlay / tree drawer) is the innermost one Compose
    // dispatches to, so nothing routes off this flag any more — it stays only because
    // `ChatScreen` requires the callback.
    var editorConsumesBack by remember(session.id) { mutableStateOf(false) }
    val messagesAll by vm.fleet.messages.collectAsState()
    val activityAll by vm.fleet.activity.collectAsState()
    val agentAll by vm.fleet.agentState.collectAsState()
    val pendingSend by vm.fleet.pendingSend.collectAsState()
    val commandsAll by vm.fleet.commands.collectAsState()
    val commandsResolvedAll by vm.fleet.commandsResolved.collectAsState()
    val messages = messagesAll[session.id] ?: emptyList()
    val activity = activityAll[session.id] ?: emptyList()
    val agent = agentAll[session.id]
    val sending = pendingSend.contains(session.id)
    val commands = commandsAll[session.id] ?: emptyList()
    val commandsResolved = commandsResolvedAll[session.id] ?: false

    // Collect the finish-job flow at this layer (consistent with messages/activity/agent).
    val finishJobs by vm.fleet.finishJobs.collectAsState()
    val finishJob = finishJobs[session.id]

    // Background tasks (bg shells / subagents / workflows) for the chips row + waiting state.
    val bgTasksAll by vm.fleet.bgTasks.collectAsState()
    val bgTasks = bgTasksAll[session.id] ?: emptyList()

    // Exposed proxy links for this session (iOS parity) — loaded on open, filtered by session name.
    var sessionLinks by remember(session.id) { mutableStateOf<List<ProxyDto>>(emptyList()) }
    LaunchedEffect(session.id) { sessionLinks = vm.fleet.proxies().filter { it.sessionName == session.name } }

    if (!visible) return
    // Cluster G4: the shared Display panel's broker seam, built once per fleet.
    val displayActions = rememberDisplayActions(vm.fleet)
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
            transcribeAudio = { bytes, name, mime -> vm.fleet.transcribeAudio(session.id, bytes, name, mime) },
            transcribeDraft = { draft -> vm.fleet.transcribeDraft(session.id, draft) },
            loadGlossary = { vm.fleet.fetchGlossary().orEmpty() },
            onRename = { vm.fleet.rename(session.id, it) },
            onMute = { vm.fleet.setMute(session.id, it) },
            onKill = { vm.fleet.kill(session.id) { onBack() } },
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
            displayActions = displayActions,
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
