package dev.supermux.android.windows

import dev.supermux.ui.shell.windows.PersistedWindowHost
import dev.supermux.ui.shell.windows.WindowBounds
import dev.supermux.ui.shell.windows.tearOutTab
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Extra windows on Android: what a torn-out tab claims, and what a restored window keeps alive. */
class AndroidWindowsTest {

    @Test
    fun `a window waiting to be restored keeps its workspace composed`() {
        val windows = AndroidShellWindows()
        windows.pending = listOf(PersistedWindowHost("w1", "ws-restored", listOf("v1"), 0f, 0f, 0f, 0f))
        assertEquals(setOf("ws-restored"), windows.extraWorkspaceIds())
    }

    @Test
    fun `a restored window re-claims its views once the main window composes its workspace`() {
        val windows = AndroidShellWindows()
        windows.pending = listOf(PersistedWindowHost("w1", "ws", listOf("v2"), 0f, 0f, 0f, 0f))
        val tree = LayoutNode.Split(
            "row",
            listOf(0.5, 0.5),
            listOf(LayoutNode.Group("g1", listOf("v1"), "v1"), LayoutNode.Group("g2", listOf("v2"), "v2")),
        )

        windows.setWorkspaceOnMain("ws")
        windows.onWorkspaceTree("ws", tree)

        val host = assertNotNull(windows.registry.extras().singleOrNull { it.id == "w1" })
        assertEquals(setOf("v2"), host.claimedViewIds)
        assertTrue(windows.pending.isEmpty())
        // Live now, and still counted — once, not twice.
        assertEquals(setOf("ws"), windows.extraWorkspaceIds())
        // The main window no longer draws the claimed view.
        assertEquals(LayoutNode.Group("g1", listOf("v1"), "v1"), windows.layoutFor(windows.mainHostId, tree))
    }

    @Test
    fun `a tab torn out of the main window splits into its own group and is claimed`() {
        val windows = AndroidShellWindows()
        windows.setWorkspaceOnMain("ws")
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("v1", "v2"), "v1")
        val host = assertNotNull(
            tearOutTab(
                windows.registry, tree, "v2", "ws", "g-new", WindowBounds(0f, 0f, 0f, 0f), "w1",
            ) { next -> tree = next; tree },
        )
        assertEquals(setOf("v2"), host.claimedViewIds)
        assertEquals(listOf("v1"), collectViewIds(windows.layoutFor(windows.mainHostId, tree)))
        assertEquals(listOf("v2"), collectViewIds(windows.layoutFor("w1", tree)))
    }
}
