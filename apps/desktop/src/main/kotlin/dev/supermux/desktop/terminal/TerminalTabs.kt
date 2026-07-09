// Web-parity scratch-terminal tab strip (src/web-app/src/components/TerminalPanel.vue). One strip
// per session; each tab is one tmux-backed terminal. The tab SET is rebuilt from the broker
// (listTerminals → live tmux) on open, so both the shells AND the tabs survive an app restart.
//
// Android has no terminal tabs (a touch-platform simplification); web is the parity reference, so
// the desktop client matches web. See the M2 plan Task 6.
package dev.supermux.desktop.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.LocalPanes
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.KeepAlivePanel
import dev.supermux.net.TerminalClient
import kotlin.random.Random

/** Broker's default scratch-terminal name; the first tab of a fresh session uses it so connecting
 *  with terminal="main" lands on the same tmux terminal the broker would auto-create. */
private const val DEFAULT_TERMINAL_ID = "main"

/** New-tab id: a short random hex suffix, mirroring web's `genId()` fallback (`"t" + rand hex`). The
 *  id is the tmux terminal name the broker creates on first connect. */
private fun genTerminalId(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder("t")
    repeat(8) { sb.append(hex[Random.nextInt(hex.length)]) }
    return sb.toString()
}

/**
 * Per-session terminal tab strip + the active terminal below it.
 *
 * TAB POLICY (plan Task 6): only the ACTIVE tab's panel is fully visible. Inactive panels are kept
 * alive (composed, connected) via [KeepAlivePanel] under a BOUNDED policy — at most the active tab
 * PLUS the most-recently-active tab stay composed; every other tab is fully disposed (its
 * [TerminalClient] stops + the JediTerm widget closes on leaving the composition). This caps live
 * websockets at two per session regardless of how many tabs exist, while making the common
 * flip-back-to-the-last-tab instant (no reconnect / no blank grid). Background shells keep running
 * headless in tmux, so a disposed tab reconnects to the SAME shell when re-selected.
 *
 * KEY ISOLATION (T3 review warning): each tab's panel is wrapped in `key(tabId)`. [DesktopTerminalPanel]
 * builds its [TerminalClient] with a bare `remember { connect() }` (no key) — without the per-tab
 * `key`, Compose could reuse the previous tab's remembered client for a different tab id. The `key`
 * ties each remembered client to its tab identity, so switching tabs (or reusing a slot) always
 * yields the right client.
 *
 * @param panelContent injectable panel slot — defaults to the real [DesktopTerminalPanel]. Its
 *   SwingPanel cannot be hosted under `runComposeUiTest` (no real AWT window), so UI tests inject a
 *   lightweight pure-Compose fake to exercise the strip's add/close/select + key isolation.
 */
@Composable
fun TerminalTabs(
    app: DesktopAppState,
    sessionId: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    panelContent: @Composable (tabId: String, connect: () -> TerminalClient, active: Boolean) -> Unit = {
        tabId, connect, act ->
        DesktopTerminalPanel(connect = connect, active = act, modifier = Modifier.fillMaxSize())
    },
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme

    // State is keyed on sessionId so a session switch (ChatView reuse) resets the strip cleanly.
    val tabs = remember(sessionId) { mutableStateListOf<String>() }
    var activeId by remember(sessionId) { mutableStateOf("") }
    var lastActiveId by remember(sessionId) { mutableStateOf<String?>(null) }
    var hydrated by remember(sessionId) { mutableStateOf(false) }

    // Rebuild the tab set from the broker (live tmux) on first show for this session. Empty / failed
    // list → start with one DEFAULT_TERMINAL_ID tab so the pane is immediately usable (web parity).
    LaunchedEffect(sessionId) {
        val ids = app.listTerminals(sessionId).map { it.id } // broker returns them sorted by createdAt asc
        tabs.clear()
        if (ids.isEmpty()) {
            tabs.add(DEFAULT_TERMINAL_ID)
            activeId = DEFAULT_TERMINAL_ID
        } else {
            tabs.addAll(ids)
            activeId = ids.first()
        }
        lastActiveId = null
        hydrated = true
    }

    fun selectTab(id: String) {
        if (id == activeId) return
        lastActiveId = activeId.takeIf { it.isNotEmpty() }
        activeId = id
    }

    fun addTab() {
        val id = genTerminalId()
        lastActiveId = activeId.takeIf { it.isNotEmpty() }
        tabs.add(id)
        activeId = id
    }

    // Web's pickActiveAfterRemoval: if the active tab is still present, keep it; else fall to the
    // tab that shifted into the removed slot, then the one before it, then the first, then none.
    fun pickActiveAfterRemoval(removedIdx: Int) {
        if (activeId.isNotEmpty() && tabs.any { it == activeId }) return
        val next = tabs.getOrNull(removedIdx) ?: tabs.getOrNull(removedIdx - 1) ?: tabs.getOrNull(0)
        activeId = next ?: ""
    }

    fun closeTab(id: String) {
        val removedIdx = tabs.indexOf(id)
        if (removedIdx < 0) return
        tabs.removeAt(removedIdx)
        if (id == lastActiveId) lastActiveId = null
        if (id == activeId) pickActiveAfterRemoval(removedIdx)
        // Best-effort tmux teardown (fire-and-forget inside DesktopAppState) — the tab is already
        // gone locally regardless of the outcome (web parity: the shell may already have exited).
        app.closeTerminal(sessionId, id)
    }

    Column(modifier.fillMaxSize().background(Color(c.terminal))) {
        // ── Tab strip ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(cs.surfaceContainerLow)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Space.xs, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            tabs.forEach { id ->
                TerminalTabChip(
                    id = id,
                    selected = id == activeId,
                    onSelect = { selectTab(id) },
                    onClose = { closeTab(id) },
                )
            }
            // + : new terminal
            Box(
                Modifier
                    .size(24.dp)
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
        Box(Modifier.fillMaxWidth().size(1.dp).background(cs.outlineVariant))

        // ── Active terminal (+ the kept-alive last-active one, hidden) ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hydrated && tabs.isEmpty()) {
                TerminalEmptyState(onAdd = { addTab() })
            }
            tabs.forEach { id ->
                // Bounded keep-alive: only the active + most-recently-active tabs stay composed; the
                // rest are not emitted here → disposed. key(id) ties each remembered client to its
                // tab (see the KEY ISOLATION note above).
                if (id == activeId || id == lastActiveId) {
                    key(id) {
                        KeepAlivePanel(visible = id == activeId) {
                            panelContent(id, { app.connectTerminal(sessionId, id) }, id == activeId && active)
                        }
                    }
                }
            }
        }
    }
}

/** One tab chip: mono id label + an always-present (hover-brightened) × close affordance. Middle-
 *  click also closes (a desktop convenience the web strip lacks). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TerminalTabChip(
    id: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.surface else Color.Transparent
    val fg = if (selected) cs.onSurface else cs.onSurfaceVariant
    Row(
        Modifier
            .clickable { onSelect() }
            .pointerHoverIcon(PointerIcon.Hand)
            // Middle-click → close (cheap desktop nicety; primary click still selects).
            .onPointerEvent(PointerEventType.Press) { e ->
                if (e.button == PointerButton.Tertiary) onClose()
            }
            .background(bg, RoundedCornerShape(Radii.sm))
            .padding(start = Space.sm, end = 3.dp, top = 3.dp, bottom = 3.dp)
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
        Box(
            Modifier
                .size(16.dp)
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

/** Shown when the user has closed every tab (web parity): a lone "New terminal" affordance. */
@Composable
private fun TerminalEmptyState(onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
