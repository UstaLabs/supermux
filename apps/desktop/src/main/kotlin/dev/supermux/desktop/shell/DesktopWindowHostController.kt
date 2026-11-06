// Cluster G1: the process-wide handle behind `Platform.windows` on desktop.
package dev.supermux.desktop.shell

import dev.supermux.ui.panes.TabDropEndpoint
import dev.supermux.ui.panes.TabHandoff
import dev.supermux.ui.panes.TabTearOff
import dev.supermux.ui.platform.WindowHostController
import dev.supermux.ui.shell.windows.WindowHostRegistry
import dev.supermux.ui.workspace.WorkspaceSession

/**
 * Desktop's [WindowHostController]: the three verbs a shared shell needs over [WindowHostRegistry].
 *
 * The registry and the tear-out planners live with the window that owns them (`AppShell` holds the
 * live `ShellUiState.windowHosts`, and only it can resolve "the calling window" into a
 * `panesBind`), while `Platform` is built per theme mount — so the seam is this stable singleton
 * and the shell [bind]s itself into it for as long as it is composed. Unbound, every verb is a
 * no-op: no window is torn out, nothing is released, and nothing throws.
 */
object DesktopWindowHostController : WindowHostController {
    private var tearOutTab: ((String) -> Unit)? = null
    private var tearOutCanvas: (() -> Unit)? = null
    private var release: ((String) -> Unit)? = null

    /**
     * Install the tear-out verbs. Only the composed shell can resolve "the calling window" into a
     * `panesBind`, so these come and go with it — [unbindTearOut] on teardown.
     */
    fun bindTearOut(tearOutTab: (String) -> Unit, tearOutCanvas: () -> Unit) {
        this.tearOutTab = tearOutTab
        this.tearOutCanvas = tearOutCanvas
    }

    fun unbindTearOut() {
        tearOutTab = null
        tearOutCanvas = null
    }

    /**
     * Install the release verb — bound by whoever OWNS the windows (`Main.kt`), not by the shell:
     * an extra window can outlive a composed `AppShell` (the last host is forgotten while a
     * detached window is still open), and closing it must still unclaim its views.
     */
    fun bindRelease(release: (String) -> Unit) {
        this.release = release
    }

    /** The window registry live tear-off claims new windows on — bound by `Main.kt`, its owner. */
    fun bindRegistry(registry: WindowHostRegistry) {
        DesktopTearOff.bind(registry)
    }

    override fun tearOutTab(viewId: String) {
        tearOutTab?.invoke(viewId)
    }

    override fun tearOutCanvas() {
        tearOutCanvas?.invoke()
    }

    override fun release(hostId: String) {
        release?.invoke(hostId)
    }

    /**
     * Resolved per call, not once: a window's panes keep the object they were handed, and must
     * reach whichever drag world is bound now (see [bindRegistry]), even one bound after them.
     */
    override fun tabTearOff(hostId: String, ws: WorkspaceSession, workspaceId: String): TabTearOff =
        object : TabTearOff {
            private fun world() = DesktopTearOff.world?.tearOffFor(hostId, ws.layoutSync, workspaceId)

            override fun register(endpoint: TabDropEndpoint) {
                world()?.register(endpoint)
            }

            override fun unregister(endpoint: TabDropEndpoint) {
                world()?.unregister(endpoint)
            }

            override fun takeOverOnStart(handoff: TabHandoff): Boolean = world()?.takeOverOnStart(handoff) == true

            override fun takeOverOutside(handoff: TabHandoff): Boolean = world()?.takeOverOutside(handoff) == true
        }
}
