// Extra windows on Android: a pane torn out of the main window opens as its own activity
// ([ExtraWindowActivity]) beside the app — split screen on a phone or a tablet, a free-form window
// under DeX / desktop windowing.
//
// Every window is in the SAME process, so they share live objects exactly as desktop's `Window {}`s
// do: one claim registry ([AndroidWindows.shellWindows]) and the main window's `ShellUiState`,
// whose per-workspace binds carry the layout, documents and host store an extra window draws.
// The main window owns those binds — an extra window is a satellite of it, and says so (with a way
// back) whenever the main window is not composed.
package dev.supermux.android.windows

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.ui.platform.WindowHostController
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.windows.RegistryShellWindows
import dev.supermux.ui.shell.windows.WindowHost
import dev.supermux.ui.shell.windows.tearOutCanvasLive
import dev.supermux.ui.shell.windows.tearOutTabFrom

/**
 * Android's claims. A window the system restored after process death sits in [pending] until the
 * main window composes its workspace again — and its workspace must BE composed for that to
 * happen, so pending windows count as extra workspaces too (desktop re-opens its pending windows
 * only when the user gets back to their workspace; here the window is already on screen, waiting).
 */
class AndroidShellWindows : RegistryShellWindows() {
    override fun extraWorkspaceIds(): Set<String> =
        super.extraWorkspaceIds() + pending.map { it.workspaceId }
}

/** The process-wide window state every activity reads. */
object AndroidWindows {
    val shellWindows = AndroidShellWindows()

    /** The main window's shell state while it is composed; null otherwise. */
    var mainUi by mutableStateOf<ShellUiState?>(null)
}

/**
 * The [WindowHostController] of one activity: tearing out claims in the shared registry, then
 * opens the claim's window from THIS activity, so it lands beside it.
 */
class AndroidWindowHostController(private val activity: Activity) : WindowHostController {
    /** Touch: a drag that misses every target is not a request for a window. */
    override val tearOutOnDragMiss: Boolean = false

    private val registry get() = AndroidWindows.shellWindows.registry

    override fun tearOutTab(viewId: String) {
        val bind = AndroidWindows.mainUi?.panesBind ?: return
        tearOutTabFrom(registry, bind, viewId)?.let(::open)
    }

    override fun tearOutCanvas() {
        val bind = AndroidWindows.mainUi?.panesBind ?: return
        tearOutCanvasLive(registry, bind.current.id)?.let(::open)
    }

    override fun release(hostId: String) {
        registry.unclaim(hostId)
    }

    /** Open [host]'s window beside this activity. */
    fun open(host: WindowHost) {
        runCatching { activity.startActivity(ExtraWindowActivity.intent(activity, host)) }
            // No window to show it in: give the views back rather than hide them.
            .onFailure { registry.unclaim(host.id) }
    }
}

/** The flags that put an extra window in its own task, beside the one that opened it. */
internal const val EXTRA_WINDOW_FLAGS: Int = Intent.FLAG_ACTIVITY_NEW_TASK or
    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
