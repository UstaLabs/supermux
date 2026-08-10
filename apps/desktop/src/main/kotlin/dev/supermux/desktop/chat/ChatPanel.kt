// The desktop chat panel: header (session name + a thin live status line / dead banner), the keyed
// timeline, and the composer. Structure mirrors Android's ChatPanel (header / status / keyed list /
// autoscroll) but the composer is the lean desktop one (see DesktopComposer.kt) — no upload/
// dictation surface in M1. Drafts are hoisted (per-session) by AppShell so switching sessions
// keeps each draft; broker-side draft sync is M4.
package dev.supermux.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.mergeTimeline
import dev.supermux.desktop.shell.AgentViewToggle
import dev.supermux.desktop.shell.OverflowMenu
import dev.supermux.desktop.shell.SessionLinksMenu
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.ui.KeepAlivePanel
import dev.supermux.desktop.ui.keepAlivePanel
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.countToolsSince
import dev.supermux.ui.effectiveChatDetail
import dev.supermux.ui.formatLowWorkingStatus
import dev.supermux.ui.turnBoundaryMs
import kotlinx.coroutines.launch

/** Reading-width cap for the timeline + composer so long lines wrap at a comfortable measure on a
 *  1440-wide window instead of stretching edge-to-edge (obligation 2). Content is centered under
 *  this cap; below it, everything is fluid. */
private val CONTENT_MAX_WIDTH = 860.dp

/**
 * Stable list key for the timeline so the optimistic→real id swap (local-echo → broker MessageAppend)
 * doesn't flicker or lose scroll position. Ported from Android's `timelineItemKey`
 * (apps/android/.../chat/ChatPanel.kt): tool rows key off callId (falling back to kind:seq:ts when a
 * tool has no callId), message rows off the entry id.
 */
private fun timelineItemKey(item: TimelineItem): String = when (item) {
    is TimelineItem.Msg -> "m:${item.entry.id}"
    is TimelineItem.Tool -> "t:${item.event.callId ?: "${item.event.kind}:${item.event.seq}:${item.event.ts}"}"
}

/**
 * Full chat surface for [session]. Reads the live StateFlows off [app], merges the timeline, and
 * wires the composer's send/interrupt to the real send path.
 *
 * @param draft hoisted per-session draft text (kept in AppShell's draft map so a session
 *   switch preserves it). [onDraftChange] writes back into that map; a send clears it via
 *   `onDraftChange("")`.
 *
 * NOTE: the task's sketch signature was `(app, session, modifier)`; [draft]/[onDraftChange] are
 * added because the draft state is deliberately hoisted to AppShell (in-memory, M1) rather
 * than owned here — a `remember(session.id)` would drop the draft on switch.
 */
@Composable
fun ChatPanel(
    app: DesktopAppState,
    session: SessionInfo,
    draft: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    // When embedded in the shell SessionDetail (M1 Task 9), that composable owns the identity
    // header (name + status rail + pane toggles), so the chat pane suppresses its own name header
    // to avoid a duplicate title bar. The dead banner is always shown regardless. Defaults to true
    // for standalone use.
    showHeader: Boolean = true,
    // Threaded since M1-T7 but wired to a no-op until M3-T5: SessionDetail now passes its real
    // chat-tap → editor-at-line handler (Android ChatScreen:221 parity). Defaults to {} so
    // standalone/preview uses of ChatPanel keep compiling.
    onOpenFile: (FilePathRef) -> Unit = {},
    // Off-by-default headless hook (SM_CHAT_ATTACH, Main.kt) delivery: a one-shot "stage this file
    // then send" request routed straight through to [DesktopComposer]'s `externalAttach` — see its
    // KDoc for the funnel. Null in normal operation.
    externalAttach: ComposerExternalAttach? = null,
    onExternalAttachConsumed: () -> Unit = {},
    // Off-by-default headless hook (SM_DICTATE, Main.kt) delivery: a one-shot "transcribe this WAV
    // file then append" request routed straight through to [DesktopComposer]'s `externalDictate` —
    // see its KDoc for the funnel. Null in normal operation.
    externalDictate: ComposerExternalDictate? = null,
    onExternalDictateConsumed: () -> Unit = {},
    // Edit ▸ Paste image (MenuBar) → DesktopComposer.pasteImageRequestNonce.
    pasteImageRequestNonce: Long = 0L,
    onPasteImageRequestConsumed: () -> Unit = {},
    /** After "Continue in new conversation" — parent selects the new session (ViewHost path). */
    onSelectSession: (String) -> Unit = {},
    // ── Header affordances that moved off the old session header (SessionDetail, deleted) ──
    // Both belong to ONE session, and a chat view IS one session — unlike the git badge, which
    // belongs to the work tree and moved to the workspace header instead.
    /**
     * This session's exposed proxies, for the header's globe (links) menu. Loaded on open and on
     * session change, and only when [showHeader] — no proxy_* frame is reduced, so this is a plain
     * load-on-open. getOrNull-degraded upstream: a broker hiccup just leaves the menu hidden.
     */
    loadProxies: suspend () -> List<ProxyDto> = { app.proxies() },
    // Off-by-default headless hook (SM_LINKS_MENU, Main.kt): force-expands the links dropdown.
    // Never opens a URL. Applied once, then [onForceLinksMenuConsumed] clears the source.
    forceLinksMenu: Boolean = false,
    onForceLinksMenuConsumed: () -> Unit = {},
    /**
     * The agent's raw ("Native") PTY. When non-null AND the session's agent is claude, the header
     * shows the Chat⇄Native pill and flipping it swaps the transcript+composer for this content —
     * the header itself stays put, so the way back is always on screen.
     *
     * Null (the default) hides the pill entirely: a chat with no native surface has nothing to
     * flip to. The lambda receives an `onExit` to call when the PTY dies, which drops the panel
     * and returns to the transcript.
     */
    nativeContent: (@Composable (onExit: () -> Unit) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current

    val messagesMap by app.messages.collectAsState()
    val activityMap by app.activity.collectAsState()
    val agentMap by app.agentState.collectAsState()
    val pending by app.pendingSend.collectAsState()

    val agent = agentMap[session.id]
    val working = agent?.working == true
    val sending = session.id in pending
    val dead = agent?.state == "dead"

    // Lazily fetch the transcript on open (archive-resumed sessions have no snapshot history).
    LaunchedEffect(session.id) { app.ensureMessagesLoaded(session.id) }

    // ── In-composer model + reasoning selection (M-uxfix) ──────────────────────────────────────
    // ChatPanel owns the catalog state so it can fetch-on-open, optimistically update `current`
    // after a switch, and refetch reasoning when the model changes (effort visibility is
    // model-dependent). remember(session.id) resets on session switch so a stale catalog never
    // flashes; the LaunchedEffects then refill it.
    val scope = rememberCoroutineScope()
    var modelsData by remember(session.id) { mutableStateOf<ModelsResponse?>(null) }
    var reasoningData by remember(session.id) { mutableStateOf<ReasoningResponse?>(null) }
    LaunchedEffect(session.id) { modelsData = app.sessionModels(session.id) }
    LaunchedEffect(session.id) { reasoningData = app.sessionReasoning(session.id) }

    val chatDetail by ChatDetailPrefs.level.collectAsState()
    val detailMode = effectiveChatDetail(chatDetail)
    val hideTools = detailMode == ChatDetailLevel.LOW
    val highDetail = detailMode == ChatDetailLevel.HIGH
    val messages = messagesMap[session.id].orEmpty()
    val activity = activityMap[session.id].orEmpty()
    val timelineItems = remember(messagesMap, activityMap, session.id, hideTools) {
        mergeTimeline(messages, activity, hideTools = hideTools)
    }

    // ── Autoscroll ────────────────────────────────────────────────────────────
    // First content for a session: INSTANT jump to the bottom (house rule — a chat opens AT the
    // bottom, never animates a fast scroll down). After that, follow new items only while the user
    // is at/near the bottom (tracked via layoutInfo → [autoFollow]); reading history stays put.
    val listState = rememberLazyListState()
    var prevSize by remember(session.id) { mutableIntStateOf(-1) }
    var autoFollow by remember(session.id) { mutableStateOf(true) }

    LaunchedEffect(session.id, listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            // Near-bottom = last visible item is within 2 of the end (or nothing to scroll yet).
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }.collect { nearBottom -> autoFollow = nearBottom }
    }

    LaunchedEffect(session.id, timelineItems.size, working) {
        val target = timelineItems.size - 1
        if (target < 0) return@LaunchedEffect // nothing yet — keep prevSize == -1 so the first
        // real content still jumps instantly rather than animating.
        when {
            prevSize < 0 -> listState.scrollToItem(target)
            (timelineItems.size > prevSize) && autoFollow -> listState.animateScrollToItem(target)
        }
        prevSize = timelineItems.size
    }

    // ── Header status line ──────────────────────────────────────────────────────
    // Priority: dead (banner) > working (thinking/running) > sending > waiting. `pendingSend`
    // covers the gap between tapping Send and the first agent_state frame arriving.
    val statusText: String? = when {
        dead -> null // rendered as a banner below, not this thin line
        // `working` (= agent?.working == true) implies agent != null, so it smart-casts here.
        working -> {
            if (hideTools) {
                val base = if (agent.detail == "running") "running" else "thinking"
                val since = turnBoundaryMs(
                    messages = messages.map { it.direction to it.ts },
                    isUserDirection = { it == "inbound" },
                    tsToEpochMs = { ts ->
                        ts.toLongOrNull()?.let { n -> if (n < 1_000_000_000_000L) n * 1000L else n }
                            ?: runCatching { java.time.Instant.parse(ts).toEpochMilli() }.getOrDefault(0L)
                    },
                    workingSinceMs = agent.workingSince,
                )
                val toolTs = activity.filter { it.kind == "tool" }.map { e ->
                    e.ts.toLongOrNull()?.let { n -> if (n < 1_000_000_000_000L) n * 1000L else n }
                        ?: runCatching { java.time.Instant.parse(e.ts).toEpochMilli() }.getOrDefault(0L)
                }
                val count = countToolsSince(toolTs, since)
                formatLowWorkingStatus(base, agent.detail, agent.tool, count, "")
            } else {
                if (agent.detail == "running") "running ${agent.tool ?: "tool"}…" else "thinking…"
            }
        }
        sending -> "sending…"
        agent?.waiting == true -> "waiting (${agent.bgOpen} background)"
        else -> null
    }
    val statusColor = if (agent?.waiting == true && !working && !sending) sem.warning else cs.primary

    // ── Header affordances (links menu + Chat⇄Native) ───────────────────────────────────
    // Proxies are a plain load-on-open; cleared first so the previous session's list can't
    // transiently render filtered-for-the-new-session while the fresh load is in flight.
    var proxies by remember(session.id) { mutableStateOf<List<ProxyDto>>(emptyList()) }
    LaunchedEffect(session.id, showHeader) {
        if (!showHeader) return@LaunchedEffect
        proxies = emptyList()
        proxies = loadProxies()
    }
    // Chat ⇄ Native. Only claude has a native view, and only a caller that handed us
    // [nativeContent] has one to show.
    val hasNative = nativeContent != null && session.agent == "claude"
    var nativeView by remember(session.id) { mutableStateOf(false) }
    // Once Native has been shown for this session, keep it composed so a flip back to Chat does
    // not drop its PTY; reset per session so a switch starts closed. Latched in an effect, not a
    // bare state write during composition (which Compose may re-execute/discard).
    var nativeOpened by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id, nativeView) { if (nativeView) nativeOpened = true }
    val showNative = hasNative && nativeView

    Column(modifier.fillMaxSize().background(cs.surfaceContainerLow)) {
        // Header (suppressed when embedded in the shell SessionDetail, which owns the identity bar).
        // ViewHost chat views use this header and need the ⋮ menu here.
        if (showHeader) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surface)
                    .padding(start = Space.lg, end = Space.sm, top = Space.md, bottom = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = session.name,
                        style = MaterialTheme.typography.titleLarge, // Geist SemiBold
                        color = cs.onSurface,
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            fontFamily = MonoFontFamily,
                            fontSize = 12.sp,
                            color = statusColor,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                // The proxies exposed by THIS session (hidden when it has none). Moved here from
                // the old session header: a proxy belongs to one session, and this view is one.
                SessionLinksMenu(
                    session = session,
                    proxies = proxies,
                    forceOpen = forceLinksMenu,
                    onForceOpenConsumed = onForceLinksMenuConsumed,
                )
                if (hasNative) {
                    AgentViewToggle(
                        nativeView = nativeView,
                        onSetNative = { nativeView = it },
                        modifier = Modifier.testTag("toggle_native"),
                    )
                    Spacer(Modifier.width(Space.xs))
                }
                OverflowMenu(
                    session = session,
                    onRename = { name -> app.rename(session.id, name) },
                    onToggleMute = { muted -> app.setMute(session.id, muted) },
                    onKill = { app.kill(session.id) },
                    onContinue = { message -> app.continueConversation(session, message) },
                    onContinued = onSelectSession,
                    showManagementRows = false,
                )
            }
        }
        // "Not responding" dead banner (error-tinted strip; mirrors Android's dead banner).
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

        // Body: the transcript + composer, or the agent's raw PTY over the top of them.
        //
        // The two are a keep-alive PAIR, not an if/else: Chat is pure Compose and hides through the
        // lightweight `Modifier.keepAlivePanel` (so its unsaved draft and scroll survive a flip and
        // it never remounts), while Native is a heavyweight SwingPanel that only a 0×0 layout can
        // hide — hence the `KeepAlivePanel` composable. Native is LAZY: not composed at all until
        // first opened, then kept alive across flips so its PTY survives.
        Box(Modifier.fillMaxWidth().weight(1f)) {
        Column(Modifier.keepAlivePanel(visible = !showNative).testTag("chat_body")) {
        // Timeline — keyed LazyColumn (obligation 1), reading-width capped + centered (obligation 2).
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                // widthIn BEFORE fillMaxHeight: cap the reading width first, then fill the height.
                // (fillMaxSize would fix the width to the parent's max and defeat the cap.)
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxHeight()
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(timelineItems, key = { timelineItemKey(it) }) { item ->
                    TimelineItemRow(
                        item = item,
                        loadBytes = { id -> app.fileBytes(id) },
                        onOpenFile = onOpenFile,
                        highDetail = highDetail,
                    )
                }
            }
        }

        // Composer — reading-width capped + centered to line up with the timeline.
        // No separate surface strip: share the panel's surfaceContainerLow so the composer
        // doesn't sit in a contrasting band under the timeline.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            DesktopComposer(
                draft = draft,
                onDraftChange = onDraftChange,
                sending = sending,
                agentWorking = working,
                // Scope the composer's staged-attachment state to this session — ChatPanel stays
                // composed across session switches, so without this a chip staged against session A
                // would leak into B (and its file_id gathered into B's send).
                sessionKey = session.id,
                onSend = { text, fileIds ->
                    app.sendMessage(session.id, text, fileIds)
                    onDraftChange("")
                },
                onInterrupt = { app.interrupt(session.id) },
                // Chat uploads against the LIVE session immediately (unlike the launcher's pre-spawn
                // staging): bind the composer's upload seam to uploadResumable for this session.
                onUpload = { source, name, mime, kind, onProgress ->
                    app.uploadResumable(session.id, source, name, mime, kind, onProgress)
                },
                onTranscribeAudio = { bytes, name -> app.transcribeAudio(session.id, bytes, name)?.text },
                externalAttach = externalAttach,
                onExternalAttachConsumed = onExternalAttachConsumed,
                externalDictate = externalDictate,
                onExternalDictateConsumed = onExternalDictateConsumed,
                pasteImageRequestNonce = pasteImageRequestNonce,
                onPasteImageRequestConsumed = onPasteImageRequestConsumed,
                // Model + reasoning pills live IN the composer (above the input). The current model
                // falls back to session.model until the catalog loads. Picking optimistically updates
                // the shown `current`; a model change also refetches reasoning since effort
                // visibility is model-dependent.
                models = modelsData,
                reasoning = reasoningData,
                sessionModel = session.model,
                sessionAgent = session.agent,
                onPickModel = { model ->
                    scope.launch {
                        if (app.switchModel(session.id, model)) {
                            modelsData = modelsData?.copy(current = model.ifBlank { null })
                            reasoningData = app.sessionReasoning(session.id)
                        }
                    }
                },
                onPickReasoning = { level ->
                    scope.launch {
                        if (app.switchReasoning(session.id, level)) {
                            reasoningData = reasoningData?.copy(current = level)
                        }
                    }
                },
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .padding(horizontal = Space.lg, vertical = Space.md),
            )
        }
        }
        if (hasNative && nativeOpened) {
            KeepAlivePanel(visible = showNative, modifier = Modifier.testTag("pane_native")) {
                // Agent PTY exited (the broker's exit frame) → drop the kept-alive panel so a later
                // re-open builds a fresh client rather than showing the dead one, and fall back to
                // the transcript.
                nativeContent?.invoke { nativeView = false; nativeOpened = false }
            }
        }
        }
    }
}
