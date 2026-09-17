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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import dev.supermux.net.ChunkSource
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.session.inferHomeDir
import dev.supermux.session.projectLabel
import dev.supermux.state.HostStore
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.TestIds
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.countToolsSince
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.effectiveChatDetail
import dev.supermux.ui.formatLowWorkingStatus
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.shell.AgentViewToggle
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.turnBoundaryMs
import dev.supermux.ui.widgets.KeepAlivePanel
import dev.supermux.ui.widgets.keepAlivePanel
import dev.supermux.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Reading-width cap for the composer so it lines up with the timeline's own cap. */
private val CONTENT_MAX_WIDTH = 860.dp

/** Height of the fade that carries the transcript into the header above it. */
private val EDGE_FADE = 28.dp

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
    val loadBytes: suspend (String) -> ByteArray? = { null },
    /** The composer's non-hot seams (drafts, slash cleanup, glossary, pending-first, git ops). */
    val composer: ComposerActions = ComposerActions(),
    val loadModels: suspend () -> ModelsResponse? = { null },
    val loadReasoning: suspend () -> ReasoningResponse? = { null },
    /** Returns true when the switch took, so the panel can update its shown `current`. */
    val pickModel: suspend (String) -> Boolean = { false },
    val pickReasoning: suspend (String) -> Boolean = { false },
    /** Transcript loaded lazily on open (archive-resumed sessions have no snapshot history). */
    val ensureMessagesLoaded: suspend () -> Unit = {},
    /** Session proxies for the header's links slot. */
    val loadProxies: suspend () -> List<ProxyDto> = { emptyList() },
)

/** [ChatActions] wired to a [HostStore] for one session — desktop's ergonomics, kept. */
@Composable
fun rememberChatActions(
    app: HostStore,
    session: SessionInfo,
    /** Override the proxy load (the headless SM_LINKS_MENU hook injects a canned list). */
    loadProxies: (suspend () -> List<ProxyDto>)? = null,
): ChatActions {
    val composerActions = rememberComposerActions(app, session.id)
    return remember(app, session.id, composerActions, loadProxies) {
        ChatActions(
            send = { text, fileIds -> app.sendMessage(session.id, text, fileIds) },
            interrupt = { app.interrupt(session.id) },
            upload = { source, name, mime, kind, onProgress ->
                app.uploadResumable(session.id, source, name, mime, kind, onProgress)
            },
            transcribeAudio = { bytes, name, mime -> app.transcribeAudio(session.id, bytes, name, mime)?.text },
            loadBytes = { id -> app.fileBytes(id) },
            composer = composerActions,
            loadModels = { app.sessionModels(session.id) },
            loadReasoning = { app.sessionReasoning(session.id) },
            pickModel = { model -> app.switchModel(session.id, model) },
            pickReasoning = { level -> app.switchReasoning(session.id, level) },
            ensureMessagesLoaded = { app.ensureMessagesLoaded(session.id) },
            loadProxies = loadProxies ?: { app.proxies() },
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
    )
}

/**
 * Stable list key so the optimistic→real id swap (local-echo → broker MessageAppend) doesn't
 * flicker or lose scroll position: tool rows key off callId (falling back to kind:seq:ts), message
 * rows off the entry id. Identical on both hosts before the merge.
 */
private fun timelineItemKey(item: TimelineItem): String = when (item) {
    is TimelineItem.Msg -> "m:${item.entry.id}"
    is TimelineItem.Tool -> "t:${item.event.callId ?: "${item.event.kind}:${item.event.seq}:${item.event.ts}"}"
}

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
    /**
     * The agent's raw ("Native") PTY. Non-null AND agent == claude shows the Chat⇄Native pill; the
     * lambda receives an `onExit` to call when the PTY dies.
     */
    nativeContent: (@Composable (onExit: () -> Unit) -> Unit)? = null,
    /** Opens the workspace's singleton Changes pane in walkthrough mode. */
    onOpenWalkthrough: (stepId: String?) -> Unit = {},
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
    val timelineItems = remember(messages, activity, hideTools) {
        mergeTimeline(messages, activity, hideTools = hideTools)
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
    val listState = rememberLazyListState()

    run {
        // One rule on every host (was desktop's): instant jump on the first content for a session, then follow new items
        // only while the user is at/near the bottom (reading history stays put).
        var prevSize by remember(session.id) { mutableIntStateOf(-1) }
        var autoFollow by remember(session.id) { mutableStateOf(true) }
        LaunchedEffect(session.id, listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
            }.collect { nearBottom -> autoFollow = nearBottom }
        }
        LaunchedEffect(session.id, timelineItems.size, working, active) {
            if (!active) return@LaunchedEffect
            val target = timelineItems.size - 1
            if (target < 0) return@LaunchedEffect
            when {
                prevSize < 0 -> listState.scrollToItem(target)
                (timelineItems.size > prevSize) && autoFollow -> listState.animateScrollToItem(target)
            }
            prevSize = timelineItems.size
        }
}

    // ── Header status line ─────────────────────────────────────────────────────────────────────
    // Priority: dead (banner) > working > sending > waiting.
    val statusText: String? = when {
        dead -> null
        working && agent != null -> {
            if (hideTools) {
                val base = if (agent.detail == "running") "running" else "thinking"
                formatLowWorkingStatus(base, agent.detail, agent.tool, toolCountSinceTurn(), "")
            } else {
                if (agent.detail == "running") "running ${agent.tool ?: "tool"}…" else "thinking…"
            }
        }
        sending -> "sending…"
        agent?.waiting == true -> "waiting (${agent.bgOpen} background)"
        else -> null
    }
    val statusColor = if (agent?.waiting == true && !working && !sending) sem.warning else cs.primary

    // Proxies are a plain load-on-open; cleared first so the previous session's list can't
    // transiently render filtered-for-the-new-session while the fresh load is in flight.
    var proxies by remember(session.id) { mutableStateOf<List<ProxyDto>>(emptyList()) }
    LaunchedEffect(session.id, showHeader) {
        if (!showHeader) return@LaunchedEffect
        proxies = emptyList()
        proxies = actions.loadProxies()
    }

    val hasNative = nativeContent != null && session.agent == "claude"
    var nativeView by remember(session.id) { mutableStateOf(false) }
    var nativeOpened by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id, nativeView) { if (nativeView) nativeOpened = true }
    val showNative = hasNative && nativeView

    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    Column(
        modifier
            .fillMaxSize()
            .background(cs.surfaceContainerLow)
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
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val headerWidth = maxWidth
            val showProject = headerWidth >= 560.dp
            val showStatusText = headerWidth >= 460.dp
            val toggleIconOnly = headerWidth < 420.dp
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
                headerLinks(proxies, forceLinksMenu, onForceLinksMenuConsumed)
                if (hasNative) {
                    Spacer(Modifier.width(Space.xs))
                    AgentViewToggle(
                        nativeView = nativeView,
                        onSetNative = { nativeView = it },
                        modifier = Modifier.testTag("toggle_native"),
                        iconOnly = toggleIconOnly,
                    )
                    Spacer(Modifier.width(Space.xs))
                }
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
                externalDictate = externalDictate,
                onExternalDictateConsumed = onExternalDictateConsumed,
                pasteImageRequestNonce = pasteImageRequestNonce,
                onPasteImageRequestConsumed = onPasteImageRequestConsumed,
                models = modelsData,
                reasoning = reasoningData,
                sessionModel = session.model,
                sessionReasoning = session.reasoningLevel,
                sessionAgent = session.agent,
                onPickModel = { model ->
                    scope.launch {
                        if (actions.pickModel(model)) {
                            modelsData = modelsData?.copy(current = model.ifBlank { null })
                            reasoningData = actions.loadReasoning()
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
                // Re-fetch when a picker opens so a catalog that changed since open is current.
                onPickerOpened = { scope.launch { refreshCatalogs() } },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val transcript: @Composable (bottomPadding: androidx.compose.ui.unit.Dp) -> Unit = { bottomPad ->
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
                items(timelineItems, key = { timelineItemKey(it) }) { item ->
                    TimelineItemRow(
                        item = item,
                        loadBytes = { id -> actions.loadBytes(id) },
                        onOpenFile = onOpenFile,
                        highDetail = highDetail,
                        onOpenWalkthrough = { onOpenWalkthrough(null) },
                    )
                }
                // Background-task chips: only RUNNING tasks get a chip, so a chip clears the moment
                // its task finishes and they never accumulate. The outcome lives in the stream.
                val visibleBgTasks = state.bgTasks.filter { it.status == "running" }
                if (visibleBgTasks.isNotEmpty()) {
                    item(key = "__bgtasks__") { BgTaskChipsRow(visibleBgTasks) }
                }
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
                    } else if (agent?.waiting == true) {
                        item(key = "__waiting__") { WaitingIndicator(agent.bgOpen) }
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

        val emptySession = timelineItems.isEmpty() && !working

        // Body: transcript + composer, or the agent's raw PTY over the top of them. The two are a
        // keep-alive PAIR, not an if/else: Chat hides through `Modifier.keepAlivePanel` (draft and
        // scroll survive a flip) while Native is heavyweight and only a 0×0 layout can hide it.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            run {
                // Docked composer under the transcript on every host; a tap on the transcript drops focus.
                Column(Modifier.keepAlivePanel(visible = !showNative).testTag("chat_body").pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (emptySession) starters() else transcript(0.dp)
                        // Fade, not a rule: a short scrim of the panel's own background so a
                        // message scrolling up dissolves into the header. Non-interactive.
                        EdgeFade(cs.surfaceContainerLow, Modifier.align(Alignment.TopCenter))
                    }
                    WalkthroughUnreadChip(state, onOpenWalkthrough, Modifier.align(Alignment.CenterHorizontally))
                    // A pointer host can still raise a soft keyboard (a tablet with a mouse, DeX
                    // with the phone as touchpad), so the composer clears it here too; the inset
                    // is zero on a desktop, where this is the layout it always was.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            Modifier
                                .widthIn(max = CONTENT_MAX_WIDTH)
                                .padding(start = Space.lg, end = Space.lg, bottom = Space.sm),
                        ) {
                            composer()
                            ComposerFooter(
                                session = session,
                                modifier = Modifier.padding(top = 3.dp),
                                onFetch = actions.composer.gitFetch,
                                onPull = actions.composer.gitPull,
                                onPush = actions.composer.gitPush,
                                onPublish = actions.composer.gitPublish,
                            )
                        }
                    }
                }
}
            if (hasNative && nativeOpened) {
                KeepAlivePanel(visible = showNative, modifier = Modifier.testTag("pane_native")) {
                    // Agent PTY exited → drop the kept-alive panel so a later re-open builds a
                    // fresh client, and fall back to the transcript.
                    nativeContent?.invoke { nativeView = false; nativeOpened = false }
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
            baseLabel = if (agent.detail == "running") "working" else "thinking",
            detail = agent.detail,
            tool = agent.tool,
            toolCount = toolCount,
            durationLabel = durationLabel,
        )
    } else {
        val base = (if (agent.detail == "running") "working" else "thinking") +
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

/**
 * Background-task chips: one mono chip per RUNNING bg shell / subagent / workflow, with its own
 * live elapsed. Chips clear the moment their task finishes (the caller passes running-only).
 */
@Composable
private fun BgTaskChipsRow(tasks: List<ServerFrame.BgTask>) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = nowMs()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = Space.xs, bottom = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tasks.forEach { t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = t.label + " · " + formatDuration(((now - t.startedAt).coerceAtLeast(0)) / 1000),
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
