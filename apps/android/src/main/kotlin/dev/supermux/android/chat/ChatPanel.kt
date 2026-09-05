package dev.supermux.android.chat

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import dev.supermux.ui.TestIds
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.supermux.android.R
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.mergeTimeline
import dev.supermux.net.ChunkSource
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.Composer
import dev.supermux.ui.chat.ComposerActions
import dev.supermux.ui.chat.ComposerFooter
import dev.supermux.ui.chat.TimelineItemRow
import dev.supermux.ui.chat.timelineReadingWidth
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.countToolsSince
import dev.supermux.ui.effectiveChatDetail
import dev.supermux.ui.formatLowWorkingStatus
import dev.supermux.ui.turnBoundaryMs
import dev.supermux.util.formatDuration
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.supermux.ui.prefs.LocalUiPrefs


/** Stable list key for timeline diffing so the optimistic→real id swap (§9) doesn't flicker. */
private fun timelineItemKey(item: TimelineItem): String = when (item) {
    is TimelineItem.Msg -> "m:${item.entry.id}"
    is TimelineItem.Tool -> "t:${item.event.callId ?: "${item.event.kind}:${item.event.seq}:${item.event.ts}"}"
}

/**
 * The reusable chat body — transcript timeline + composer — extracted verbatim from [ChatScreen]
 * so both the phone screen and the tablet multi-pane workspace can render it. Behavior is
 * identical to the inline version; the caller supplies the panel modifier (e.g.
 * `Modifier.keepAlivePanel(...)`), which already applies fillMaxSize/alpha.
 *
 * Composer-owned state (staged attachments and their uploads, dictation, the model/effort pickers,
 * the slash menu) lives in the shared [dev.supermux.ui.chat.Composer] since cluster D3; this screen
 * keeps the transcript, the draft it measures the composer around, and the control-command routing.
 * Session-control slash actions that open ChatScreen-level dialogs (rename / mute / kill) are
 * surfaced through the [onRequestRename], [onRequestMute], and [onRequestKill] callbacks; the
 * model/effort/stop actions are handled here (they touch the composer's own picker or
 * [onInterrupt]).
 */
@Composable
fun ChatPanel(
    session: SessionInfo,
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    agent: AgentStatus?,
    bgTasks: List<ServerFrame.BgTask> = emptyList(),
    sending: Boolean,
    activePanel: SessionPanel,
    onSendWith: (text: String, attachments: List<String>) -> Unit,
    onInterrupt: () -> Unit,
    commands: List<SlashCommand>,
    commandsResolved: Boolean,
    onUpload: suspend (source: ChunkSource, name: String, mime: String, kind: String?, onProgress: (Long, Long) -> Unit) -> String?,
    loadBytes: suspend (String) -> ByteArray?,
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String?,
    transcribeDraft: suspend (draft: String) -> String?,
    loadGlossary: suspend () -> List<String>,
    vmModels: suspend (String) -> ModelsResponse?,
    vmReasoning: suspend (String) -> ReasoningResponse?,
    onPickModel: (String) -> Unit,
    onPickEffort: (String) -> Unit,
    loadDraft: suspend (String) -> String,
    saveDraft: (String, String) -> Unit,
    consumePendingFirst: (String) -> dev.supermux.state.HostStore.PendingFirstMessage?,
    onOpenFile: (FilePathRef) -> Unit,
    onRequestRename: () -> Unit,
    onRequestMute: () -> Unit,
    onRequestKill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = rememberHaptics()


    // ── composer state ────────────────────────────────────────────────────────
    // Everything the composer owns — staged attachments and their uploads, the clipboard/camera
    // entries, dictation, the model/effort pickers, the slash menu — lives in the shared
    // `dev.supermux.ui.chat.Composer` now (cluster D3). This screen keeps only the draft (so the
    // transcript can measure the composer's height around it) and the control-command routing.
    var draft by remember { mutableStateOf("") }
    val composerActions = remember(session.id) {
        ComposerActions(
            loadDraft = { loadDraft(it) },
            saveDraft = { id, t -> saveDraft(id, t) },
            consumePendingFirst = { id -> consumePendingFirst(id)?.let { it.text to it.attachments } },
            transcribeDraft = transcribeDraft,
            loadGlossary = loadGlossary,
        )
    }

    // Model + effort catalogs. LAZY on a phone (a request per opened chat is real battery and real
    // latency on a handset — the pills label themselves from live session state, so the catalog is
    // only needed once a picker actually opens) and EAGER under a pointer, which is desktop's
    // behaviour and where the reasoning fetch also decides whether the effort pill shows at all.
    val pointer = LocalPointerAvailable.current
    var modelsData by remember(session.id) { mutableStateOf<ModelsResponse?>(null) }
    var reasoningData by remember(session.id) { mutableStateOf<ReasoningResponse?>(null) }
    // Bumped by the `/model` control command so the composer opens its own picker.
    var openModelPicker by remember(session.id) { mutableLongStateOf(0L) }
    suspend fun refreshCatalogs() {
        modelsData = withContext(Dispatchers.IO) { vmModels(session.id) }
        reasoningData = withContext(Dispatchers.IO) { vmReasoning(session.id) }
    }
    LaunchedEffect(session.id, pointer) {
        // The effort pill's visibility comes from the reasoning payload, so a phone still fetches
        // THAT on open — it is the model catalog (the big one) that waits for a tap.
        reasoningData = withContext(Dispatchers.IO) { vmReasoning(session.id) }
        if (pointer) modelsData = withContext(Dispatchers.IO) { vmModels(session.id) }
    }


    // ── onControl: composer-scoped control commands are handled here; session-control actions
    //    (rename / mute / kill) bubble up to ChatScreen via callbacks so its dialog state stays put ──
    val onControl: (SlashCommand) -> Unit = { cmd ->
        when (cmd.action?.kind) {
            "rename" -> onRequestRename()
            "mute" -> onRequestMute()
            "kill" -> onRequestKill()
            "model" -> {
                // The picker lives in the shared composer; refresh the catalog, then ask it to open.
                scope.launch {
                    refreshCatalogs()
                    openModelPicker++
                }
            }
            "stop" -> onInterrupt()
            "spawn" -> { /* TODO: spawn from control command (needs nav; iOS also skips) */ }
            else -> {}
        }
    }

    // Float the composer over the transcript (iOS ChatPane parity): the transcript fills the
    // Box and the composer overlays the bottom; the transcript pads its content by the measured
    // composer height so the last message still clears it. Tapping the transcript drops focus.
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var composerHeightPx by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .testTag(TestIds.CHAT_VIEW)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
    ) {
        // ----------------------------------------------------------------
        // 2. Timeline
        // ----------------------------------------------------------------
        // Chat detail (low/medium): hide tool cards in low; activity still arrives via [activity].
        val chatDetail by LocalUiPrefs.current.chatDetailLevel.collectAsState(ChatDetailLevel.MEDIUM)
        val detailMode = effectiveChatDetail(chatDetail)
        val hideTools = detailMode == ChatDetailLevel.LOW
        val highDetail = detailMode == ChatDetailLevel.HIGH
        val timelineItems = remember(messages, activity, hideTools) {
            mergeTimeline(messages, activity, hideTools = hideTools)
        }
        val listState = rememberLazyListState()
        var prevTimelineSize by remember { mutableIntStateOf(0) }

        // Working ⇔ the broker says the agent is busy (iOS workingIndicator gate). Drives both the
        // bottom WorkingIndicator row and the auto-scroll target (so the spinner stays in view).
        val working = agent?.working == true

        // Auto-scroll on new content AND when the working row appears/disappears.
        LaunchedEffect(timelineItems.size, working, activePanel) {
            if (activePanel != SessionPanel.Chat) return@LaunchedEffect
            val target = timelineItems.size - 1 + (if (working) 1 else 0)
            if (target >= 0 && (timelineItems.size > prevTimelineSize || working)) {
                // First paint for this session: jump to the bottom instantly — opening a chat should
                // START at the bottom, not animate a fast scroll down. Only animate for content that
                // arrives while you're already watching.
                // Reach the TRUE bottom (past the composer's contentPadding): position the last
                // item, then scrollBy to the very end. Plain scrollToItem stops short of the padding.
                if (prevTimelineSize == 0) listState.scrollToItem(target) else listState.animateScrollToItem(target)
                listState.scrollBy(100_000f)
            }
            prevTimelineSize = timelineItems.size
        }

        // Follow the floating composer SMOOTHLY as its height changes — first layout, expand/collapse,
        // and the keyboard's ime inset animating in/out — by scrolling the transcript by the SAME
        // delta, so it tracks the composer at exactly its speed (= the keyboard's) instead of jumping.
        // Uses snapshotFlow.collect (NOT a per-change LaunchedEffect) so awaiting a frame for the
        // contentPadding relayout doesn't get cancelled by the next change; conflation is handled by
        // the accumulated delta (h - lastProcessed). Only while near the bottom, so reading history
        // isn't disturbed.
        LaunchedEffect(activePanel, session.id) {
            if (activePanel != SessionPanel.Chat) return@LaunchedEffect
            var prev = 0
            snapshotFlow { composerHeightPx }.collect { h ->
                val delta = h - prev
                prev = h
                // Only follow GROWTH (focus / keyboard-up / expand). On shrink (blur / keyboard-down)
                // the LazyColumn auto-clamps the shrinking contentPadding, keeping the last item at
                // the bottom — an extra downward scroll here would over-shoot into older messages.
                if (delta > 0) {
                    withFrameNanos {} // let this height's contentPadding relayout apply first
                    val info = listState.layoutInfo
                    val atBottom = (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
                    if (atBottom) listState.scrollBy(delta.toFloat())
                }
            }
        }

        if (timelineItems.isEmpty() && !working) {
            // ── Empty session: starter prompts (iOS ChatPane empty state) ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Spacer(Modifier.height(36.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_sparkle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    "Start the conversation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                listOf("What's the current state?", "Run the tests", "Summarize recent changes")
                    .forEachIndexed { i, prompt ->
                        Surface(
                            onClick = {
                                haptic.perform(HapticKind.Confirm)
                                onSendWith(prompt, emptyList())
                            },
                            shape = RoundedCornerShape(Radii.md),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_starter_$i"),
                        ) {
                            Text(
                                prompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.md),
                            )
                        }
                    }
            }
        } else {
            // Full-bleed on a phone, reading-width capped AND centred on a tablet — desktop's rule.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .timelineReadingWidth()
                    .fillMaxHeight()
                    .padding(horizontal = Space.md, vertical = Space.md),
                contentPadding = PaddingValues(bottom = with(density) { composerHeightPx.toDp() } + Space.md),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(timelineItems, key = { timelineItemKey(it) }) { item ->
                    TimelineItemRow(item, loadBytes, onOpenFile, highDetail = highDetail)
                }
                // Background-task chips (bg shells / subagents / workflows): only RUNNING
                // tasks get a chip, so a chip clears the moment its task finishes and they
                // never accumulate. The outcome lives in the chat stream.
                val visibleBgTasks = bgTasks.filter { it.status == "running" }
                if (visibleBgTasks.isNotEmpty()) {
                    item(key = "__bgtasks__") {
                        BgTaskChipsRow(visibleBgTasks)
                    }
                }
                // Live working indicator pinned to the bottom (iOS renders it below the last block).
                if (working && agent != null) {
                    item(key = "__working__") {
                        WorkingIndicator(
                            agent = agent,
                            onStop = onInterrupt,
                            detailLow = hideTools,
                            toolCount = if (hideTools) {
                                val since = turnBoundaryMs(
                                    messages = messages.map { it.direction to it.ts },
                                    isUserDirection = { it == "inbound" },
                                    tsToEpochMs = { ts ->
                                        ts.toLongOrNull()?.let { n ->
                                            if (n < 1_000_000_000_000L) n * 1000L else n
                                        } ?: runCatching {
                                            java.time.Instant.parse(ts).toEpochMilli()
                                        }.getOrDefault(0L)
                                    },
                                    workingSinceMs = agent.workingSince,
                                )
                                val toolTs = activity.filter { it.kind == "tool" }.map { e ->
                                    e.ts.toLongOrNull()?.let { n ->
                                        if (n < 1_000_000_000_000L) n * 1000L else n
                                    } ?: runCatching {
                                        java.time.Instant.parse(e.ts).toEpochMilli()
                                    }.getOrDefault(0L)
                                }
                                countToolsSince(toolTs, since)
                            } else 0,
                        )
                    }
                } else if (sending) {
                    item(key = "__sending__") {
                        SendingIndicator(onStop = onInterrupt)
                    }
                } else if (agent?.waiting == true) {
                    item(key = "__waiting__") {
                        WaitingIndicator(agent.bgOpen)
                    }
                }
            }
            }
        }

        // ----------------------------------------------------------------
        // 3. Composer
        // ----------------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Measure the FULL footprint (card + nav/ime inset) — the composer Column occupies
                // card + inset anchored at the bottom, so the transcript's bottom contentPadding
                // must clear all of it. onSizeChanged sits OUTSIDE windowInsetsPadding to include
                // the inset.
                .onSizeChanged { composerHeightPx = it.height }
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = sending,
                // Android's send button ALWAYS sends (iOS parity) — the Stop/interrupt affordance
                // lives in the transcript's WorkingIndicator, not in the composer's trailing slot.
                agentWorking = false,
                onSend = { text, attachmentIds ->
                    onSendWith(text, attachmentIds)
                    draft = ""
                },
                onInterrupt = onInterrupt,
                sessionKey = session.id,
                actions = composerActions,
                onUpload = onUpload,
                onTranscribeAudio = transcribeAudio,
                commands = commands,
                commandsResolved = commandsResolved,
                onControl = onControl,
                placeholder = "Message ${session.name}…",
                models = modelsData,
                reasoning = reasoningData,
                // Live session state first (kept fresh by session_state frames + the optimistic
                // switch update); the composer falls back to the fetched catalog's current.
                sessionModel = session.model,
                sessionAgent = session.agent,
                sessionReasoning = session.reasoningLevel,
                onPickModel = onPickModel,
                onPickReasoning = onPickEffort,
                // Lazy catalogs on a phone: the pills fetch on first tap.
                onPickerOpened = { if (!pointer) scope.launch { refreshCatalogs() } },
                // Every control command this screen can actually perform; anything else is filtered
                // out of the slash menu rather than clearing the token and doing nothing.
                handledControlKinds = setOf("rename", "mute", "kill", "model", "stop"),
                openModelPickerNonce = openModelPicker,
            )
            // Detail + git context. Compact (a phone) keeps the footer-less composer it always
            // had; a tablet/desktop-class window gets desktop's strip.
            if (LocalWindowWidthClass.current != WindowWidthClass.Compact) {
                ComposerFooter(session = session, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/**
 * Live "Working… · Ns" indicator pinned to the bottom of the transcript (iOS workingIndicator
 * parity): a small spinner + phase label + elapsed duration, and a red Stop capsule that
 * interrupts the running agent. Ticks every 1s, recomputing elapsed from `agent.since` (epoch-ms).
 */
@Composable
private fun WorkingIndicator(
    agent: AgentStatus,
    onStop: () -> Unit,
    detailLow: Boolean = false,
    toolCount: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = agent.workingSince?.let { ((now - it).coerceAtLeast(0)) / 1000 }
    val durationLabel = elapsed?.let { formatDuration(it) } ?: ""
    // Medium: platform baseline (working/thinking + tool). Low: shared enrichment with tool count.
    val label = if (detailLow) {
        val base = if (agent.detail == "running") "working" else "thinking"
        formatLowWorkingStatus(
            baseLabel = base,
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
    // Minimal live status: mono label + elapsed, compact stop — no gutter pulse dots.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            color = cs.primary,
        )
        Spacer(Modifier.width(Space.sm))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.sm))
                .clickable { haptic.perform(HapticKind.Tick); onStop() }
                .testTag("working_stop")
                .padding(horizontal = Space.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_square),
                contentDescription = "Stop",
                tint = cs.error,
                modifier = Modifier.size(10.dp),
            )
            Text("stop", fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.error)
        }
    }
}

/**
 * Background-task chips (direction B of the waiting-state design): one mono chip per
 * RUNNING bg shell / subagent / workflow, with its own live elapsed. Chips clear the
 * moment their task finishes (the caller passes running-only), so they never accumulate;
 * the outcome lives in the chat stream. Motion stays chat-only per the design language.
 */
@Composable
private fun BgTaskChipsRow(tasks: List<ServerFrame.BgTask>) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
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

/**
 * "waiting · N background tasks" status row — the turn is over but the harness will
 * wake the agent when its background tasks finish. Amber = attention-not-error; no
 * Stop capsule because the agent is idle (there is nothing to interrupt).
 */
@Composable
private fun WaitingIndicator(bgOpen: Int) {
    val sem = LocalSemantics.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "waiting · " + if (bgOpen == 1) "1 background task" else "$bgOpen background tasks",
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            color = sem.warning,
        )
    }
}

/**
 * Client-local "Sending…" indicator shown between the user tapping Send and the first
 * `agent_state` frame arriving from the broker. No timer (no elapsed), static label.
 * Same Stop capsule as WorkingIndicator so the user can cancel immediately after sending.
 */
@Composable
private fun SendingIndicator(onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "sending",
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            color = cs.primary,
        )
        Spacer(Modifier.width(Space.sm))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.sm))
                .clickable { haptic.perform(HapticKind.Tick); onStop() }
                .testTag("sending_stop")
                .padding(horizontal = Space.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_square),
                contentDescription = "Stop",
                tint = cs.error,
                modifier = Modifier.size(10.dp),
            )
            Text("stop", fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.error)
        }
    }
}

/**
 * Create a FileProvider URI for a fresh camera capture in cacheDir/attachments (the path already
 * declared in file_paths.xml + reused by openAttachment). The system camera app writes the JPEG
 * here, then [stageFromUri] reads it back and uploads it.
 */
internal fun createImageUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Create a FileProvider URI for a fresh camera video capture in cacheDir/attachments (the same
 * path createImageUri + openAttachment already use, so no file_paths.xml change is needed). The
 * system camera app writes the MP4 here; stageFromUri then reads it back — contentResolver
 * .getType() maps the .mp4 extension to video/mp4 — and uploads it with kind=null so the broker
 * infers "video".
 */
internal fun createVideoUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.mp4")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
