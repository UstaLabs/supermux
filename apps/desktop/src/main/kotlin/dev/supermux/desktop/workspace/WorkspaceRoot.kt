// Root of the paired app shell. M1 Task 6 (Session list port) fills the left column with the
// real session list; the right (chat) column stays a placeholder until Task 7/8 (chat timeline +
// composer) land, and Task 9 (Workspace shell) adds resizable-sidebar chrome around this Row. The
// viewing-presence wiring below (window focus + selection → DesktopAppState.updateViewing) is the
// real M1 contract and now reports the actual selected session instead of always null.
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.chat.TimelineItemRow
import dev.supermux.desktop.chat.mergeTimeline
import dev.supermux.desktop.session.SessionListPanel
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.Space
import dev.supermux.session.inferHomeDir

/** Fixed sidebar width for M1 — Task 9 (Workspace shell) replaces this with a real
 *  drag-resizable sidebar (Android's `SidebarDivider` / `workspaceLayout.sidebarWidth`). */
private val SIDEBAR_WIDTH = 320.dp

@Composable
fun WorkspaceRoot(app: DesktopAppState) {
    val sessions by app.sessions.collectAsState()
    val messages by app.messages.collectAsState()
    val activity by app.activity.collectAsState()
    val agentState by app.agentState.collectAsState()
    val lastBySession = remember(messages) { messages.mapValues { it.value.lastOrNull() } }

    var selectedId by remember { mutableStateOf<String?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused

    // TEMP(T8): auto-select the most-recently-active session so the timeline renders in a headless
    // run (no pointer to click a row). Task 8's real ChatPanel + composer replaces this whole block.
    LaunchedEffect(sessions, lastBySession) {
        if (selectedId == null && sessions.isNotEmpty()) {
            selectedId = sessions.maxByOrNull { lastBySession[it.id]?.ts ?: "" }?.id ?: sessions.first().id
        }
    }

    // Lazily fetch the selected session's transcript (archive resume has no snapshot history).
    LaunchedEffect(selectedId) { selectedId?.let(app::ensureMessagesLoaded) }

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
            // TEMP(T8): rough read-only timeline preview so Task 7's rendering can be verified
            // before Task 8 builds the real ChatPanel (header, composer, autoscroll, callbacks).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = if (selectedId == null) Alignment.Center else Alignment.TopStart,
            ) {
                val id = selectedId
                if (id == null) {
                    Text("select a session", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val items = remember(messages, activity, id) {
                        mergeTimeline(messages[id].orEmpty(), activity[id].orEmpty())
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md)) {
                        items(items) { item -> TimelineItemRow(item = item) }
                    }
                }
            }
        }
    }
}
