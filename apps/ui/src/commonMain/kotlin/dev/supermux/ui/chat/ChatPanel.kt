// The one chat surface both hosts render (cluster D4).
//
// Base: desktop's `desktop/chat/ChatPanel.kt` — the optional header (project breadcrumb / session
// name / live status dot / links slot / Chat⇄Native toggle / overflow slot), the keyed timeline,
// the shared [Composer] + [ComposerFooter], the walkthrough unread chip, the dead banner and the
// keep-alive Native pair. Android's `chat/ChatPanel.kt` folded in feature by feature:
//
//   • starter prompts on an empty session (`chat_starter_N`)                — both hosts now
//   • background-task chips, working / sending / waiting transcript rows    — see [showStatusRows]
//   • the floating composer (measured height → transcript contentPadding, ime + navbar insets)
//     and tap-to-dismiss-focus                                              — the TOUCH branch
//   • the slash control routing that needs a host dialog (rename / kill)    — [onRequestRename] etc.
//   • `TestIds.CHAT_VIEW` on the root
//
// The size/input branch is `LocalPointerAvailable`, not the host: a pointer host stacks the
// composer under the transcript with the edge fade (there is no soft keyboard to track), a touch
// host floats it over the transcript and follows the ime inset. Android tablets keep the floating
// shape they have today because they are still touch.
//
// State and behaviour arrive as [ChatState] + [ChatActions] (the portable shape); a host that
// already holds a [dev.supermux.state.HostStore] builds them with [rememberChatActions] /
// [rememberChatState], which keep desktop's ergonomics.
package dev.supermux.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.mergeTimeline
import dev.supermux.chat.parseChatTs
import dev.supermux.fs.inferHomeDir
import dev.supermux.net.ChunkSource
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.PromptRequest
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import kotlinx.serialization.json.JsonObject
import dev.supermux.session.projectLabel
import dev.supermux.state.HostStore
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.TestIds
import dev.supermux.ui.retain.rememberRetained
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.countToolsSince
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.effectiveChatDetail
import dev.supermux.ui.formatLowWorkingStatus
import dev.supermux.ui.workingBaseLabel
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.turnBoundaryMs
import dev.supermux.util.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.max
import kotlin.time.Clock

/** Reading-width cap for the composer so it lines up with the timeline's own cap. */
private val CONTENT_MAX_WIDTH = 860.dp

/** Height of the fade that carries the transcript into the header above it. */
private val EDGE_FADE = 28.dp

/** How far short of the very end a reader may stop and still have the transcript follow new content. */
private val FOLLOW_SLOP = 48.dp

/** The transcript as its autoscroll reads it, one layout at a time. */
private data class TranscriptTail(
    val firstIndex: Int,
    val firstOffset: Int,
    /** The end of the transcript is on screen, give or take [FOLLOW_SLOP]. */
    val atEnd: Boolean,
    /** The last row that draws, and its height while it is on screen (-1 when it is not). */
    val lastRow: Int,
    val lastRowSize: Int,
    /** Our own follow scroll is moving the transcript. */
    val ours: Boolean,
)

/**
 * Whether the end of the transcript is on screen, within [slopPx]: the last item's bottom edge plus
 * the after-content padding, against the viewport's end (the measure [transcriptEdge] uses). Exactly
 * at the end always counts, even when the rows that end the list draw nothing.
 */
private fun transcriptAtEnd(state: LazyListState, slopPx: Float): Boolean {
    if (!state.canScrollForward) return true
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index != info.totalItemsCount - 1) return false
    return last.offset + last.size + info.afterContentPadding - info.viewportEndOffset <= slopPx
}

/** How far the transcript's end is below the viewport's end, in px: 0 at the end, MAX_VALUE when the last row is off screen. */
private fun distanceToEnd(state: LazyListState): Float {
    if (!state.canScrollForward) return 0f
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return Float.MAX_VALUE
    if (last.index != info.totalItemsCount - 1) return Float.MAX_VALUE
    return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
}

/** Card #76: how fast the transcript eases toward a revealing reply's end; past [GLIDE_MAX_PX] it jumps as before. */
private const val GLIDE_TAU_MS = 90.0
private const val GLIDE_MAX_PX = 400f

/**
 * Eases [state] to the transcript's end: each frame covers `1 - exp(-dt / GLIDE_TAU_MS)` of what is left, then it
 * settles exactly there. A reader's drag outranks it (their scroll cancels ours).
 */
private suspend fun glideToEnd(state: LazyListState) {
    var last = withFrameMillis { it }
    while (true) {
        val left = distanceToEnd(state)
        if (left <= 0.5f || left > GLIDE_MAX_PX) break
        val now = withFrameMillis { it }
        val dt = (now - last).coerceIn(1L, 64L).toDouble()
        last = now
        state.scrollBy(max(1f, left * (1 - exp(-dt / GLIDE_TAU_MS)).toFloat()))
    }
    if (state.canScrollForward) state.scrollBy(100_000f)
}

/** Fade, not a rule: a short scrim of the panel's own background. Non-interactive. */
@Composable
private fun EdgeFade(bg: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(EDGE_FADE)
            .background(
                Brush.verticalGradient(
                    0.00f to bg,
                    0.45f to bg.copy(alpha = 0.72f),
                    0.75f to bg.copy(alpha = 0.28f),
                    1.00f to bg.copy(alpha = 0f),
                ),
            ),
    )
}

/**
 * Everything the chat surface READS. A host that owns a [HostStore] builds it with
 * [rememberChatState]; Android's screen passes the values its view-model already collected.
 */
data class ChatState(
    val messages: List<LogEntry> = emptyList(),
    val activity: List<ActivityEvent> = emptyList(),
    val agent: AgentStatus? = null,
    val bgTasks: List<ServerFrame.BgTask> = emptyList(),
    val sending: Boolean = false,
    val commands: List<SlashCommand> = emptyList(),
    val commandsResolved: Boolean = false,
    /** Walkthrough replies the user has not opened yet — drives the unread chip. */
    val walkthroughUnread: Int = 0,
    val walkthroughUnreadStepId: String? = null,
    val requests: List<PromptRequest> = emptyList(),
    /** Prompts that closed moments ago; the card leaves a one-line receipt for them. */
    val closedRequests: List<dev.supermux.state.ClosedRequest> = emptyList(),
    val lastError: String? = null,
    val permissionModes: List<dev.supermux.proto.PermissionModeInfo> = emptyList(),
    /** The session's subagents (running + recently finished), ordered by start. */
    val subagents: List<dev.supermux.proto.Subagent> = emptyList(),
    /** The councils this session called (spec §5), ordered by creation: one card each in the chat. */
    val councils: List<dev.supermux.proto.CouncilView> = emptyList(),
    /** What each council member is doing right now (member session id → "Searching the web…"), live. */
    val councilActivity: Map<String, String> = emptyMap(),
    /**
     * Ids of the sessions this chat's host lists (what the sidebar shows): an inbox message's sender
     * opens its chat on a tap only when it is one of them. A hidden child or a council is in no list.
     */
    val listedSessions: Set<String> = emptySet(),
    /** The host holds this chat's whole history page and extras (card #54's "fully shown" mark). */
    val historyLoaded: Boolean = true,
    /** Card #76: this chat's reply / thinking being written now (thinking first). */
    val live: List<dev.supermux.state.LiveReply> = emptyList(),
)

/**
 * Everything the chat surface DOES, already bound to ONE session. The portable shape: no store, no
 * session ids. [rememberChatActions] builds it from a [HostStore] for a host that has one.
 */
class ChatActions(
    val send: (text: String, fileIds: List<String>) -> Unit = { _, _ -> },
    val interrupt: () -> Unit = {},
    /** Chat uploads go against the LIVE session (unlike the launcher's pre-spawn staging). */
    val upload: (suspend (ChunkSource, String, String, String?, (Long, Long) -> Unit) -> String?)? = null,
    val transcribeAudio: (suspend (ByteArray, String, String) -> String?)? = null,
    /** How the chat reads attachments — the host's media cache in production. */
    val media: dev.supermux.media.AttachmentMedia = dev.supermux.media.AttachmentMedia.None,
    /** The composer's non-hot seams (drafts, slash cleanup, glossary, pending-first, git ops). */
    val composer: ComposerActions = ComposerActions(),
    val loadModels: suspend () -> ModelsResponse? = { null },
    val loadReasoning: suspend () -> ReasoningResponse? = { null },
    /** Levels for a just-picked [model] (null = Default), before the session row carries it. */
    val loadReasoningFor: suspend (model: String?) -> ReasoningResponse? = { loadReasoning() },
    /** Returns true when the switch took, so the panel can update its shown `current`. */
    val pickModel: suspend (String) -> Boolean = { false },
    val pickReasoning: suspend (String) -> Boolean = { false },
    /** Whether a model (null = Default) offers fast mode; false hides the toggle. */
    val fastSupported: (model: String?) -> Boolean = { false },
    val pickFast: suspend (Boolean) -> Boolean = { false },
    /** Transcript loaded lazily on open (archive-resumed sessions have no snapshot history). */
    val ensureMessagesLoaded: suspend () -> Unit = {},
    /** Session proxies for the header's links slot. */
    val loadProxies: suspend () -> List<ProxyDto> = { emptyList() },
    val respondRequest: (requestId: String, answer: JsonObject) -> Unit = { _, _ -> },
    val setPermissionMode: (String) -> Unit = {},
    /** Send text to one subagent / stop it; a host without the seam answers "not available". */
    val messageSubagent: suspend (subagentId: String, text: String) -> dev.supermux.net.SubagentActionResult =
        { _, _ -> dev.supermux.net.SubagentActionResult(ok = false, error = "Not available on this client") },
    val stopSubagent: suspend (subagentId: String) -> dev.supermux.net.SubagentActionResult =
        { dev.supermux.net.SubagentActionResult(ok = false, error = "Not available on this client") },
    /**
     * End a session of this chat's host: a council member (the council card's Stop). The same kill
     * the header's Kill does. Null = this client cannot, and the card shows no Stop.
     */
    val endCouncil: ((councilId: String) -> Unit)? = null,
    /** The session's host's accounts (every agent), live; null until loaded. Drives the run-settings Account row. */
    val accounts: kotlinx.coroutines.flow.Flow<List<dev.supermux.net.AccountDto>?> = kotlinx.coroutines.flow.flowOf(null),
    /** Start following [accounts] (the first call fetches GET /accounts). */
    val ensureAccounts: () -> Unit = {},
    /** POST /sessions/<id>/account — a refusal keeps the broker's code (session_busy, …). */
    val setAccount: suspend (accountId: String) -> dev.supermux.state.AccountResult<dev.supermux.net.SessionAccountResult> =
        { dev.supermux.state.AccountResult.Failed(null, null, "Not available on this client") },
    /** Opens Settings ▸ Accounts (the Account list's link when the session's account is unavailable); null = no link. */
    val openAccounts: (() -> Unit)? = null,
    /** Opens the agent's own sign-in, Settings ▸ Providers (the link when the system login's sign-in expired); null = none. */
    val openSignIn: (() -> Unit)? = null,
)

/** [ChatActions] wired to a [HostStore] for one session — desktop's ergonomics, kept. */
@Composable
fun rememberChatActions(
    app: HostStore,
    session: SessionInfo,
    /** Override the proxy load (the headless SM_LINKS_MENU hook injects a canned list). */
    loadProxies: (suspend () -> List<ProxyDto>)? = null,
    openAccounts: (() -> Unit)? = null,
    openSignIn: (() -> Unit)? = null,
): ChatActions {
    val composerActions = rememberComposerActions(app, session.id, session.workdir)
    return remember(app, session.id, composerActions, loadProxies, openAccounts, openSignIn) {
        ChatActions(
            send = { text, fileIds -> app.sendMessage(session.id, text, fileIds) },
            interrupt = { app.interrupt(session.id) },
            upload = { source, name, mime, kind, onProgress ->
                app.uploadResumable(session.id, source, name, mime, kind, onProgress)
            },
            transcribeAudio = { bytes, name, mime -> app.transcribeAudio(session.id, bytes, name, mime)?.text },
            media = app.media,
            composer = composerActions,
            loadModels = { app.sessionModels(session.id) },
            loadReasoning = { app.sessionReasoning(session.id) },
            loadReasoningFor = { model -> app.sessionReasoningFor(session.id, model) },
            pickModel = { model -> app.switchModel(session.id, model) },
            pickReasoning = { level -> app.switchReasoning(session.id, level) },
            fastSupported = { model -> app.fastSupported(session.agent, model) },
            pickFast = { on -> app.switchFastMode(session.id, on) },
            ensureMessagesLoaded = { app.ensureMessagesLoaded(session.id) },
            loadProxies = loadProxies ?: { app.proxies() },
            respondRequest = { requestId, answer -> app.respondRequest(session.id, requestId, answer) },
            setPermissionMode = { mode -> app.setPermissionMode(session.id, mode) },
            messageSubagent = { id, text -> app.messageSubagent(session.id, id, text) },
            stopSubagent = { id -> app.stopSubagent(session.id, id) },
            endCouncil = { id -> app.endCouncil(id) },
            accounts = app.accounts,
            ensureAccounts = { app.ensureAccounts() },
            setAccount = { id -> app.setSessionAccount(session.id, id) },
            openAccounts = openAccounts,
            openSignIn = openSignIn,
        )
    }
}

/** [ChatState] collected off a [HostStore] for one session. */
@Composable
fun rememberChatState(app: HostStore, sessionId: String): ChatState {
    val messagesMap by app.messages.collectAsState()
    val activityMap by app.activity.collectAsState()
    val agentMap by app.agentState.collectAsState()
    val pending by app.pendingSend.collectAsState()
    val bgTasksMap by app.bgTasks.collectAsState()
    val commandsMap by app.commands.collectAsState()
    val commandsResolvedMap by app.commandsResolved.collectAsState()
    val walkthrough = app.walkthroughState<WalkthroughState>(sessionId)
    val requestsMap by app.requests.collectAsState()
    val closedRequestsMap by app.closedRequests.collectAsState()
    val lastError by app.lastError.collectAsState()
    val permissionModesMap by app.permissionModes.collectAsState()
    val subagentsMap by app.subagents.collectAsState()
    val councilsMap by app.councils.collectAsState()
    val sessions by app.sessions.collectAsState()
    val sessionAgent = sessions.find { it.id == sessionId }?.agent
    val listedSessions = remember(sessions) { sessions.mapTo(HashSet()) { it.id } }
    val loadedChats by app.loadedChats.collectAsState()
    val historyLoaded = sessionId in loadedChats
    val liveMap by app.liveReplies.collectAsState()
    return ChatState(
        messages = messagesMap[sessionId].orEmpty(),
        activity = activityMap[sessionId].orEmpty(),
        agent = agentMap[sessionId],
        bgTasks = bgTasksMap[sessionId].orEmpty(),
        sending = sessionId in pending,
        commands = commandsMap[sessionId].orEmpty(),
        commandsResolved = commandsResolvedMap[sessionId] ?: false,
        walkthroughUnread = walkthrough.unreadReplies,
        walkthroughUnreadStepId = walkthrough.unreadStepId,
        requests = requestsMap[sessionId].orEmpty(),
        closedRequests = closedRequestsMap[sessionId].orEmpty(),
        lastError = lastError,
        permissionModes = sessionAgent?.let { permissionModesMap[it] }.orEmpty(),
        subagents = subagentsMap[sessionId].orEmpty(),
        councils = councilsMap[sessionId].orEmpty(),
        councilActivity = councilActivityOf(councilsMap[sessionId].orEmpty(), agentMap, activityMap),
        listedSessions = listedSessions,
        historyLoaded = historyLoaded,
        live = liveMap[sessionId].orEmpty(),
    )
}

/**
 * Stable list key so the optimistic→real id swap (local-echo → broker MessageAppend) doesn't
 * flicker or lose scroll position: tool rows key off callId (falling back to kind:seq:ts), message
 * rows off the entry id. Identical on both hosts before the merge.
 */
internal fun uniqueTimelineKeys(items: List<TimelineItem>): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val base = timelineItemKey(item)
        val n = seen.getOrElse(base) { 0 }
        seen[base] = n + 1
        if (n == 0) base else "$base#$n"
    }
}

private fun timelineItemKey(item: TimelineItem): String = when (item) {
    // Card #76: a stored row with a stream keeps the key its live row had, so the swap keeps its slot.
    is TimelineItem.Msg -> "m:${item.entry.stream ?: item.entry.id}"
    is TimelineItem.Tool -> "t:${item.event.callId ?: "${item.event.kind}:${item.event.seq}:${item.event.ts}"}"
    is TimelineItem.Activity -> item.event.stream?.let { "a:$it" } ?: "a:${item.event.kind}:${item.event.seq}:${item.event.ts}"
    is TimelineItem.SubagentCard -> "s:${item.subagent.id}"
    is TimelineItem.CouncilCard -> "c:${item.council.id}"
}

/** Card #76: a live row's key, the same one its stored row will have (see [timelineItemKey]). */
private fun liveRowKey(row: dev.supermux.state.LiveReply): String = if (row.thinking) "a:${row.stream}" else "m:${row.stream}"

/**
 * Full chat surface for [session].
 *
 * @param showHeader when false the host owns the identity bar (Android's `ChatScreen`, the tablet
 *   `ChatViewChrome`) and this panel draws none — the dead banner is shown regardless. When false
 *   the live status also moves INTO the transcript (working / sending / waiting rows), because
 *   there is no header line to carry it.
 * @param draft hoisted per-session draft text; a send clears it via `onDraftChange("")`.
 */
@Composable
fun ChatPanel(
    session: SessionInfo,
    state: ChatState,
    actions: ChatActions,
    draft: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    /** False while another panel/tab is on top — suspends the autoscroll effects. */
    active: Boolean = true,
    onOpenFile: (FilePathRef) -> Unit = {},
    // Slash control commands that need a host-owned dialog. Null = this host cannot perform it, and
    // the composer filters it out of the slash menu rather than offering a dead row.
    onRequestRename: (() -> Unit)? = null,
    onRequestMute: (() -> Unit)? = null,
    onRequestKill: (() -> Unit)? = null,
    // Headless hooks (desktop's SM_CHAT_ATTACH / SM_DICTATE / Edit ▸ Paste image), routed straight
    // through to the shared [Composer]; null in normal operation.
    externalAttach: ComposerExternalAttach? = null,
    onExternalAttachConsumed: () -> Unit = {},
    externalDictate: ComposerExternalDictate? = null,
    onExternalDictateConsumed: () -> Unit = {},
    pasteImageRequestNonce: Long = 0L,
    onPasteImageRequestConsumed: () -> Unit = {},
    /** The host's own attach chips above the composer's field (see [Composer]'s `leadingChips`). */
    composerChips: (@Composable RowScope.() -> Unit)? = null,
    // ── Header slots ────────────────────────────────────────────────────────────────
    /** This session's proxies, loaded on open and only when [showHeader]. */
    forceLinksMenu: Boolean = false,
    onForceLinksMenuConsumed: () -> Unit = {},
    /** The globe/links affordance; receives the loaded proxies + the one-shot force-open flag. */
    headerLinks: @Composable RowScope.(List<ProxyDto>, Boolean, () -> Unit) -> Unit = { _, _, _ -> },
    /**
     * The Finish flow, in the chat header. NEW ON DESKTOP: desktop's `FinishDialog` was dead code
     * (nothing ever opened it) — passing bindings here is what puts Finish on a desktop chat.
     */
    finish: FinishBindings? = null,
    /** The ⋮ overflow (rename/mute/kill/continue) — the host owns it; cluster G moves it here. */
    headerActions: @Composable RowScope.() -> Unit = {},
    /** Opens the workspace's singleton Changes pane in walkthrough mode. */
    onOpenWalkthrough: (stepId: String?) -> Unit = {},
    /**
     * Reports scroll-to-hide on a narrow view (see [ChatChromeAutoHide]): true while the transcript
     * has been scrolled down and the chrome is tucked away. A host that draws its OWN header above
     * a header-less panel (the BAR shape) hides that header on it.
     */
    onChromeHiddenChange: (Boolean) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val scope = rememberCoroutineScope()
    val pointer = LocalPointerAvailable.current
    val haptic = rememberHaptics()

    val agent = state.agent
    val working = agent?.working == true
    val sending = state.sending
    val dead = agent?.state == "dead"

    LaunchedEffect(session.id) { actions.ensureMessagesLoaded() }
    // The session's account lives in the composer's run-settings panel (with or without a header):
    // which one it runs on, and the switch to another, which outlives the panel.
    val sessionAccounts by actions.accounts.collectAsState(null)
    LaunchedEffect(actions) { actions.ensureAccounts() }
    val accountSwitch = remember(session.id, actions) { SessionAccountSwitch(scope, actions.setAccount) }
    LaunchedEffect(accountSwitch, session.account) { accountSwitch.clearRefusal() }
    val runAccount = RunSettingsAccount.forChat(
        agent = session.agent,
        accountId = session.account,
        accountLabel = session.accountLabel,
        accounts = sessionAccounts,
        switch = accountSwitch,
        onOpenAccounts = actions.openAccounts,
        onSignIn = actions.openSignIn,
    )

    var pendingRespondIds by remember(session.id) { mutableStateOf(setOf<String>()) }
    val liveRequestIds = remember(state.requests) { state.requests.map { it.requestId }.toSet() }
    LaunchedEffect(liveRequestIds) {
        pendingRespondIds = pendingRespondIds.filter { it in liveRequestIds }.toSet()
    }
    val platform = dev.supermux.ui.platform.LocalPlatform.current
    LaunchedEffect(state.lastError) {
        val err = state.lastError
        if (!err.isNullOrBlank()) platform.notices.show(err)
    }

    // ── Model + reasoning catalogs ────────────────────────────────────────────────────────────
    // Owned here so the panel can fetch-on-open, optimistically update `current` after a switch and
    // refetch reasoning when the model changes (effort visibility is model-dependent).
    // remember(session.id) resets on switch so a stale catalog never flashes.
    var modelsData by remember(session.id) { mutableStateOf<ModelsResponse?>(null) }
    var reasoningData by remember(session.id) { mutableStateOf<ReasoningResponse?>(null) }
    var openModelPicker by remember(session.id) { mutableLongStateOf(0L) }
    suspend fun refreshCatalogs() {
        modelsData = actions.loadModels()
        reasoningData = actions.loadReasoning()
    }
    LaunchedEffect(session.id) { refreshCatalogs() }
    // Re-read when the model or a catalog refresh changes; the catalog itself is the host's cache.
    val fastSupported = remember(session.model, modelsData, actions) { actions.fastSupported(session.model?.takeIf { it.isNotBlank() }) }

    // Control commands THIS panel can perform. rename/mute/kill only when the host handed us a
    // dialog for them; the composer filters everything else out of the slash menu.
    val handledControls = remember(onRequestRename, onRequestMute, onRequestKill) {
        buildSet {
            add("model"); add("stop")
            if (onRequestRename != null) add("rename")
            if (onRequestMute != null) add("mute")
            if (onRequestKill != null) add("kill")
        }
    }
    val onControl: (SlashCommand) -> Unit = { cmd ->
        when (cmd.action?.kind) {
            "rename" -> onRequestRename?.invoke()
            "mute" -> onRequestMute?.invoke()
            "kill" -> onRequestKill?.invoke()
            "model" -> scope.launch { refreshCatalogs(); openModelPicker++ }
            "stop" -> actions.interrupt()
            else -> {}
        }
    }

    val chatDetail by LocalUiPrefs.current.chatDetailLevel.collectAsState(ChatDetailLevel.MEDIUM)
    val detailMode = effectiveChatDetail(chatDetail)
    val hideTools = detailMode == ChatDetailLevel.LOW
    val highDetail = detailMode == ChatDetailLevel.HIGH
    val messages = state.messages
    val activity = state.activity
    val subagents = state.subagents
    val councils = state.councils
    // Card #76: while anything is live, the redacted "Thinking…" spinner row is not needed: the live rows are the progress.
    val live = state.live
    val liveIdle = live.isEmpty()
    val timelineItems = remember(messages, activity, hideTools, subagents, working, councils, liveIdle) {
        mergeTimeline(messages, activity, hideTools = hideTools, subagents = subagents, working = working && liveIdle, councils = councils)
    }
    // Card #54: the chat-open trace's two screen marks — the first frame that draws any of this
    // chat, and the first that draws it with its whole history loaded.
    SideEffect { dev.supermux.util.PerfTrace.countRecompose("chat") }
    LaunchedEffect(session.id, timelineItems.isNotEmpty(), state.historyLoaded) {
        val trace = dev.supermux.util.PerfTrace.chatTrace(session.id) ?: return@LaunchedEffect
        if (timelineItems.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        dev.supermux.util.PerfTrace.firstMilestone("chat.firstFrame", "items" to timelineItems.size, "messages" to messages.size, trace = trace)
        if (state.historyLoaded) {
            dev.supermux.util.PerfTrace.firstMilestone("chat.complete", "items" to timelineItems.size, "messages" to messages.size, "activity" to activity.size, trace = trace)
        }
    }
    // Card #75: a session the launcher just started — the first frame that draws its chat, and the
    // first that draws its agent's reply, end that start's trace.
    val firstReplyId = remember(messages) { messages.firstOrNull { it.direction == "outbound" && it.op == "reply" }?.id }
    LaunchedEffect(session.id, firstReplyId) {
        if (!dev.supermux.util.PerfTrace.startActive) return@LaunchedEffect
        val trace = dev.supermux.util.PerfTrace.startTraceFor(session.id) ?: return@LaunchedEffect
        withFrameNanos { }
        dev.supermux.util.PerfTrace.firstMilestone("start.chat.shown", "session" to session.id, trace = trace)
        if (firstReplyId != null) {
            dev.supermux.util.PerfTrace.firstMilestone("start.reply.drawn", "session" to session.id, trace = trace)
            dev.supermux.util.PerfTrace.endStart(trace)
        }
    }
    // LazyColumn throws (and freezes the whole Compose UI) on a duplicate key, so make every key
    // unique even if two items ever resolve to the same id: later duplicates get a #n suffix.
    val timelineKeys = remember(timelineItems) { uniqueTimelineKeys(timelineItems) }
    // Card #76: what is being written now. A live row whose stored row already arrived is not drawn twice
    // (two rows with one key would throw).
    // Card #76: the reveals of this chat's live replies, by stream (the stored reply finishes an unfinished one).
    val reveals = remember(session.id) { LiveReveals() }
    val liveRows = remember(live, timelineKeys) {
        if (live.isEmpty()) live else timelineKeys.toHashSet().let { taken -> live.filterNot { liveRowKey(it) in taken } }
    }
    // Medium: runs of consecutive tool rows render as one tool-call group.
    val toolRuns = remember(timelineItems, highDetail) { if (highDetail) emptyMap() else toolRuns(timelineItems) }
    val foldedRows = remember(toolRuns) { foldedRows(toolRuns) }

    // ── Media: every picture and clip in this chat — the fullscreen viewer's swipe set ──────────
    // Markdown host media join only where this chat reads host files, resolved against the same
    // base folder its markdown images use.
    val markdownFiles = LocalMarkdownFiles.current
    val mediaItems = remember(timelineItems, markdownFiles) {
        chatMediaItems(timelineItems, hostFiles = markdownFiles != null, baseDir = markdownFiles?.baseDir)
    }
    val latestMedia by androidx.compose.runtime.rememberUpdatedState(mediaItems)
    // Who sent each one and when, for the viewer's line under its counter.
    val mediaEntries = remember(timelineItems) { timelineItems.mapNotNull { (it as? TimelineItem.Msg)?.entry }.associateBy { it.id } }
    val mediaAgentName = remember(session.agent) { dev.supermux.ui.accounts.agentDisplayName(session.agent) }
    // The open viewer and the item it shows, kept outside this composition: a rotation or a width
    // change closed it (#89). Zoom and a clip's playback position still start afresh.
    val viewer = rememberRetained("chat.viewer:${session.id}") { MediaViewerPlace() }
    var viewerKey by viewer::open
    val mediaOpener = remember(session.id, viewer) {
        ChatMediaOpener { key ->
            latestMedia.any { it.key == key }.also { found ->
                if (found) {
                    viewer.current = key
                    viewer.open = key
                }
            }
        }
    }

    // The live agent state is carried by the header status line when there IS a header, and by the
    // transcript's own rows when there is not (Android's phone/tablet chat, which owns its header).
    val showStatusRows = !showHeader
    val toolCountSinceTurn: () -> Int = {
        val since = turnBoundaryMs(
            messages = messages.map { it.direction to it.ts },
            isUserDirection = { it == "inbound" },
            tsToEpochMs = { ts -> parseChatTs(ts) ?: 0L },
            workingSinceMs = agent?.workingSince,
        )
        countToolsSince(activity.filter { it.kind == "tool" }.map { parseChatTs(it.ts) ?: 0L }, since)
    }

    // ── Autoscroll ────────────────────────────────────────────────────────────────────────────
    // Where the reader is, kept outside this composition: an Android rotation rebuilds the panel,
    // and a width-class change rebuilds it somewhere else in the tree, and either one used to put a
    // reader who had scrolled up back at the end (#89).
    val place = rememberRetained("chat.place:${session.id}") { TranscriptPlace() }
    val listState = rememberLazyListState(place.index, place.offset)
    LaunchedEffect(listState, place) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                place.index = index
                place.offset = offset
            }
    }
    val density = LocalDensity.current
    // The find bar (open, its query, its scope) outlives this composition: a rotation or a width
    // change closed it and dropped what was typed (#89).
    val find = rememberRetained("chat.find:${session.id}") { ChatFindState() }
    // This chat's hits; All chats leaves the transcript alone.
    val findMatches = remember(timelineItems, find.open, find.query, find.scope) {
        if (find.open && find.scope == ChatSearchScope.ThisChat) chatFindMatches(timelineItems, find.query) else emptyList()
    }

    // ── Subagents ─────────────────────────────────────────────────────────────────────────────
    // Cards are collapsed by default; the set of open ones is hoisted here so the running strip can
    // open one and scroll to it. Kept outside this composition with the replies seen below: a
    // rebuilt panel (a rotation, a width change) closed every card and marked every reply read (#89).
    val cards = rememberRetained("chat.subagents:${session.id}") { SubagentCards() }
    var openSubagents by cards::open
    val subagentActions = remember(actions, session.id) {
        SubagentActions(message = actions.messageSubagent, stop = actions.stopSubagent, sessionId = session.id)
    }
    // Unread replies are client-side: the reply count the user has "seen" per subagent. A subagent
    // first seen counts as read (a reload does not light every card up); an OPEN card keeps up with
    // its replies; a collapsed one falls behind, and the gap is its "N new replies" badge.
    var seenReplies by cards::seen
    LaunchedEffect(subagents, openSubagents) {
        var next = seenReplies
        for (s in subagents) {
            val r = s.replies ?: 0
            if (s.id !in next || (s.id in openSubagents && next[s.id] != r)) next = next + (s.id to r)
        }
        if (next !== seenReplies) seenReplies = next
    }
    val openSubagentCard: (String) -> Unit = { id ->
        openSubagents = openSubagents + id
        val index = timelineItems.indexOfFirst { it is TimelineItem.SubagentCard && it.subagent.id == id }
        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
    }
    val subagentNames = remember(subagents) { subagents.associate { it.id to it.displayName } }
    val subagentUi = SubagentUi(
        isExpanded = { it in openSubagents },
        onToggle = { id -> openSubagents = if (id in openSubagents) openSubagents - id else openSubagents + id },
        actions = subagentActions,
        unread = { s -> s.unreadReplies(seenReplies[s.id] ?: (s.replies ?: 0)) },
        open = openSubagentCard,
        nameOf = { subagentNames[it] },
    )
    // An inbox sender's chat opens from its card only when this host lists it.
    val listedSessions = state.listedSessions
    val canOpenChat = remember(listedSessions) { { id: String -> id in listedSessions } }

    run {
        // One rule on every host (was desktop's): instant jump to the end on the first content for a
        // session, then follow new content only while the reader is AT the end — the transcript's
        // bottom edge on screen, give or take [FOLLOW_SLOP]. Anywhere above stays put, inside a reply
        // taller than the screen too: "one of the last two rows is on screen" used to count as the
        // bottom, so scrolling back into a long last reply jumped the reader to its end.
        // In [place], so a rebuilt panel knows the rows it already showed and whether the reader was
        // following them: it does not jump a reader who had scrolled up back to the end.
        var prevSize by place::rows
        var autoFollow by place::autoFollow
        // Our own follow scrolls moving the transcript (a new row's, the glide): the positions they pass are not the
        // reader's. A count, not a flag (card #76): the glide takes the scroll from a new row's follow, and the follow
        // that stops must not unmark the glide still moving, or its positions read as the reader leaving the end.
        var followScrolls by remember(session.id) { mutableIntStateOf(0) }
        // The tail below can see a follow scroll's last step only after that scroll has ended: a new row restarting
        // the follow mid-scroll left the transcript short of the end, and that step read as the reader leaving, so
        // nothing followed again (card #76, a slow web page). A follow that finished, or that this panel stopped,
        // keeps its last positions ours for one more look; one the reader's scroll took over does not.
        var followScrollEnded by remember(session.id) { mutableStateOf(false) }
        // Medium folds a tool run into its first row and draws nothing for the rest, so "the last row"
        // is the last row that draws, not the last index.
        // Card #76: live rows come after the timeline, so the last one of them is the transcript's last row.
        val lastShown by rememberUpdatedState(remember(timelineItems, toolRuns, liveRows.size) {
            if (liveRows.isNotEmpty()) timelineItems.size + liveRows.size - 1 else shownTail(timelineItems.size, toolRuns).first
        })
        val panelActive by rememberUpdatedState(active)
        val followSlop = with(density) { FOLLOW_SLOP.toPx() }
        // Growth that lands mid-scroll is held, and applied when that scroll settles if the reader is
        // still following then.
        var pendingPin by remember(session.id) { mutableStateOf(false) }
        // After the frame (a scroll from inside a layout pass throws), then past the end: scrollBy
        // clamps there, the same way the jump-to-bottom button settles on a tall last row. A find hit
        // picked during that frame holds the transcript: its scroll must not be cancelled by this one.
        suspend fun pinToEnd() {
            withFrameNanos { }
            if (find.current < 0) listState.scrollBy(100_000f)
        }
        // Card #76: a revealing reply (the live row, or the stored row finishing its reveal) grows a word at a time;
        // while following, the transcript eases after it instead of jumping a line at a time. Reduced motion jumps.
        val reduceMotion = dev.supermux.ui.theme.LocalReduceMotion.current
        val revealingTail = liveRows.lastOrNull()?.let { !it.thinking } == true ||
            (liveRows.isEmpty() && (timelineItems.lastOrNull() as? TimelineItem.Msg)?.entry?.stream?.let { reveals.unfinished(it) } != null)
        val glideTail by rememberUpdatedState(!reduceMotion && revealingTail)
        val glides = remember(session.id) { Channel<Unit>(Channel.CONFLATED) }
        LaunchedEffect(session.id, listState) {
            for (request in glides) {
                followScrolls++
                var settled = false
                try {
                    glideToEnd(listState)
                    settled = true
                } catch (e: CancellationException) {
                    // The reader took the scroll: theirs wins. Only a real cancellation ends this loop.
                    currentCoroutineContext().ensureActive()
                } finally {
                    followScrolls--
                    if (settled) followScrollEnded = true
                }
            }
        }
        LaunchedEffect(session.id, listState) {
            var seen: TranscriptTail? = null
            snapshotFlow {
                TranscriptTail(
                    firstIndex = listState.firstVisibleItemIndex,
                    firstOffset = listState.firstVisibleItemScrollOffset,
                    atEnd = transcriptAtEnd(listState, followSlop),
                    lastRow = lastShown,
                    lastRowSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastShown }?.size ?: -1,
                    ours = followScrolls > 0 || followScrollEnded,
                )
            }.collect { now ->
                val before = seen
                seen = now
                // That one look at a follow scroll that ended is used up.
                if (followScrollEnded && followScrolls == 0) followScrollEnded = false
                if (before == null) return@collect
                // Following is decided by where the transcript is SCROLLED. Rows that grow or arrive
                // under a still transcript leave it as it was: a new row below would otherwise read as
                // "the reader left the end" before it could be followed.
                if (!now.ours && (now.firstIndex != before.firstIndex || now.firstOffset != before.firstOffset)) {
                    autoFollow = now.atEnd
                }
                // A tool group grows inside ONE row (calls arrive, rows spring open), which the
                // item-count follow below never sees: while following, keep the end pinned as that
                // last row grows. A row scrolling back into view has not grown.
                val grew = now.lastRow == before.lastRow && before.lastRowSize >= 0 && now.lastRowSize > before.lastRowSize
                if (grew && panelActive && autoFollow && find.current < 0 && listState.canScrollForward) {
                    when {
                        glideTail -> glides.trySend(Unit)
                        listState.isScrollInProgress -> pendingPin = true
                        else -> launch { pinToEnd() }
                    }
                }
            }
        }
        LaunchedEffect(session.id, listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling && pendingPin) {
                    pendingPin = false
                    if (autoFollow && listState.canScrollForward) launch { pinToEnd() }
                }
            }
        }
        // Card #76: live rows count as rows: one appearing is followed like a new message, and the stored row
        // taking a live row's place adds none.
        val rowCount = timelineItems.size + liveRows.size
        LaunchedEffect(session.id, rowCount, working, active) {
            if (!active) return@LaunchedEffect
            val target = rowCount - 1
            if (target < 0) return@LaunchedEffect
            val first = prevSize < 0
            // A find hit on screen holds the transcript where it is.
            if (first || (rowCount > prevSize && autoFollow && find.current < 0)) {
                followScrolls++
                var settled = false
                try {
                    if (first) listState.scrollToItem(target) else listState.animateScrollToItem(target)
                    // The last row may be taller than the screen: settle on its end, not its top.
                    pinToEnd()
                    settled = true
                } finally {
                    followScrolls--
                    // Stopped by this effect restarting (a newer row): its last step is ours too.
                    if (settled || !currentCoroutineContext().isActive) followScrollEnded = true
                }
            }
            prevSize = rowCount
        }
}

    // ── Find ──────────────────────────────────────────────────────────────────────────────────
    // After the autoscroll above on purpose: a jump's scroll must land after the first-content one.
    val searchHost = LocalChatSearch.current
    // The message a picked All-chats hit lands on, held until the transcript has it.
    var findTarget by remember(session.id) { mutableStateOf<String?>(null) }
    var findQueryShown by remember(session.id) { mutableStateOf("") }
    val pendingJump = searchHost?.jump?.collectAsState()?.value
    LaunchedEffect(pendingJump, session.id) {
        val jump = pendingJump?.takeIf { it.sessionId == session.id } ?: return@LaunchedEffect
        searchHost.consume(jump)
        if (jump.messageId == null) {
            find.show()
        } else {
            findTarget = jump.messageId
            find.query = jump.query
            find.show(ChatSearchScope.ThisChat)
        }
    }
    LaunchedEffect(findMatches) {
        val target = findTarget
        if (target != null) {
            val at = findMatches.indexOfFirst { (timelineItems[it] as? TimelineItem.Msg)?.entry?.id == target }
            if (at < 0) return@LaunchedEffect // not loaded yet
            findTarget = null
            find.current = at
        } else if (find.query != findQueryShown || find.current !in findMatches.indices) {
            find.current = findMatches.lastIndex // newest first
        }
        findQueryShown = find.query
    }
    val findCurrentIndex = findMatches.getOrNull(find.current) ?: -1
    val findCurrentKey = timelineKeys.getOrNull(findCurrentIndex)
    val findMatchSet = remember(findMatches) { findMatches.toHashSet() }
    LaunchedEffect(findCurrentKey) {
        if (findCurrentKey == null) return@LaunchedEffect
        val index = timelineKeys.indexOf(findCurrentKey)
        // Clear of the find bar floating over the transcript's top.
        if (index >= 0) listState.animateScrollToItem(index, scrollOffset = -with(density) { 96.dp.roundToPx() })
    }

    // ── Header status line ─────────────────────────────────────────────────────────────────────
    // Priority: dead (banner) > working > sending > agents working > waiting.
    val statusText: String? = when {
        dead -> null
        working && agent != null -> {
            if (hideTools) {
                val base = when (agent.detail) { "running" -> "running"; "starting" -> "starting"; else -> "thinking" }
                formatLowWorkingStatus(base, agent.detail, agent.tool, toolCountSinceTurn(), "")
            } else {
                when (agent.detail) {
                    "running" -> "running ${agent.tool ?: "tool"}…"
                    "starting" -> "starting…"
                    else -> "thinking…"
                }
            }
        }
        sending -> "sending…"
        agent != null && agent.agentsRunning > 0 -> agentsWorkingLabel(agent.agentsRunning)
        agent?.waiting == true -> "waiting (${agent.bgOpen} background)"
        else -> null
    }
    val statusColor = if (agent?.waiting == true && agent.agentsRunning == 0 && !working && !sending) sem.warning else cs.primary

    // Proxies are a plain load-on-open; cleared first so the previous session's list can't
    // transiently render filtered-for-the-new-session while the fresh load is in flight.
    var proxies by remember(session.id) { mutableStateOf<List<ProxyDto>>(emptyList()) }
    LaunchedEffect(session.id, showHeader) {
        if (!showHeader) return@LaunchedEffect
        proxies = emptyList()
        proxies = actions.loadProxies()
    }

    val focusManager = LocalFocusManager.current
    val emptySession = timelineItems.isEmpty() && !working

    // ── Scroll-to-hide chrome ──────────────────────────────────────────────────────────────────
    // Only on a NARROW view (phone-width, whatever the device): scrolling down tucks the header and
    // the composer away, scrolling up brings them back. Never on an empty session (the starters
    // need the composer), and never on a touch host while the composer has
    // focus — the soft keyboard is up, you are typing. A pointer host keeps focus in the composer
    // while you read, so there focus does not pin it; typing into it brings it back instead.
    val chrome = remember(session.id, listState) {
        val edgeZone = with(density) { 160.dp.toPx() }
        ChatChromeAutoHide(with(density) { 24.dp.toPx() }, edge = { transcriptEdge(listState, edgeZone) })
    }
    var panelWidth by remember { mutableStateOf(0.dp) }
    var composerFocused by remember(session.id) { mutableStateOf(false) }
    val narrowView = panelWidth > 0.dp && panelWidth < CHAT_CHROME_AUTO_HIDE_MAX_WIDTH
    val autoHideAllowed = narrowView && !emptySession && !(composerFocused && !pointer)
    LaunchedEffect(autoHideAllowed) { if (!autoHideAllowed) chrome.show() }
    LaunchedEffect(draft) { chrome.show() }
    val chromeCollapsed = autoHideAllowed && chrome.hidden
    val chromeFraction by animateFloatAsState(
        targetValue = if (chromeCollapsed) 0f else 1f,
        animationSpec = tween(200),
        label = "chatChrome",
    )
    LaunchedEffect(chromeCollapsed) { onChromeHiddenChange(chromeCollapsed) }
    // Revealed at the end of the transcript: the composer growing back shrinks the viewport, which
    // would push the last lines out of view. Keep the bottom pinned while it animates in.
    LaunchedEffect(chromeCollapsed) {
        if (chromeCollapsed || listState.canScrollForward) return@LaunchedEffect
        snapshotFlow { chromeFraction }.collect { if (it < 1f) listState.scrollBy(100_000f) }
    }

    // The WHOLE panel accepts dropped files, not just the composer card: a drop on the transcript
    // stages into the composer through the same funnel (the card highlights while dragging).
    val dropState = remember(session.id) { ComposerDropState() }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.surfaceContainerLow)
            .onSizeChanged { panelWidth = with(density) { it.width.toDp() } }
            .onPreviewKeyEvent { event ->
                if (!isFindChord(event)) return@onPreviewKeyEvent false
                find.show()
                true
            }
            .externalFileDropTarget(
                enabled = actions.upload != null,
                onDragOver = { dropState.dragOver = it },
                onFiles = { dropState.deliver(it) },
            )
            .testTag(TestIds.CHAT_VIEW),
    ) {
        if (showHeader) {
            // One fixed-height line: the name and the live status share a baseline, so the header
            // never grows/shrinks (and the transcript never shifts) as the agent starts and stops
            // working. No bar and no rule — it shares the panel's background and the transcript
            // dissolves into it through the scrim below.
            // Responsive: the same header at every width, shedding the least important parts as it
            // narrows — the project crumb first, then the status words (the dot stays), then the
            // Chat/Native labels.
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxWidth().collapseVertically({ chromeFraction }, slideUp = true),
            ) {
            val headerWidth = maxWidth
            val showProject = headerWidth >= 560.dp
            val showStatusText = headerWidth >= 460.dp
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(start = if (pointer) Space.lg else Space.md, end = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    // Breadcrumb, not a title: the PROJECT the session belongs to, then the session.
                    val projectName = projectLabel(session, inferHomeDir(session.workdir))
                    if (showProject && projectName.isNotEmpty()) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = projectName,
                            fontSize = 13.sp,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = "/",
                            fontSize = 13.sp,
                            color = cs.outline,
                            modifier = Modifier.padding(horizontal = 7.dp),
                        )
                    }
                    Text(
                        text = session.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (statusText != null) {
                        Spacer(Modifier.width(Space.sm))
                        Box(Modifier.size(5.dp).clip(CircleShape).background(statusColor))
                        if (showStatusText) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        }
                    }
                }
                IconButton(
                    onClick = { find.show() },
                    modifier = Modifier.size(32.dp).testTag("chat_find_open"),
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Search chats",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
                headerLinks(proxies, forceLinksMenu, onForceLinksMenuConsumed)
                headerActions()
            }
            }
        }
        // "Not responding" dead banner (error-tinted strip).
        if (dead) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(cs.errorContainer)
                    .padding(horizontal = Space.lg, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Not responding",
                    color = cs.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        }

        val composer: @Composable () -> Unit = {
            Composer(
                draft = draft,
                onDraftChange = onDraftChange,
                sending = sending,
                // Stop lives in the composer's trailing slot on every host.
                agentWorking = working,
                // Scope staged attachments to this session: the panel stays composed across a
                // switch, so without this a chip staged against A would leak into B's send.
                sessionKey = session.id,
                onSend = { text, fileIds ->
                    actions.send(text, fileIds)
                    onDraftChange("")
                },
                onInterrupt = actions.interrupt,
                onUpload = actions.upload,
                onTranscribeAudio = actions.transcribeAudio,
                actions = actions.composer,
                commands = state.commands,
                commandsResolved = state.commandsResolved,
                onControl = onControl,
                handledControlKinds = handledControls,
                openModelPickerNonce = openModelPicker,
                placeholder = DEFAULT_COMPOSER_PLACEHOLDER,
                externalAttach = externalAttach,
                onExternalAttachConsumed = onExternalAttachConsumed,
                leadingChips = composerChips,
                dropState = dropState,
                externalDictate = externalDictate,
                onExternalDictateConsumed = onExternalDictateConsumed,
                pasteImageRequestNonce = pasteImageRequestNonce,
                onPasteImageRequestConsumed = onPasteImageRequestConsumed,
                models = modelsData,
                reasoning = reasoningData,
                sessionModel = session.model,
                sessionReasoning = session.reasoningLevel,
                sessionAgent = session.agent,
                permissionModes = state.permissionModes,
                sessionPermissionMode = session.permissionMode,
                onSetPermissionMode = actions.setPermissionMode,
                onPickModel = { model ->
                    scope.launch {
                        if (actions.pickModel(model)) {
                            modelsData = modelsData?.copy(current = model.ifBlank { null })
                            reasoningData = actions.loadReasoningFor(model.ifBlank { null })
                        }
                    }
                },
                onPickReasoning = { level ->
                    scope.launch {
                        if (actions.pickReasoning(level)) {
                            reasoningData = reasoningData?.copy(current = level)
                        }
                    }
                },
                fastMode = (session.fastMode == true).takeIf { fastSupported },
                onToggleFast = { on -> scope.launch { actions.pickFast(on) } },
                account = runAccount,
                // Re-fetch when a picker opens so a catalog that changed since open is current; an
                // old refusal of an account switch has nothing left to say.
                onPickerOpened = {
                    accountSwitch.clearRefusal()
                    scope.launch { refreshCatalogs() }
                },
                tray = {
                    ComposerFooter(
                        session = session,
                        onFetch = actions.composer.gitFetch,
                        onPull = actions.composer.gitPull,
                        onPush = actions.composer.gitPush,
                        onPublish = actions.composer.gitPublish,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val transcript: @Composable (bottomPadding: androidx.compose.ui.unit.Dp) -> Unit = { bottomPad ->
            androidx.compose.runtime.CompositionLocalProvider(
                LocalCouncilActivity provides state.councilActivity,
                LocalChatMediaViewer provides mediaOpener,
                LocalLiveReveals provides reveals,
            ) {
            LazyColumn(
                state = listState,
                // widthIn BEFORE fillMaxHeight: cap the reading width first, then fill the height.
                modifier = Modifier
                    .timelineReadingWidth()
                    .fillMaxHeight()
                    .padding(horizontal = if (pointer) Space.lg else Space.md),
                // Vertical inset as CONTENT padding, not a Modifier pad: a Modifier pad clips the
                // scroll viewport so rows pop into view at a hard edge instead of scrolling under
                // the scrim.
                contentPadding = PaddingValues(top = Space.md, bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                itemsIndexed(timelineItems, key = { i, _ -> timelineKeys[i] }) { i, item ->
                    val findTint = when {
                        i == findCurrentIndex -> cs.primary.copy(alpha = 0.14f)
                        i in findMatchSet -> cs.primary.copy(alpha = 0.05f)
                        else -> null
                    }
                    Box(
                        if (findTint == null) Modifier
                        else Modifier.background(findTint, RoundedCornerShape(Radii.sm)).padding(horizontal = Space.sm),
                    ) {
                        val run = toolRuns[i]
                        when {
                            run != null -> ToolRunGroup(run.tools, active = working && i + run.span - 1 == timelineItems.lastIndex)
                            i in foldedRows -> Unit
                            else -> TimelineItemRow(
                                item = item,
                                media = actions.media,
                                onOpenFile = onOpenFile,
                                highDetail = highDetail,
                                onOpenWalkthrough = { onOpenWalkthrough(null) },
                                subagentUi = subagentUi,
                                canOpenChat = canOpenChat,
                                endCouncil = actions.endCouncil,
                            )
                        }
                    }
                }
                // Card #76: what the agent is writing right now, keyed like the stored rows that replace it.
                items(liveRows, key = ::liveRowKey) { row ->
                    if (row.thinking) LiveThinkingRow(row, folded = liveRows.any { !it.thinking })
                    else LiveReplyRow(row, reveals.of(row.stream), onOpenFile)
                }
                // Running background tasks live in the strip over the composer (with the running
                // subagents), not as chips in the transcript: one place for "still going".
                if (showStatusRows) {
                    if (working && agent != null) {
                        item(key = "__working__") {
                            WorkingIndicator(
                                agent = agent,
                                onStop = actions.interrupt,
                                detailLow = hideTools,
                                toolCount = if (hideTools) toolCountSinceTurn() else 0,
                            )
                        }
                    } else if (sending) {
                        item(key = "__sending__") { SendingIndicator(onStop = actions.interrupt) }
                    } else if (agent != null && agent.agentsRunning > 0) {
                        item(key = "__agents_working__") { AgentsWorkingIndicator(agent.agentsRunning) }
                    } else if (agent?.waiting == true) {
                        item(key = "__waiting__") { WaitingIndicator(agent.bgOpen) }
                    }
                }
            }
            }
        }

        val starters: @Composable () -> Unit = {
            Column(
                modifier = Modifier.fillMaxSize().padding(Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Spacer(Modifier.height(36.dp))
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    "Start the conversation",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                )
                listOf("What's the current state?", "Run the tests", "Summarize recent changes")
                    .forEachIndexed { i, prompt ->
                        Surface(
                            onClick = {
                                haptic.perform(HapticKind.Confirm)
                                actions.send(prompt, emptyList())
                            },
                            shape = RoundedCornerShape(Radii.md),
                            color = cs.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().testTag("chat_starter_$i"),
                        ) {
                            Text(
                                prompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurface,
                                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.md),
                            )
                        }
                    }
            }
        }

        viewerKey?.let { key ->
            ChatMediaViewer(
                items = mediaItems,
                startKey = viewer.current ?: key,
                resolve = { item -> chatMediaHandle(item, actions.media, markdownFiles, platform.files::probeMime) },
                onDismiss = {
                    viewerKey = null
                    viewer.current = null
                },
                caption = { item ->
                    mediaEntries[item.entryId]?.let { mediaCaption(it, mediaAgentName, Clock.System.now().toEpochMilliseconds()) }
                },
                onCurrentChange = { viewer.current = it },
            )
        }

        // Body: transcript + composer.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            run {
                // Docked composer under the transcript on every host; a tap on the transcript drops focus.
                // A tap while the chrome is tucked away brings it back (the reading-app convention).
                Column(Modifier.testTag("chat_body").pointerInput(chrome) { detectTapGestures(onTap = { focusManager.clearFocus(); chrome.show() }) }) {
                    Box(Modifier.fillMaxWidth().weight(1f).nestedScroll(chrome.connection), contentAlignment = Alignment.TopCenter) {
                        // With the composer tucked away the last row would sit on the window's edge; give
                        // it the breathing room the composer's own padding gave it.
                        if (emptySession) starters() else transcript(Space.xl * (1f - chromeFraction))
                        // Fade, not a rule: a short scrim of the panel's own background so a
                        // message scrolling up dissolves into the header. Non-interactive.
                        EdgeFade(cs.surfaceContainerLow, Modifier.align(Alignment.TopCenter))
                        if (find.open) {
                            ChatFindBar(
                                find = find,
                                matchCount = findMatches.size,
                                onOlder = {
                                    if (findMatches.isNotEmpty()) {
                                        find.current = if (find.current <= 0) findMatches.lastIndex else find.current - 1
                                    }
                                },
                                onNewer = {
                                    if (findMatches.isNotEmpty()) find.current = (find.current + 1) % findMatches.size
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(Space.sm)
                                    .widthIn(max = 440.dp)
                                    .fillMaxWidth(),
                            )
                        }
                        if (!emptySession) {
                            JumpToBottomButton(
                                listState = listState,
                                onJumped = { chrome.show() },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.md),
                            )
                        }
                    }
                    WalkthroughUnreadChip(state, onOpenWalkthrough, Modifier.align(Alignment.CenterHorizontally))
                    // A pointer host can still raise a soft keyboard (a tablet with a mouse, DeX
                    // with the phone as touchpad), so the composer clears it here too; the inset
                    // is zero on a desktop, where this is the layout it always was.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .collapseVertically({ chromeFraction }, slideUp = false)
                            .onFocusChanged { composerFocused = it.hasFocus }
                            .testTag("chat_composer_dock")
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            Modifier
                                .widthIn(max = CONTENT_MAX_WIDTH)
                                .padding(start = Space.lg, end = Space.lg, bottom = Space.sm),
                        ) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalCouncilActivity provides state.councilActivity) {
                                CouncilStrips(
                                    cards = remember(timelineItems) { timelineItems.filterIsInstance<TimelineItem.CouncilCard>() },
                                    onOpenFile = onOpenFile,
                                    endCouncil = actions.endCouncil,
                                )
                            }
                            RunningStrip(
                                subagents = subagents,
                                bgTasks = state.bgTasks,
                                onOpenSubagent = openSubagentCard,
                                activity = activity,
                                agent = session.agent,
                            )
                            RequestCards(
                                requests = state.requests,
                                closed = state.closedRequests,
                                disabledIds = pendingRespondIds,
                                onRespond = { requestId, answer ->
                                    pendingRespondIds = pendingRespondIds + requestId
                                    actions.respondRequest(requestId, answer)
                                },
                                modifier = Modifier.padding(bottom = Space.sm),
                            )
                            composer()
                        }
                    }
                }
}
        }
    }
}

@Composable
private fun WalkthroughUnreadChip(
    state: ChatState,
    onOpenWalkthrough: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.walkthroughUnread <= 0) return
    TextButton(
        onClick = { onOpenWalkthrough(state.walkthroughUnreadStepId) },
        modifier = modifier.testTag("walkthrough_unread_chip"),
    ) {
        Text("💬 ${state.walkthroughUnread} new walkthrough replies")
    }
}

/**
 * Round "jump to the latest" button floated over the bottom of the transcript. It only appears once
 * the reader has scrolled well away from the end — more than about a screen, or two whole rows below
 * the viewport — so the ordinary small drift of reading the last reply never summons it.
 */
@Composable
private fun JumpToBottomButton(
    listState: androidx.compose.foundation.lazy.LazyListState,
    onJumped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val visible by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf false
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val below = last.offset + last.size - info.viewportEndOffset
            last.index < info.totalItemsCount - 2 || below > viewport
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        Surface(
            onClick = {
                haptic.perform(HapticKind.Confirm)
                scope.launch {
                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                    if (lastIndex < 0) return@launch
                    listState.animateScrollToItem(lastIndex)
                    // The last row may be taller than the viewport: settle on its end, not its top.
                    listState.scrollBy(100_000f)
                    onJumped()
                }
            },
            // A crisp disc with a hairline rim and a low shadow: a heavy default-black shadow on a
            // near-background fill read as a muddy grey smudge in light mode.
            shape = CircleShape,
            color = cs.surface,
            contentColor = cs.onSurfaceVariant,
            border = BorderStroke(1.dp, cs.outlineVariant),
            shadowElevation = 2.dp,
            modifier = Modifier
                .size(36.dp)
                .testTag("chat_jump_to_bottom"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom", modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Epoch millis without `java.time` — `:ui` commonMain has to compile for iOS too. */
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Live "working… · Ns" indicator pinned to the bottom of the transcript (iOS workingIndicator
 * parity): a mono phase label + elapsed duration, and a Stop capsule that interrupts the agent.
 * Ticks every 1s, recomputing elapsed from `agent.workingSince` (epoch-ms).
 */
@Composable
private fun WorkingIndicator(
    agent: AgentStatus,
    onStop: () -> Unit,
    detailLow: Boolean = false,
    toolCount: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = nowMs()
        }
    }
    val elapsed = agent.workingSince?.let { ((now - it).coerceAtLeast(0)) / 1000 }
    val durationLabel = elapsed?.let { formatDuration(it) } ?: ""
    // Medium: platform baseline (working/thinking + tool). Low: shared enrichment with tool count.
    val label = if (detailLow) {
        formatLowWorkingStatus(
            baseLabel = workingBaseLabel(agent.detail),
            detail = agent.detail,
            tool = agent.tool,
            toolCount = toolCount,
            durationLabel = durationLabel,
        )
    } else {
        val base = workingBaseLabel(agent.detail) +
            (agent.tool?.takeIf { agent.detail == "running" }?.let { " · $it" } ?: "")
        base + (if (durationLabel.isNotEmpty()) " · $durationLabel" else "")
    }
    StatusRow(label = label, color = cs.primary, stopTag = "working_stop", onStop = onStop)
}

/**
 * Client-local "sending" indicator shown between the user tapping Send and the first `agent_state`
 * frame arriving. No timer; same Stop capsule so the user can cancel immediately after sending.
 */
@Composable
private fun SendingIndicator(onStop: () -> Unit) {
    StatusRow(
        label = "sending",
        color = MaterialTheme.colorScheme.primary,
        stopTag = "sending_stop",
        onStop = onStop,
    )
}

/**
 * "waiting · N background tasks" — the turn is over but the harness will wake the agent when its
 * background tasks finish. Amber = attention-not-error; no Stop (the agent is idle).
 */
@Composable
private fun WaitingIndicator(bgOpen: Int) {
    StatusRow(
        label = "waiting · " + if (bgOpen == 1) "1 background task" else "$bgOpen background tasks",
        color = LocalSemantics.current.warning,
        stopTag = null,
        onStop = {},
    )
}

private fun agentsWorkingLabel(n: Int) = if (n == 1) "1 agent working" else "$n agents working"

/**
 * The parent's turn is over but agents it started are still running: the session is working.
 * No Stop here — each agent stops from its lane in the running strip.
 */
@Composable
private fun AgentsWorkingIndicator(agentsRunning: Int) {
    StatusRow(
        label = agentsWorkingLabel(agentsRunning) + "…",
        color = MaterialTheme.colorScheme.primary,
        stopTag = null,
        onStop = {},
    )
}

/** Minimal live status: mono label + optional compact stop capsule — no gutter pulse dots. */
@Composable
private fun StatusRow(label: String, color: androidx.compose.ui.graphics.Color, stopTag: String?, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontFamily = MonoFontFamily, fontSize = 12.sp, color = color)
        if (stopTag != null) {
            Spacer(Modifier.width(Space.sm))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.sm))
                    .clickable { haptic.perform(HapticKind.Tick); onStop() }
                    .testTag(stopTag)
                    .padding(horizontal = Space.sm, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Filled.Square,
                    contentDescription = "Stop",
                    tint = cs.error,
                    modifier = Modifier.size(10.dp),
                )
                Text("stop", fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.error)
            }
        }
    }
}

/** Where a reader is in one session's transcript, kept for a rebuilt [ChatPanel] (`rememberRetained`, #89). */
private class TranscriptPlace {
    var index = 0
    var offset = 0
    /** Rows already shown; -1 before the first content, which jumps to the end. */
    var rows by mutableIntStateOf(-1)
    var autoFollow by mutableStateOf(true)
}

/** One session's open media viewer, kept for a rebuilt [ChatPanel] (`rememberRetained`, #89). */
private class MediaViewerPlace {
    /** The item the viewer was opened on; null when it is closed. */
    var open by mutableStateOf<String?>(null)
    /** The item on screen as the user pages, where a rebuilt viewer starts. Read once, so not observed. */
    var current: String? = null
}

/** One session's open subagent cards and the replies seen, kept for a rebuilt [ChatPanel] (`rememberRetained`, #89). */
private class SubagentCards {
    var open by mutableStateOf(setOf<String>())
    var seen by mutableStateOf(mapOf<String, Int>())
}
