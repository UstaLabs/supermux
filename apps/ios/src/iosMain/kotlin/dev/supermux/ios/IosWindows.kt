// Extra windows on iPad: a pane torn out of the main window opens as its own scene (a second
// SwiftUI `WindowGroup`, see `SupermuxApp.swift`) beside the app — Split View, Slide Over, or a
// free window under Stage Manager. An iPhone has one window per app, and gets none of this.
//
// Every scene is in the SAME process, so they share live objects exactly as desktop's `Window {}`s
// do: one claim registry ([IosWindows.shellWindows]) and the main window's `ShellUiState`, whose
// per-workspace binds carry the layout, documents and host store an extra window draws. Android's
// `AndroidWindows.kt` is the same arrangement over activities.
package dev.supermux.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.ui.platform.WindowHostController
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.windows.RegistryShellWindows
import dev.supermux.ui.shell.windows.WindowHost
import dev.supermux.ui.shell.windows.encode
import dev.supermux.ui.shell.windows.tearOutCanvasLive
import dev.supermux.ui.shell.windows.tearOutTabFrom
import dev.supermux.ui.shell.windows.toClaim

/** The process-wide window state every scene reads. */
object IosWindows {
    /** Windows iPadOS restores keep their workspace composed (see [RegistryShellWindows]). */
    val shellWindows = RegistryShellWindows(keepPendingComposed = true)

    /** The main window's shell state while it is composed; null otherwise. */
    var mainUi by mutableStateOf<ShellUiState?>(null)

    /**
     * The one main window that owns the fleet. iPadOS lets the user open a SECOND window of the
     * main scene (App Exposé's "+"); that one must not build a second `FleetStore` — two sets of
     * sockets to every host, each overwriting the other's saves — so it defers to this one.
     * A plain field, set during the first composition, BEFORE the fleet is built.
     */
    internal var mainOwner: Any? = null
}

/**
 * The [WindowHostController] of one scene: tearing out claims in the shared registry, then asks
 * Swift (through [bridge]) to open the claim's scene.
 */
class IosWindowHostController(private val bridge: IosBridge) : WindowHostController {
    /** Touch: a drag that misses every target is not a request for a window. */
    override val tearOutOnDragMiss: Boolean = false

    private val registry get() = IosWindows.shellWindows.registry

    override fun tearOutTab(viewId: String) {
        val bind = IosWindows.mainUi?.panesBind ?: return
        tearOutTabFrom(registry, bind, viewId)?.let(::open)
    }

    override fun tearOutCanvas() {
        val bind = IosWindows.mainUi?.panesBind ?: return
        tearOutCanvasLive(registry, bind.current.id)?.let(::open)
    }

    override fun release(hostId: String) {
        registry.unclaim(hostId)
    }

    /** Open [host]'s scene. */
    fun open(host: WindowHost) {
        bridge.openExtraWindow(host.toClaim().encode())
    }
}
