// TEMPORARY placeholder for the paired app shell. M1 Task 9 (Workspace shell) replaces this
// body with the real session-list / chat-timeline / composer layout; the viewing-presence wiring
// below (window focus → DesktopAppState.updateViewing) is the real M1 contract and stays as-is.
package dev.supermux.desktop.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import dev.supermux.desktop.state.DesktopAppState

/**
 * Root of the paired app (post-pairing-gate). Placeholder body — reports live session count
 * so M1 Task 5's live-broker verification has something to observe. Also exercises the viewing-
 * presence plumbing: reports the foreground window's focus state to the broker so its per-device
 * "viewing" tracker is current (session id is always null here; Task 9 wires a real selection).
 */
@Composable
fun WorkspaceRoot(app: DesktopAppState) {
    val sessions by app.sessions.collectAsState()
    val focused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(focused) {
        app.updateViewing(null, focused)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Text(
            "paired to ${app.baseUrl} — ${sessions.size} sessions",
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
