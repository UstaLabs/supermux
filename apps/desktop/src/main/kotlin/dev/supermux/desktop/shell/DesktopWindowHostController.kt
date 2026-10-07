// Cluster G1: the process-wide handle behind `Platform.windows` on desktop.
package dev.supermux.desktop.shell

import dev.supermux.ui.platform.WindowHostController

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

    override fun tearOutTab(viewId: String) {
        tearOutTab?.invoke(viewId)
    }

    override fun tearOutCanvas() {
        tearOutCanvas?.invoke()
    }

    override fun release(hostId: String) {
        release?.invoke(hostId)
    }
}
