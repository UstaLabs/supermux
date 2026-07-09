// The desktop chat panel: header (session name + a thin live status line / dead banner), the keyed
// timeline, and the composer. Structure mirrors Android's ChatPanel (header / status / keyed list /
// autoscroll) but the composer is the lean desktop one (see DesktopComposer.kt) — no upload/
// dictation surface in M1. Drafts are hoisted (per-session) by WorkspaceRoot so switching sessions
// keeps each draft; broker-side draft sync is M4.
package dev.supermux.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.FilePathRef

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
 * @param draft hoisted per-session draft text (kept in WorkspaceRoot's draft map so a session
 *   switch preserves it). [onDraftChange] writes back into that map; a send clears it via
 *   `onDraftChange("")`.
 *
 * NOTE: the task's sketch signature was `(app, session, modifier)`; [draft]/[onDraftChange] are
 * added because the draft state is deliberately hoisted to WorkspaceRoot (in-memory, M1) rather
 * than owned here — a `remember(session.id)` would drop the draft on switch.
 */
@Composable
fun ChatPanel(
    app: DesktopAppState,
    session: SessionInfo,
    draft: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    // When embedded in the workspace SessionDetail (M1 Task 9), that composable owns the identity
    // header (name + status rail + pane toggles), so the chat pane suppresses its own name header
    // to avoid a duplicate title bar. The dead banner is always shown regardless. Defaults to true
    // for standalone use.
    showHeader: Boolean = true,
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

    val timelineItems = remember(messagesMap, activityMap, session.id) {
        mergeTimeline(messagesMap[session.id].orEmpty(), activityMap[session.id].orEmpty())
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
        working -> if (agent.detail == "running") "running ${agent.tool ?: "tool"}…" else "thinking…"
        sending -> "sending…"
        agent?.waiting == true -> "waiting (${agent.bgOpen} background)"
        else -> null
    }
    val statusColor = if (agent?.waiting == true && !working && !sending) sem.warning else cs.primary

    Column(modifier.fillMaxSize().background(cs.surfaceContainerLow)) {
        // Header (suppressed when embedded in the workspace SessionDetail, which owns the identity bar).
        if (showHeader) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surface)
                    .padding(horizontal = Space.lg, vertical = Space.md),
            ) {
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
                        loadBytes = { null }, // M4: attachment bytes fetch (chip-only for now)
                        onOpenFile = { _: FilePathRef -> /* TODO(M3): open file in the workspace editor */ },
                    )
                }
            }
        }

        // Composer — reading-width capped + centered to line up with the timeline.
        Box(Modifier.fillMaxWidth().background(cs.surface), contentAlignment = Alignment.TopCenter) {
            DesktopComposer(
                draft = draft,
                onDraftChange = onDraftChange,
                sending = sending,
                agentWorking = working,
                onSend = { text ->
                    app.sendMessage(session.id, text)
                    onDraftChange("")
                },
                onInterrupt = { app.interrupt(session.id) },
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .padding(horizontal = Space.lg, vertical = Space.md),
            )
        }
    }
}
