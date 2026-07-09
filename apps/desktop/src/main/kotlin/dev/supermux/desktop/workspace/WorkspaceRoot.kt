// Root of the paired app shell. M1 Task 9 (this) is the workspace chrome: a collapsible,
// drag-resizable sidebar, the multi-pane SessionDetail with pane toggles, keyboard shortcuts, and
// UI-state persistence (WorkspaceStateStore → ui-state.json).
//
// State that the menu bar (Main.kt) also needs — the WorkspaceLayout + the selected session id —
// lives in a small [WorkspaceUiState] holder created in Main and passed down here, so File/View
// menu actions and the in-app shortcuts drive the same state.
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.delay
import dev.supermux.desktop.session.SessionListPanel
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.session.inferHomeDir

/**
 * Holder for the workspace UI state that both [WorkspaceRoot] and the window MenuBar (Main.kt) act
 * on: the shared [WorkspaceLayout] and the selected session id. Created once in Main (so the menu
 * can reach it), hydrated from [WorkspaceStateStore] at startup.
 */
@Stable
class WorkspaceUiState {
    val layout = WorkspaceLayout()
    var selectedId by mutableStateOf<String?>(null)
}

@Composable
fun WorkspaceRoot(
    app: DesktopAppState,
    ui: WorkspaceUiState,
    store: WorkspaceStateStore,
) {
    val layout = ui.layout
    val sessions by app.sessions.collectAsState()
    val messages by app.messages.collectAsState()
    val agentState by app.agentState.collectAsState()
    val lastBySession = remember(messages) { messages.mapValues { it.value.lastOrNull() } }

    val focused = LocalWindowInfo.current.isWindowFocused

    // TODO(M4): a real session launcher. For now New Session (shortcut Ctrl+N + File menu) is a no-op.
    val onNewSession: () -> Unit = { println("[workspace] New Session (TODO M4 launcher)") }

    // Per-session composer drafts, hoisted here so switching sessions preserves each draft.
    // In-memory only for M1 — broker-side draft sync is M4.
    val drafts = remember { mutableStateMapOf<String, String>() }

    // Headless-verification hook (no input injection on CI boxes); harmless in production (off by
    // default). With SM_AUTOSELECT=1 we auto-select a session so the workspace renders under Xvfb
    // without a pointer: the SM_SMOKE_SEND target if one is set, otherwise the most-recently-active
    // session. Skips if a (valid) persisted selection already exists.
    if (System.getenv("SM_AUTOSELECT") == "1") {
        LaunchedEffect(sessions, lastBySession) {
            if (ui.selectedId == null && sessions.isNotEmpty()) {
                val smokeName = System.getenv("SM_SMOKE_SEND")
                    ?.substringBefore(':')?.takeIf { it.isNotBlank() }
                ui.selectedId = smokeName?.let { n -> sessions.firstOrNull { it.name == n }?.id }
                    ?: sessions.maxByOrNull { lastBySession[it.id]?.ts ?: "" }?.id
                    ?: sessions.first().id
            }
        }
    }

    // Headless-verification hook (like SM_AUTOSELECT): SM_PANES=etd force-opens Editor/Terminal/
    // Display for the selected session at startup so the 3-pane split can be screenshotted under
    // Xvfb without menu/pointer input. Each letter e/t/d flips the matching pane on.
    val panesHook = System.getenv("SM_PANES")?.takeIf { it.isNotBlank() }
    if (panesHook != null) {
        LaunchedEffect(ui.selectedId) {
            val id = ui.selectedId ?: return@LaunchedEffect
            val p = layout.panesFor(id)
            layout.setPanes(id, p.copy(
                editor = p.editor || 'e' in panesHook,
                terminal = p.terminal || 't' in panesHook,
                display = p.display || 'd' in panesHook,
            ))
        }
    }

    // Re-assert viewing presence whenever the selection or window focus changes (broker per-device
    // "viewing" tracker keys off (session id, visible)).
    LaunchedEffect(ui.selectedId, focused) {
        app.updateViewing(ui.selectedId, focused)
    }

    // Sessions changed: drop a selection whose session vanished (killed elsewhere / agent exit),
    // prune the layout's per-session pane state, and prune stale drafts.
    LaunchedEffect(sessions) {
        val live = sessions.mapTo(mutableSetOf()) { it.id }
        if (ui.selectedId != null && ui.selectedId !in live) ui.selectedId = null
        layout.prune(live)
        drafts.keys.filterNot { it in live }.forEach(drafts::remove)
    }

    // Debounced persistence: whenever the layout snapshot or the selection changes, wait 500ms of
    // quiet then write ui-state.json. Reading layout.snapshot() here subscribes to all the layout's
    // snapshot state, so any change re-runs this effect (and restarts the debounce).
    val snapshot = layout.snapshot()
    LaunchedEffect(snapshot, ui.selectedId) {
        delay(500)
        store.save(PersistedUiState(layout = snapshot, selectedId = ui.selectedId))
    }

    val home = remember(sessions) {
        inferHomeDir(sessions.firstOrNull()?.workdir) ?: System.getProperty("user.home").orEmpty()
    }

    // Root focus so the workspace shortcuts (Ctrl/Cmd B/N/L/E/T/D) resolve even before the user
    // clicks into a pane; once the composer/terminal is focused, key events still bubble up here.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable()
                .workspaceShortcuts(layout, ui.selectedId, onNewSession),
        ) {
            Row(Modifier.fillMaxSize()) {
                // ── Sidebar: collapsed rail, or the full list + a drag-resize gutter ──
                if (layout.sidebarCollapsed) {
                    SessionsRail(
                        sessions = sessions,
                        selectedId = ui.selectedId,
                        agentState = agentState,
                        onSelect = { ui.selectedId = it },
                        onExpand = { layout.sidebarCollapsed = false },
                        onNewSession = onNewSession,
                    )
                } else {
                    SessionListPanel(
                        sessions = sessions,
                        home = home,
                        activeId = ui.selectedId,
                        onOpen = { ui.selectedId = it },
                        lastBySession = lastBySession,
                        agentState = agentState,
                        onRename = { id, name -> app.rename(id, name) },
                        onKill = { id -> app.kill(id) { if (ui.selectedId == id) ui.selectedId = null } },
                        onMute = { id, muted -> app.setMute(id, muted) },
                        modifier = Modifier.width(layout.sidebarWidth).fillMaxHeight(),
                    )
                    SidebarDivider(
                        onDragDelta = { d -> layout.setSidebarWidth(layout.sidebarWidth + d) },
                        onCollapse = { layout.sidebarCollapsed = true },
                    )
                }

                // ── Detail: the multi-pane SessionDetail, or an empty prompt ──
                val id = ui.selectedId
                val session = id?.let { sel -> sessions.firstOrNull { it.id == sel } }
                if (session == null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("select a session", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    SessionDetail(
                        app = app,
                        session = session,
                        agent = agentState[session.id],
                        layout = layout,
                        draft = drafts[session.id] ?: "",
                        onDraftChange = { drafts[session.id] = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
