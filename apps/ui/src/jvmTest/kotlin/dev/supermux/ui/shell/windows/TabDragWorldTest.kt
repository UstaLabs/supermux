package dev.supermux.ui.shell.windows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.supermux.ui.panes.PaneDragController
import dev.supermux.ui.panes.TabDropEndpoint
import dev.supermux.ui.panes.TabHandoff
import dev.supermux.ui.panes.TabTearOff
import dev.supermux.ui.panes.applyPaneDrop
import dev.supermux.ui.panes.restoreTab
import dev.supermux.ui.workspace.WorkspaceLayoutState
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.removeViewFromLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live tear-off across windows, over fake windows: a window is its content rect on a "screen"
 * (no frame), and each one's pane layer is a real [PaneDragController] with the bounds its
 * PaneHost would register. The tree and the claims are the real ones.
 */
class TabDragWorldTest {

    // ── Starting ────────────────────────────────────────────────────────────────────

    @Test
    fun `a tab dragged out of its window opens in one of its own, under the pointer`() {
        val f = Fixture()
        assertTrue(f.mainTearOff.takeOverOutside(f.handoffB(pointer = Offset(500f, -40f))))

        val torn = assertNotNull(f.world.carrierHostId)
        assertEquals(setOf("b"), f.windows.registry.extras().single().claimedViewIds)
        // Opened so the pointer (screen 600,60) holds the tab where it held it (30,10 into it),
        // the size its group had.
        assertEquals(WindowBounds(570f, 50f, 1000f, 700f), f.windows.registry.extras().single().bounds)
        assertEquals(listOf("a", "c"), collectViewIds(f.windows.layoutFor("main", f.layout.tree)))
        assertEquals(listOf("b"), collectViewIds(f.windows.layoutFor(torn, f.layout.tree)))
        assertTrue(f.platform.live)
        assertTrue(torn in f.platform.following)
    }

    @Test
    fun `the window opened for the tab follows the pointer, holding the tab where it was grabbed`() {
        val f = Fixture()
        val torn = f.tearOffB()
        f.world.move(Offset(700f, 300f))
        assertEquals(Offset(670f, 290f), f.platform.windows.getValue(torn).origin)
    }

    @Test
    fun `before the new window is on screen, following moves where it will open`() {
        val f = Fixture()
        assertTrue(f.mainTearOff.takeOverOutside(f.handoffB(pointer = Offset(500f, -40f))))
        f.world.move(Offset(700f, 300f))
        assertEquals(670f, f.windows.registry.extras().single().bounds.x)
        assertEquals(290f, f.windows.registry.extras().single().bounds.y)
    }

    @Test
    fun `a new window opens so the pointer holds the tab past that window's own controls`() {
        val f = Fixture()
        // The new window's strip starts past its traffic lights.
        f.platform.newWindowTab = Offset(78f, 0f)
        assertTrue(f.mainTearOff.takeOverOutside(f.handoffB(pointer = Offset(500f, -40f))))
        assertEquals(WindowBounds(492f, 50f, 1000f, 700f), f.windows.registry.extras().single().bounds)
        f.world.move(Offset(700f, 300f))
        assertEquals(592f, f.windows.registry.extras().single().bounds.x, "following before it is on screen keeps that")
    }

    @Test
    fun `a host only moves a window that has nothing but the dragged tab`() {
        val f = Fixture()
        // The main window is never moved by a tab, whatever it holds.
        assertFalse(f.mainTearOff.takeOverOnStart(f.handoffB(pointer = Offset(150f, 16f))))
        assertFalse(f.world.active)
    }

    // ── Snapping back ───────────────────────────────────────────────────────────────

    @Test
    fun `brought over a strip of its window, the tab is previewed there and its window goes away`() {
        val f = Fixture()
        val torn = f.tearOffB()
        f.world.move(f.overMainStrip)

        assertEquals("main", f.world.overHostId)
        assertTrue(torn in f.platform.hidden)
        assertTrue(f.mainDrag.isDragging)
        assertEquals("b", f.mainDrag.draggingViewId)
        // The tree has not moved yet: Esc or a pass back out must still find everything in place.
        assertEquals(setOf("b"), f.windows.registry.extras().single().claimedViewIds)
    }

    @Test
    fun `released over the strip it came from, the tab is back where it was and its window closes`() {
        val f = Fixture()
        f.tearOffB()
        f.world.move(f.overMainStrip)
        f.world.release(f.overMainStrip)

        assertEquals(LayoutNode.Group("g1", listOf("a", "b", "c"), "b"), f.layout.tree)
        assertTrue(f.windows.registry.extras().isEmpty())
        assertFalse(f.world.active)
        assertFalse(f.platform.live)
        assertEquals("main", f.platform.focused.last())
    }

    @Test
    fun `leaving the window again brings the tab's own window back under the pointer`() {
        val f = Fixture()
        val torn = f.tearOffB()
        f.world.move(f.overMainStrip)
        // Past the main window's bottom edge by more than the margin.
        f.world.move(Offset(600f, 850f))

        assertNull(f.world.overHostId)
        assertFalse(torn in f.platform.hidden)
        assertFalse(f.mainDrag.isDragging)
        assertEquals(Offset(570f, 840f), f.platform.windows.getValue(torn).origin)
    }

    @Test
    fun `a pointer just past the edge of the window it is over keeps the tab there`() {
        val f = Fixture()
        f.tearOffB()
        f.world.move(f.overMainStrip)
        f.world.move(Offset(600f, 808f)) // main's content ends at y = 800
        assertEquals("main", f.world.overHostId)
    }

    @Test
    fun `released over a window on nothing that takes a tab, the tab's window stays there`() {
        val f = Fixture()
        val torn = f.tearOffB()
        f.world.move(f.overMainStrip)
        // Still over main, but past its strip and pane: its drag resolves nothing.
        f.mainDrag.forgetAllBounds()
        f.world.move(Offset(600f, 400f))
        f.world.release(Offset(600f, 400f))

        assertEquals(setOf("b"), f.windows.registry.extras().single().claimedViewIds)
        assertFalse(torn in f.platform.hidden)
        assertEquals(torn, f.platform.focused.last())
    }

    // ── Which window takes it ───────────────────────────────────────────────────────

    @Test
    fun `a window of another workspace never takes the tab`() {
        val f = Fixture()
        val torn = f.tearOffB()
        val other = f.addWindow("other", "ws-2", Offset(100f, 900f), Size(800f, 400f))
        other.registerStrip("o1", Rect(0f, 0f, 800f, 32f))
        f.world.move(Offset(300f, 916f))

        assertNull(f.world.overHostId)
        assertFalse(torn in f.platform.hidden)
    }

    @Test
    fun `a strip hidden behind another window does not take the tab`() {
        val f = Fixture()
        f.tearOffB()
        // A window of the same workspace over the main window's strip, with only content there.
        val cover = f.addWindow("cover", "ws", Offset(200f, 80f), Size(400f, 300f))
        cover.registerPane("cv", Rect(0f, 0f, 400f, 300f))
        f.world.move(f.overMainStrip)

        assertNull(f.world.overHostId)
        assertFalse(f.mainDrag.isDragging)
    }

    @Test
    fun `over another window's content, not its strip, the tab's window can be dropped on it`() {
        val f = Fixture()
        val torn = f.tearOffB()
        f.world.move(Offset(600f, 500f)) // main's pane body
        f.world.release(Offset(600f, 500f))

        assertNull(f.world.overHostId)
        assertEquals(setOf("b"), f.windows.registry.extras().single().claimedViewIds)
        assertEquals(torn, f.platform.focused.last())
    }

    // ── Esc ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Esc puts a torn-off tab back where it was, active tab included`() {
        val f = Fixture()
        f.tearOffB()
        f.world.move(Offset(700f, 300f))
        assertTrue(f.world.cancel())

        assertEquals(LayoutNode.Group("g1", listOf("a", "b", "c"), "a"), f.layout.tree)
        assertTrue(f.windows.registry.extras().isEmpty())
        assertFalse(f.world.active)
        assertFalse(f.platform.live)
    }

    @Test
    fun `Esc over another window ends that window's preview too`() {
        val f = Fixture()
        f.tearOffB()
        f.world.move(f.overMainStrip)
        assertTrue(f.world.cancelActiveDrag())

        assertFalse(f.mainDrag.isDragging)
        assertEquals(LayoutNode.Group("g1", listOf("a", "b", "c"), "a"), f.layout.tree)
    }

    @Test
    fun `Esc ends a drag that never left its window`() {
        val f = Fixture()
        f.mainDrag.begin("a", "g1", Offset(50f, 16f), listOf("a", "b", "c"), "a", Rect(0f, 0f, 100f, 32f))
        f.mainDrag.updatePointer(Offset(150f, 16f), Offset(50f, 16f), 8f)
        assertTrue(f.world.cancelActiveDrag())

        assertFalse(f.mainDrag.isDragging)
        assertTrue(f.mainDrag.aborted)
        assertFalse(f.world.cancelActiveDrag())
    }

    // ── A window's only tab drags the window ────────────────────────────────────────

    @Test
    fun `dragging a window's only tab moves that window`() {
        val f = Fixture.withSoloWindow()
        assertTrue(f.soloTearOff().takeOverOnStart(f.handoffC()))

        assertEquals("solo", f.world.carrierHostId)
        f.world.move(Offset(1300f, 400f))
        assertEquals(Offset(1280f, 390f), f.platform.windows.getValue("solo").origin)
    }

    @Test
    fun `a moved window's tab dropped on another window's strip docks there, and the window closes`() {
        val f = Fixture.withSoloWindow()
        f.soloTearOff().takeOverOnStart(f.handoffC())
        f.world.move(f.overMainStrip)

        // The window the press began in cannot go away mid-press: it steps behind instead.
        assertEquals("main", f.world.overHostId)
        assertEquals("solo", f.platform.sentBack.last())
        f.world.release(f.overMainStrip)

        assertEquals(LayoutNode.Group("g1", listOf("a", "c", "b"), "c"), f.layout.tree)
        assertTrue(f.windows.registry.extras().isEmpty())
    }

    @Test
    fun `Esc puts a moved window back where it was`() {
        val f = Fixture.withSoloWindow()
        f.soloTearOff().takeOverOnStart(f.handoffC())
        f.world.move(Offset(1300f, 400f))
        assertTrue(f.world.cancel())

        assertEquals(Offset(1200f, 100f), f.platform.windows.getValue("solo").origin)
        assertEquals(setOf("c"), f.windows.registry.extras().single().claimedViewIds)
    }

    // ── Odd ends ────────────────────────────────────────────────────────────────────

    @Test
    fun `a tab closed elsewhere mid-drag ends the drag`() {
        val f = Fixture()
        val torn = f.tearOffB()
        f.world.move(f.overMainStrip)
        f.layout.edit { removeViewFromLayout(it, "b") ?: it }
        f.world.move(Offset(700f, 300f))

        assertFalse(f.world.active)
        assertFalse(f.mainDrag.isDragging)
        assertFalse(torn in f.platform.hidden)
    }

    @Test
    fun `dropped on an empty canvas, the tab joins that window without being merged into another window's group`() {
        // Every tab is in a window of its own: the main window shows an empty canvas, whose
        // placeholder group is the tree's first group — x's.
        val tree = LayoutNode.Split(
            "row",
            listOf(0.5, 0.5),
            listOf(LayoutNode.Group("gx", listOf("a"), "a"), LayoutNode.Group("gy", listOf("b"), "b")),
        )
        val f = Fixture(tree)
        assertNotNull(f.windows.registry.tryClaim("ws", setOf("a"), WindowBounds(0f, 0f, 1f, 1f), "x", tree))
        assertNotNull(f.windows.registry.tryClaim("ws", setOf("b"), WindowBounds(0f, 0f, 1f, 1f), "y", tree))
        f.mainDrag.forgetAllBounds()
        f.mainDrag.registerPane("gx", Rect(0f, 0f, 1000f, 700f))
        f.mainDrag.registerEmptyPane("gx", Rect(0f, 0f, 1000f, 700f), stripHeight = 32f)
        val y = f.addWindow("y", "ws", Offset(1200f, 100f), Size(400f, 300f))
        y.registerTab("gy", "b", Rect(0f, 0f, 100f, 32f))
        val yTearOff = f.tearOffs.getValue("y")

        assertTrue(
            yTearOff.takeOverOnStart(
                TabHandoff("b", "b", "gy", 0, "b", Offset(20f, 10f), Size(100f, 32f), Size(400f, 300f), Offset(40f, 20f)),
            ),
        )
        // Over the canvas but not where its strip would be: the window could be dropped there.
        f.world.move(Offset(600f, 450f))
        assertNull(f.world.overHostId)
        // At its top, where a strip would be: it takes the tab.
        f.world.move(Offset(600f, 116f))
        assertEquals("main", f.world.overHostId)
        f.world.release(Offset(600f, 116f))

        assertEquals(tree, f.layout.tree)
        assertEquals(listOf("x"), f.windows.registry.extras().map { it.id })
        assertEquals(listOf("b"), collectViewIds(f.windows.layoutFor("main", f.layout.tree)))
    }

    @Test
    fun `a window's only tab, torn off, comes back by its empty canvas's top and leaves again pulled away from it`() {
        val f = Fixture(LayoutNode.Group("g1", listOf("a"), "a"))
        val pulled = TabHandoff("a", "a", "g1", 0, "a", Offset(30f, 10f), Size(100f, 32f), Size(1000f, 700f), Offset(500f, 120f))
        assertTrue(f.mainTearOff.takeOverOutside(pulled))
        val torn = assertNotNull(f.world.carrierHostId)
        // The main window now draws an empty canvas, its placeholder group the tree's own.
        f.mainDrag.forgetAllBounds()
        f.mainDrag.registerPane("g1", Rect(0f, 0f, 1000f, 700f))
        f.mainDrag.registerEmptyPane("g1", Rect(0f, 0f, 1000f, 700f), stripHeight = 32f)

        // Pulled down into the canvas the tab came out of: it stays out, its window following.
        f.world.move(Offset(600f, 400f))
        assertNull(f.world.overHostId)
        assertFalse(torn in f.platform.hidden)

        // Back up where the strip was: in again.
        f.world.move(Offset(600f, 116f))
        assertEquals("main", f.world.overHostId)
        assertTrue(torn in f.platform.hidden)

        // Pulled away from there again — still inside the main window — and out again.
        f.world.move(Offset(600f, 300f))
        assertNull(f.world.overHostId)
        assertFalse(torn in f.platform.hidden)

        f.world.move(Offset(600f, 116f))
        f.world.release(Offset(600f, 116f))
        assertEquals(LayoutNode.Group("g1", listOf("a"), "a"), f.layout.tree)
        assertTrue(f.windows.registry.extras().isEmpty())
        assertEquals(listOf("a"), collectViewIds(f.windows.layoutFor("main", f.layout.tree)))
    }

    @Test
    fun `positions convert through each window's own scale`() {
        val f = Fixture(scale = 2f)
        // Root px (1000, -80) on a 2x display is screen (600, 60): the same place as at 1x.
        assertTrue(f.mainTearOff.takeOverOutside(f.handoffB(pointer = Offset(1000f, -80f), scale = 2f)))
        assertEquals(WindowBounds(570f, 50f, 1000f, 700f), f.windows.registry.extras().single().bounds)
    }
}

// ── Fixture ─────────────────────────────────────────────────────────────────────────────

private class FakeWindow(var origin: Offset, var size: Size, val scale: Float)

private class FakePlatform : TearOffPlatform {
    val windows = linkedMapOf<String, FakeWindow>()
    /** Front-most first. */
    val order = mutableListOf<String>()
    val hidden = mutableSetOf<String>()
    val focused = mutableListOf<String>()
    val sentBack = mutableListOf<String>()
    val following = mutableSetOf<String>()
    var live = false
    var newWindowTab = Offset.Zero

    private fun shown(hostId: String) = windows[hostId]?.takeIf { hostId !in hidden }

    override fun contentOrigin(hostId: String) = shown(hostId)?.origin
    override fun contentSize(hostId: String) = shown(hostId)?.size
    override fun windowFrame(hostId: String) = shown(hostId)?.let { Rect(it.origin, it.size) }
    override fun scale(hostId: String) = windows[hostId]?.scale
    override fun frontToBack() = order.filter { shown(it) != null }

    override fun moveContentTo(hostId: String, contentTopLeft: Offset): Boolean {
        val w = windows[hostId] ?: return false
        w.origin = contentTopLeft
        return true
    }

    override fun frameForContent(contentTopLeft: Offset, contentSize: Size) =
        WindowBounds(contentTopLeft.x, contentTopLeft.y, contentSize.width, contentSize.height)

    override fun newWindowTabOffset() = newWindowTab

    override fun setHidden(hostId: String, hidden: Boolean) {
        if (hidden) this.hidden += hostId else this.hidden -= hostId
    }

    override fun sendToBack(hostId: String) {
        order.remove(hostId)
        order.add(hostId)
        sentBack += hostId
    }

    override fun bringToFront(hostId: String) {
        order.remove(hostId)
        order.add(0, hostId)
    }

    override fun focus(hostId: String) {
        focused += hostId
        bringToFront(hostId)
    }

    override fun liveDragStarted() {
        live = true
    }

    override fun liveDragEnded() {
        live = false
    }

    override fun setFollowing(hostId: String, following: Boolean) {
        if (following) this.following += hostId else this.following -= hostId
    }
}

/**
 * A main window at screen (100, 100), 1000 × 700, showing group g1 [a, b, c] — strip across its
 * top, its tabs 100 px wide. After a tear-off of b, its strip shows a and c.
 */
private class Fixture(
    tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "a"),
    val scale: Float = 1f,
) {
    val windows = RegistryShellWindows().apply { setWorkspaceOnMain("ws") }
    val layout = WorkspaceLayoutState(tree)
    val platform = FakePlatform()
    val world = TabDragWorld(windows.registry, platform)
    val tearOffs = mutableMapOf<String, TabTearOff>()

    val mainDrag = addWindow("main", "ws", Offset(100f, 100f), Size(1000f, 700f), scale).apply {
        registerStrip("g1", Rect(0f, 0f, 1000f * scale, 32f * scale))
        registerPane("g1", Rect(0f, 32f * scale, 1000f * scale, 700f * scale))
        // As the strip draws once b has left it.
        registerTab("g1", "a", Rect(0f, 0f, 100f * scale, 32f * scale))
        registerTab("g1", "c", Rect(100f * scale, 0f, 200f * scale, 32f * scale))
    }
    val mainTearOff: TabTearOff get() = tearOffs.getValue("main")

    /** Between a and c on the main window's strip. */
    val overMainStrip = Offset(100f + 140f, 100f + 16f)

    /** A window on screen with a pane layer registered for live tear-off; returns its drag controller. */
    fun addWindow(hostId: String, workspaceId: String, origin: Offset, size: Size, scale: Float = 1f): PaneDragController {
        platform.windows[hostId] = FakeWindow(origin, size, scale)
        platform.order.add(0, hostId)
        val drag = PaneDragController()
        val tearOff = world.tearOffFor(hostId, layout, workspaceId)
        tearOff.register(endpoint(hostId, drag))
        tearOffs[hostId] = tearOff
        return drag
    }

    /** What PaneHost would build for window [hostId]: drops and restores through the layout, then the claim moves. */
    fun endpoint(hostId: String, drag: PaneDragController) = TabDropEndpoint(
        drag = drag,
        onDrop = { target ->
            applyPaneDrop(target, { layout.edit(it) }, { windows.transfer(it, hostId, layout.tree) }, null)
        },
        onDocked = { windows.transfer(it, hostId, layout.tree) },
        onRestore = { viewId, groupId, index, active ->
            layout.edit { restoreTab(it, viewId, groupId, index, active) }
            windows.transfer(viewId, hostId, layout.tree)
        },
    )

    fun handoffB(pointer: Offset, scale: Float = 1f) = TabHandoff(
        viewId = "b",
        label = "b",
        groupId = "g1",
        index = 1,
        activeViewId = "a",
        grab = Offset(30f, 10f) * scale,
        tabSize = Size(100f, 32f) * scale,
        groupSize = Size(1000f, 700f) * scale,
        pointer = pointer,
    )

    /**
     * Tear b off from just above the main window, then let its window come on screen as `Main.kt`
     * would, its pane layer showing b at the top-left. Returns the new window's host id.
     */
    fun tearOffB(): String {
        assertTrue(mainTearOff.takeOverOutside(handoffB(pointer = Offset(500f, -40f))))
        val host = windows.registry.extras().single()
        val torn = host.id
        platform.windows[torn] = FakeWindow(Offset(host.bounds.x, host.bounds.y), Size(host.bounds.width, host.bounds.height), 1f)
        platform.order.add(0, torn)
        val drag = PaneDragController()
        drag.registerTab(groupIdOf(layout.tree, "b")!!, "b", Rect(0f, 0f, 100f, 32f))
        val tearOff = world.tearOffFor(torn, layout, "ws")
        tearOff.register(endpoint(torn, drag))
        tearOffs[torn] = tearOff
        return torn
    }

    // A one-tab extra window "solo" at screen (1200, 100) holding c, its group g2.
    fun soloTearOff(): TabTearOff = tearOffs.getValue("solo")

    fun handoffC() = TabHandoff("c", "c", "g2", 0, "c", Offset(20f, 10f), Size(100f, 32f), Size(400f, 300f), Offset(40f, 20f))

    companion object {
        fun withSoloWindow(): Fixture {
            val tree = LayoutNode.Split(
                "row",
                listOf(0.5, 0.5),
                listOf(LayoutNode.Group("g1", listOf("a", "b"), "a"), LayoutNode.Group("g2", listOf("c"), "c")),
            )
            val f = Fixture(tree)
            assertNotNull(f.windows.registry.tryClaim("ws", setOf("c"), WindowBounds(1200f, 100f, 400f, 300f), "solo", tree))
            f.mainDrag.forgetAllBounds()
            f.mainDrag.registerStrip("g1", Rect(0f, 0f, 1000f, 32f))
            f.mainDrag.registerPane("g1", Rect(0f, 32f, 1000f, 700f))
            f.mainDrag.registerTab("g1", "a", Rect(0f, 0f, 100f, 32f))
            f.mainDrag.registerTab("g1", "b", Rect(100f, 0f, 200f, 32f))
            val solo = f.addWindow("solo", "ws", Offset(1200f, 100f), Size(400f, 300f))
            solo.registerStrip("g2", Rect(0f, 0f, 400f, 32f))
            solo.registerTab("g2", "c", Rect(0f, 0f, 100f, 32f))
            return f
        }
    }
}
