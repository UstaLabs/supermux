// Root of the paired app shell. M1 Task 6 (Session list port) fills the left column with the real
// session list; Task 8 (this) wires the right column to the real ChatPanel (header + keyed timeline
// + composer) for the selected session; Task 9 (Workspace shell) adds resizable-sidebar chrome
// around this Row. The viewing-presence wiring below (window focus + selection →
// DesktopAppState.updateViewing) is the real M1 contract and reports the actual selected session.
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.chat.ChatPanel
import dev.supermux.desktop.session.SessionListPanel
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.session.inferHomeDir

/** Fixed sidebar width for M1 — Task 9 (Workspace shell) replaces this with a real
 *  drag-resizable sidebar (Android's `SidebarDivider` / `workspaceLayout.sidebarWidth`). */
private val SIDEBAR_WIDTH = 320.dp

@Composable
fun WorkspaceRoot(app: DesktopAppState) {
    val sessions by app.sessions.collectAsState()
    val messages by app.messages.collectAsState()
    val agentState by app.agentState.collectAsState()
    val lastBySession = remember(messages) { messages.mapValues { it.value.lastOrNull() } }

    var selectedId by remember { mutableStateOf<String?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused

    // Per-session composer drafts, hoisted here so switching sessions preserves each draft
    // (a `remember(session.id)` inside ChatPanel would drop it). In-memory only for M1 —
    // broker-side draft sync is M4.
    val drafts = remember { mutableStateMapOf<String, String>() }

    // Headless-verification hook (no input injection on CI boxes); harmless in production (off by
    // default). With SM_AUTOSELECT=1 we auto-select a session so the chat panel renders under Xvfb
    // without a pointer to click a row: the SM_SMOKE_SEND target if one is set (so the live
    // round-trip IS the visible panel), otherwise the most-recently-active session.
    if (System.getenv("SM_AUTOSELECT") == "1") {
        LaunchedEffect(sessions, lastBySession) {
            if (selectedId == null && sessions.isNotEmpty()) {
                val smokeName = System.getenv("SM_SMOKE_SEND")
                    ?.substringBefore(':')?.takeIf { it.isNotBlank() }
                selectedId = smokeName?.let { n -> sessions.firstOrNull { it.name == n }?.id }
                    ?: sessions.maxByOrNull { lastBySession[it.id]?.ts ?: "" }?.id
                    ?: sessions.first().id
            }
        }
    }

    // Re-assert viewing presence whenever the selection or window focus changes (Android
    // AppViewModel / iOS BrokerSession parity) — the broker's per-device "viewing" tracker keys
    // off (session id, visible).
    LaunchedEffect(selectedId, focused) {
        app.updateViewing(selectedId, focused)
    }

    // A session removed externally (killed from another client, agent exit) must drop the local
    // selection, otherwise updateViewing keeps asserting a dead session id.
    LaunchedEffect(sessions) {
        if (selectedId != null && sessions.none { it.id == selectedId }) selectedId = null
        // Prune drafts for sessions that no longer exist (slow key leak otherwise).
        val live = sessions.mapTo(mutableSetOf()) { it.id }
        drafts.keys.filterNot { it in live }.forEach(drafts::remove)
    }

    val home = remember(sessions) {
        inferHomeDir(sessions.firstOrNull()?.workdir) ?: System.getProperty("user.home").orEmpty()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            SessionListPanel(
                sessions = sessions,
                home = home,
                activeId = selectedId,
                onOpen = { selectedId = it },
                lastBySession = lastBySession,
                agentState = agentState,
                onRename = { id, name -> app.rename(id, name) },
                onKill = { id -> app.kill(id) { if (selectedId == id) selectedId = null } },
                onMute = { id, muted -> app.setMute(id, muted) },
                modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight(),
            )
            val id = selectedId
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
                ChatPanel(
                    app = app,
                    session = session,
                    draft = drafts[session.id] ?: "",
                    onDraftChange = { drafts[session.id] = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
