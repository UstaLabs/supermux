// The whole screen of one extra window on a host whose windows are separate compositions — an
// Android activity, an iPad scene — as opposed to desktop, where every `Window {}` hangs off one
// composition and `ExtraWindowPanes` alone is enough.
//
// Such a window owns nothing but its claim. The workspace it draws is composed by the MAIN window,
// so it is a satellite of it: it says so (with a way back) whenever the main window is not
// composed, and fills in again once it is.
package dev.supermux.ui.shell.windows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.rememberHostWorkspaceSession
import dev.supermux.workspace.subtreeCovering
import kotlinx.coroutines.delay

/** How long a window waits for the main one before saying it is gone (a resize recreates it). */
private const val MAIN_WINDOW_GRACE_MS = 1_500L

/**
 * The registry side of an extra window's life: what it does when it opens, and when it closes.
 *
 * [adopt] on open: a window the system RESTORED (a new process, an empty registry) parks its claim
 * in `pending` until the main window composes its workspace and the registry takes it back. [close]
 * when the user closes the window: its views go back to the main one.
 */
object ExtraWindows {
    fun adopt(windows: RegistryShellWindows, claim: PersistedWindowHost) {
        val known = windows.registry.extras().any { it.id == claim.id } ||
            windows.pending.any { it.id == claim.id }
        if (!known) windows.pending = windows.pending + claim
    }

    fun close(windows: RegistryShellWindows, hostId: String) {
        windows.registry.unclaim(hostId)
        windows.pending = windows.pending.filterNot { it.id == hostId }
    }
}

/** A claim as a host stores it (the bounds are the system's on a touch host, so zero). */
fun WindowHost.toClaim(): PersistedWindowHost = PersistedWindowHost(
    id = id,
    workspaceId = workspaceId,
    claimedViewIds = claimedViewIds.toList(),
    x = 0f, y = 0f, width = 0f, height = 0f,
)

/**
 * A claim as one line of text, for a host that can only carry a string with a window (an iPad
 * scene's `WindowGroup` value). Ids are uuids, so the separators never occur inside one.
 */
fun PersistedWindowHost.encode(): String = "$id|$workspaceId|${claimedViewIds.joinToString(",")}"

/** The inverse of [encode]; null for anything that is not one. */
fun decodeClaim(text: String): PersistedWindowHost? {
    val parts = text.split('|')
    if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty()) return null
    return PersistedWindowHost(
        id = parts[0],
        workspaceId = parts[1],
        claimedViewIds = parts[2].split(',').filter { it.isNotEmpty() },
        x = 0f, y = 0f, width = 0f, height = 0f,
    )
}

/**
 * Extra window [hostId]'s screen.
 *
 * @param mainUi the main window's shell state while it is composed, null otherwise.
 * @param onClaim the claim as it now stands, whenever it changes — what a restore must re-claim.
 * @param onClaimGone the claim is gone (its views were closed, or moved back): close the window.
 * @param onTitle the caption this window should carry.
 * @param onOpenMain bring the main window back; the placeholder's one button.
 * @param onOpened a tab torn out of THIS window made another extra window: open it.
 */
@Composable
fun ExtraWindowHost(
    hostId: String,
    windows: RegistryShellWindows,
    mainUi: ShellUiState?,
    onClaim: (PersistedWindowHost) -> Unit,
    onClaimGone: () -> Unit,
    onTitle: (String) -> Unit,
    onOpenMain: () -> Unit,
    onOpened: (WindowHost) -> Unit,
) {
    val host = windows.registry.extras().firstOrNull { it.id == hostId }
    val waiting = host == null && windows.pending.any { it.id == hostId }
    if (host == null && !waiting) {
        val gone by rememberUpdatedState(onClaimGone)
        LaunchedEffect(Unit) { gone() }
        return
    }
    if (host != null) SideEffect { onClaim(host.toClaim()) }

    // The bind lends what does not depend on the main window REDRAWING — the host store, drafts,
    // its scope — so it only has to exist (`holders`: the main window is composed, if perhaps
    // paused off screen). The workspace itself and its session are this window's own.
    val bind = host?.let { mainUi?.panesBindFor(it.workspaceId) }?.takeIf { it.holders > 0 }
    if (mainUi == null || host == null || bind == null) {
        MainWindowGone(onOpenMain)
        return
    }
    val workspaces by bind.app.workspaces.collectAsState()
    val current = workspaces.firstOrNull { it.id == host.workspaceId } ?: bind.current
    val ws = rememberHostWorkspaceSession(
        current = current,
        wsApp = bind.app,
        overlayScope = bind.overlayScope,
        writesLayout = true,
        activateOpenedFile = false,
    )
    val tree = ws.layoutSync.tree
    // This window's tree re-derives the claims too, or a paused main window would leave a closed
    // tab's window open. But only once the tree has caught up with the claim: a window opened by
    // a tear-out starts from the broker's layout, which lacks the tear-out's split until that
    // PATCH comes back, and re-deriving from it would drop the claim it was just given.
    var caughtUp by remember(host.id) { mutableStateOf(false) }
    LaunchedEffect(current.id, tree) {
        if (!caughtUp) {
            caughtUp = host.claimedViewIds.isEmpty() || subtreeCovering(tree, host.claimedViewIds) != null
            if (!caughtUp) return@LaunchedEffect
        }
        windows.onWorkspaceTree(current.id, tree)
    }
    val title = extraWindowTitle(current.name, mainUi.windows.layoutFor(host.id, tree), ws.viewsById)
    SideEffect { onTitle(title) }
    ExtraWindowPanes(
        hostId = host.id,
        bind = bind,
        ui = mainUi,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("workspace_layout_host_extra"),
        onTearOutTab = { viewId ->
            tearOutTabFrom(windows.registry, ws, current.id, viewId)?.let(onOpened)
        },
        current = current,
        ws = ws,
    )
}

/**
 * The main window is not composed, so there is nothing to draw — say so, and offer the way back.
 * Held back for a moment first: resizing a split recreates the main window, and this must not
 * flash on every drag of the divider.
 */
@Composable
private fun MainWindowGone(onOpenMain: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(MAIN_WINDOW_GRACE_MS)
        show = true
    }
    if (!show) return
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "This window shows part of a workspace from the main supermux window, which is closed.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenMain, modifier = Modifier.testTag("extra_window_open_main")) {
                Text("Open supermux")
            }
        }
    }
}
