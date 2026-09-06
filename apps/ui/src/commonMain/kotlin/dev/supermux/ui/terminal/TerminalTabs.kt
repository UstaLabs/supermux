// Web-parity scratch-terminal tab strip (src/web-app/src/components/TerminalPanel.vue). One strip
// per session; each tab is one tmux-backed terminal. The tab SET is rebuilt from the broker
// (listTerminals → live tmux) on open, so both the shells AND the tabs survive an app restart.
//
// Cluster G3: ONE strip for every client. Desktop's `terminal/TerminalTabs.kt` is the base (the
// whole tab UI: strip, add/close, active switching, bounded keep-alive); Android's pure
// reconciliation (`reconcileTerminalTabs`/`activeTerminalAfterSync`, which its `ScratchTerminalPanel`
// used to poll the broker with) is folded in below and drives the periodic re-sync the desktop
// strip never had. The grid comes from `Platform.terminalView()`, and under Touch the accessory
// key bar (Android's, now shared) is pinned under the panels — outside the grid's own subtree, so
// it survives above the IME and types into the ACTIVE tab.
package dev.supermux.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalSummary
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.isTertiaryButtonPress
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.KeepAlivePanel
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Broker's default scratch-terminal name; the first tab of a fresh session uses it so connecting
 *  with terminal="main" lands on the same tmux terminal the broker would auto-create. */
private const val DEFAULT_TERMINAL_ID = "main"

/** How long a locally-created tab is kept before the broker has to confirm it (Android's rule). */
const val TERMINAL_CREATE_GRACE_MS = 15_000L

/** How often the strip re-syncs with the broker while visible (Android's `ScratchTerminalPanel`). */
private const val TERMINAL_RESYNC_MS = 3_000L

/**
 * Reconcile the broker-owned terminal list with short-lived local UI operations. The broker list
 * is authoritative across devices; pending creates are kept briefly until their websocket creates
 * the tmux terminal, and pending closes stay hidden until the broker confirms removal.
 *
 * Pure (Android's, moved unchanged) — the strip's whole cross-device merge rule.
 */
fun reconcileTerminalTabs(
    remoteIds: List<String>,
    localIds: List<String>,
    pendingCreates: Map<String, Long>,
    pendingCloses: Set<String>,
    nowMs: Long,
): List<String> {
    val remote = remoteIds.filterNot { it in pendingCloses }
    val localPending = localIds.filter { id ->
        id !in remoteIds && id !in pendingCloses &&
            pendingCreates[id]?.let { nowMs - it < TERMINAL_CREATE_GRACE_MS } == true
    }
    return (remote + localPending).distinct()
}

/** Which tab is active after the set changed: keep the current one, else the nearest survivor. */
fun activeTerminalAfterSync(
    ids: List<String>,
    current: String,
    preferredIndex: Int = 0,
): String {
    if (current in ids) return current
    return ids.getOrNull(preferredIndex) ?: ids.getOrNull(preferredIndex - 1) ?: ids.firstOrNull().orEmpty()
}

/** New-tab id: a short random hex suffix, mirroring web's `genId()` fallback (`"t" + rand hex`). The
 *  id is the tmux terminal name the broker creates on first connect. */
private fun genTerminalId(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder("t")
    repeat(8) { sb.append(hex[Random.nextInt(hex.length)]) }
    return sb.toString()
}

/**
 * Per-session terminal tab strip + the active terminal below it, over one [HostStore].
 *
 * The lambda overload below is the real one; this is the call shape every shell has.
 */
@Composable
fun TerminalTabs(
    app: HostStore,
    sessionId: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    surfaceFor: @Composable (tabId: String, connect: () -> TerminalClient) -> TerminalSurface =
        { _, connect -> LocalPlatform.current.terminalView().rememberTerminalSurface(connect) },
) = TerminalTabs(
    sessionId = sessionId,
    connect = { terminalId -> app.connectTerminal(sessionId, terminalId) },
    listTerminals = { app.listTerminals(sessionId) },
    // HostStore's teardown is already fire-and-forget inside the store.
    closeTerminal = { terminalId -> app.closeTerminal(sessionId, terminalId) },
    modifier = modifier,
    active = active,
    surfaceFor = surfaceFor,
)

/**
 * Per-session terminal tab strip + the active terminal below it.
 *
 * TAB POLICY (plan Task 6): only the ACTIVE tab's panel is fully visible. Inactive panels are kept
 * alive (composed, connected) via [KeepAlivePanel] under a BOUNDED policy — at most the active tab
 * PLUS the most-recently-active tab stay composed; every other tab is fully disposed (its
 * [TerminalClient] stops + the host's engine closes on leaving the composition). This caps live
 * websockets at two per session regardless of how many tabs exist, while making the common
 * flip-back-to-the-last-tab instant (no reconnect / no blank grid). Background shells keep running
 * headless in tmux, so a disposed tab reconnects to the SAME shell when re-selected.
 *
 * RE-SYNC (Android's rule, folded in): while [active] the strip re-lists the broker's terminals
 * every [TERMINAL_RESYNC_MS] and merges through [reconcileTerminalTabs], so a terminal opened or
 * closed on ANOTHER device appears/disappears here without leaving the screen. A tab created here
 * survives unconfirmed for [TERMINAL_CREATE_GRACE_MS]; a tab closed here stays hidden until the
 * broker confirms. Which tab is SELECTED stays local to each device on purpose.
 *
 * KEY ISOLATION (T3 review warning): each tab's surface is built inside `key(tabId)`. A surface
 * remembers its [TerminalClient] with no key of its own, so without the per-tab `key` Compose could
 * reuse the previous tab's client for a different tab id.
 *
 * KEY BAR: under [InputMode.Touch] the shared [TerminalKeyBar] is pinned below the panels and
 * drives the ACTIVE surface's [TerminalSurface.keys] — outside the grid's subtree, which is what
 * lets it sit above the soft keyboard (the whole panel+bar block carries the IME/nav inset). A
 * pointer-driven client has the real keys and gets no bar.
 *
 * @param closeTerminal best-effort tmux teardown; the tab is already gone locally regardless of the
 *   outcome (web parity: the shell may already have exited).
 * @param surfaceFor injectable surface slot — defaults to the host's own engine through
 *   `Platform.terminalView()` (cluster G1) rather than naming an engine here. The real surfaces are
 *   a SwingPanel / an AndroidView and cannot be hosted under `runComposeUiTest`, so UI tests inject
 *   a lightweight pure-Compose fake to exercise the strip's add/close/select, key isolation and the
 *   key bar's route into the active pane.
 */
@Composable
fun TerminalTabs(
    sessionId: String,
    connect: (terminalId: String) -> TerminalClient,
    listTerminals: suspend () -> List<TerminalSummary>,
    closeTerminal: suspend (terminalId: String) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    surfaceFor: @Composable (tabId: String, connect: () -> TerminalClient) -> TerminalSurface =
        { _, c -> LocalPlatform.current.terminalView().rememberTerminalSurface(c) },
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val touch = LocalInputMode.current == InputMode.Touch
    val scope = rememberCoroutineScope()
    // Monotonic clock for the create-grace window (Android used SystemClock.elapsedRealtime; a
    // wall clock would let an NTP step expire a pending tab). Relative to this strip's first frame,
    // which is all the grace window compares against.
    val clock = remember(sessionId) { TimeSource.Monotonic.markNow() }
    fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    // State is keyed on sessionId so a session switch (ChatView reuse) resets the strip cleanly.
    val tabs = remember(sessionId) { mutableStateListOf<String>() }
    var activeId by remember(sessionId) { mutableStateOf("") }
    var lastActiveId by remember(sessionId) { mutableStateOf<String?>(null) }
    var hydrated by remember(sessionId) { mutableStateOf(false) }
    // Local operations the broker has not caught up with yet (Android's reconciliation inputs).
    val pendingCreates = remember(sessionId) { mutableStateMapOf<String, Long>() }
    val pendingCloses = remember(sessionId) { mutableStateMapOf<String, Boolean>() }

    fun selectTab(id: String) {
        if (id == activeId) return
        lastActiveId = activeId.takeIf { it.isNotEmpty() }
        activeId = id
    }

    fun addTab(id: String = genTerminalId()) {
        lastActiveId = activeId.takeIf { it.isNotEmpty() }
        pendingCreates[id] = nowMs()
        tabs.add(id)
        activeId = id
    }

    // Web's pickActiveAfterRemoval: if the active tab is still present, keep it; else fall to the
    // tab that shifted into the removed slot, then the one before it, then the first, then none.
    fun pickActiveAfterRemoval(removedIdx: Int) {
        if (activeId.isNotEmpty() && tabs.any { it == activeId }) return
        activeId = activeTerminalAfterSync(tabs.toList(), activeId, removedIdx)
    }

    /** The pty ended server-side: drop the tab locally (Android's `removeLocal`) — no teardown
     *  call, the shell is already gone. */
    fun removeLocalTab(id: String) {
        val removedIdx = tabs.indexOf(id)
        if (removedIdx < 0) return
        tabs.removeAt(removedIdx)
        pendingCreates.remove(id)
        if (id == lastActiveId) lastActiveId = null
        if (id == activeId) pickActiveAfterRemoval(removedIdx)
    }

    fun closeTab(id: String) {
        val removedIdx = tabs.indexOf(id)
        if (removedIdx < 0) return
        tabs.removeAt(removedIdx)
        pendingCreates.remove(id)
        pendingCloses[id] = true
        if (id == lastActiveId) lastActiveId = null
        if (id == activeId) pickActiveAfterRemoval(removedIdx)
        // Best-effort tmux teardown — the tab is already gone locally regardless of the outcome
        // (web parity: the shell may already have exited). A FAILED close drops the hide-mark
        // (Android's rule) so the next re-sync brings the still-live terminal back rather than
        // hiding it forever.
        scope.launch { if (runCatching { closeTerminal(id) }.isFailure) pendingCloses.remove(id) }
    }

    // Rebuild the tab set from the broker (live tmux) on first show for this session, then keep it
    // in sync while visible. Empty / failed list → start with one DEFAULT_TERMINAL_ID tab so the
    // pane is immediately usable (web parity).
    //
    // MERGE, don't clobber: the `+` button is live BEFORE the first fetch resolves (no dead UI
    // while hydrating), so a tab added mid-round-trip is not yet in the broker's list — a plain
    // rebuild would wipe it, disposing its panel and orphaning the freshly created tmux terminal
    // until the next hydration. [reconcileTerminalTabs] retains it (for the create grace window),
    // and the current active tab is kept whenever it survives.
    LaunchedEffect(sessionId, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            val remoteIds = runCatching { listTerminals().sortedBy { it.createdAt }.map { it.id } }.getOrNull()
            if (remoteIds == null) {
                // Offline / broker error: never wipe what is on screen.
                if (!hydrated && tabs.isEmpty()) addTab(DEFAULT_TERMINAL_ID)
                hydrated = true
            } else {
                val now = nowMs()
                remoteIds.forEach { pendingCreates.remove(it) }
                pendingCreates.entries.filter { now - it.value >= TERMINAL_CREATE_GRACE_MS }
                    .map { it.key }.forEach { pendingCreates.remove(it) }
                pendingCloses.keys.filter { it !in remoteIds }.forEach { pendingCloses.remove(it) }

                val merged = reconcileTerminalTabs(
                    remoteIds = remoteIds,
                    localIds = tabs.toList(),
                    pendingCreates = pendingCreates,
                    pendingCloses = pendingCloses.keys,
                    nowMs = now,
                )
                tabs.clear()
                tabs.addAll(merged)
                if (merged.isEmpty()) {
                    // A session with no tmux terminal yet opens on the broker's default name, so
                    // connecting lands on the terminal the broker would auto-create (web parity).
                    if (!hydrated) addTab(DEFAULT_TERMINAL_ID)
                } else {
                    activeId = activeTerminalAfterSync(merged, activeId)
                }
                if (lastActiveId != null && lastActiveId !in tabs) lastActiveId = null
                hydrated = true
            }
            delay(TERMINAL_RESYNC_MS)
        }
    }

    // The bounded live set, built BEFORE the panels are drawn so the key bar (drawn after them,
    // outside every grid) can reach the active surface's sink in the same composition.
    // key(id) wraps the WHOLE body (not just the `if`): a conditional group outside the key would
    // be matched positionally, so a list shift could dispose one tab's surface and rebuild another's.
    val live = LinkedHashMap<String, TerminalSurface>()
    tabs.forEach { id ->
        key(id) {
            if (id == activeId || id == lastActiveId) live[id] = surfaceFor(id) { connect(id) }
        }
    }

    Column(modifier.fillMaxSize().background(Color(c.terminal))) {
        // ── Tab strip ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(cs.surfaceContainerLow)
                .horizontalScroll(rememberScrollState())
                .testTag("terminal_tabs"),
            // Square flush tabs — no strip padding / no inter-tab gap.
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            tabs.forEach { id ->
                key(id) {
                    TerminalTabChip(
                        id = id,
                        selected = id == activeId,
                        onSelect = { selectTab(id) },
                        onClose = { closeTab(id) },
                    )
                }
            }
            // + : new terminal. A touch client gets a 40dp target (the Android bar's rule).
            Box(Modifier.testTag("terminal_add"), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(if (LocalPointerAvailable.current) 24.dp else 40.dp)
                        .clickable { addTab() }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("term-tab-add"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "New terminal",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(cs.outlineVariant))

        // ── Active terminal (+ the kept-alive last-active one, hidden) + the key bar ──
        //
        // The IME/nav inset is applied HERE rather than inside a grid: the bar has to stay above
        // the soft keyboard, and shrinking this block is also what makes the emulator recompute its
        // grid and resize the remote pty to the visible rows (Android's TerminalPanel rule, hoisted
        // one level so the shared bar shares the padding).
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (hydrated && tabs.isEmpty()) {
                    TerminalEmptyState(onAdd = { addTab() })
                }
                tabs.forEach { id ->
                    // Bounded keep-alive: only the active + most-recently-active tabs are in `live`;
                    // the rest are not emitted here → disposed.
                    //
                    // key(id) must wrap the WHOLE iteration body — `if` OUTSIDE key would make the
                    // conditional groups positional siblings, so a list shift (a re-sync prepending
                    // fetched ids, or closing an earlier tab) would dispose + recreate a later tab's
                    // panel instead of relocating it (keyed matching only works among sibling groups).
                    key(id) {
                        val surface = live[id]
                        if (surface != null) {
                            KeepAlivePanel(visible = id == activeId) {
                                surface.Content(
                                    Modifier.fillMaxSize(),
                                    id == activeId && active,
                                    { removeLocalTab(id) },
                                )
                            }
                        }
                    }
                }
            }
            // Accessory keys the soft keyboard lacks. Touch only (a mouse-driven client has the
            // real keys), foreground pane only, and always the ACTIVE tab's sink — a background
            // pane's armed Ctrl can never leak into the one you are typing in.
            val activeSurface = live[activeId]
            if (touch && active && activeSurface != null) {
                TerminalKeyBar(keys = activeSurface.keys, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** One tab chip: mono id label + an always-present (hover-brightened) × close affordance. Middle-
 *  click also closes (a desktop convenience the web strip lacks). */
@Composable
private fun TerminalTabChip(
    id: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pointer = LocalPointerAvailable.current
    val bg = if (selected) cs.surface else Color.Transparent
    val fg = if (selected) cs.onSurface else cs.onSurfaceVariant
    Box(Modifier.testTag("terminal_tab_$id")) {
        Row(
            Modifier
                .clickable { onSelect() }
                .pointerHoverIcon(PointerIcon.Hand)
                // Middle-click → close (cheap desktop nicety; primary click still selects). Only
                // installed where a pointing device exists, so a phone carries no dead detector.
                .then(
                    if (!pointer) {
                        Modifier
                    } else {
                        Modifier.pointerInput(id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.isTertiaryButtonPress()) {
                                        event.changes.forEach { it.consume() }
                                        onClose()
                                    }
                                }
                            }
                        }
                    },
                )
                // Square tabs; horizontal padding so label + × look centered.
                .background(bg)
                .padding(start = 14.dp, end = 8.dp)
                .testTag("term-tab-$id"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = id,
                color = fg,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Box(Modifier.testTag("terminal_close_$id"), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        // A finger needs a real target; a mouse does not (desktop's 16dp × stays).
                        .size(if (pointer) 16.dp else 36.dp)
                        .clickable { onClose() }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .alpha(if (selected) 0.85f else 0.5f)
                        .testTag("term-tab-close-$id"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close terminal",
                        tint = fg,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/** Shown when the user has closed every tab (web parity): a lone "New terminal" affordance. */
@Composable
private fun TerminalEmptyState(onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.testTag("terminal_empty_add")) {
            Row(
                Modifier
                    .clickable { onAdd() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .background(cs.surfaceContainerHigh, RoundedCornerShape(Radii.sm))
                    .padding(horizontal = Space.md, vertical = Space.sm)
                    .testTag("term-tab-empty-add"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text("New terminal", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}
