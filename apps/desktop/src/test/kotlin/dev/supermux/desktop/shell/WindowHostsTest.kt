package dev.supermux.desktop.shell

import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.hideClaimed
import dev.supermux.workspace.splitGroup
import dev.supermux.workspace.subtreeCovering
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowHostsTest {
    private val bounds = WindowBounds(0f, 0f, 800f, 600f)

    private fun tree(): LayoutNode = LayoutNode.Split(
        "row",
        listOf(0.5, 0.5),
        listOf(
            LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
            LayoutNode.Group("g2", listOf("v3"), "v3"),
        ),
    )

    @Test
    fun claimPartialGroupRejected() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        assertNull(r.tryClaim("ws", setOf("v2"), bounds, "extra-1", tree()))
        assertTrue(r.extras("ws").isEmpty())
    }

    @Test
    fun claimSucceedsAndPartitionsLayout() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = tree()
        val extra = r.tryClaim("ws", setOf("v1", "v2"), bounds, "extra-1", t)
        assertNotNull(extra)
        assertEquals(setOf("v1", "v2"), extra.claimedViewIds)
        assertEquals(setOf("v1", "v2"), r.claimedUnion("ws"))
        assertEquals(listOf(extra), r.extras("ws"))
        assertEquals(listOf(extra), r.extras())

        val mainLayout = r.layoutFor(r.main(), t)
        assertEquals(hideClaimed(t, setOf("v1", "v2")), mainLayout)
        assertEquals(subtreeCovering(t, setOf("v1", "v2")), r.layoutFor(extra, t))
    }

    @Test
    fun claimAfterPlanTearOutTab() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = tree()
        val plan = planTearOutTab(t, "v2", "g-new")
        assertNotNull(plan)
        val next = plan.first(t)
        val extra = r.tryClaim("ws", plan.second, bounds, "extra-1", next)
        assertNotNull(extra)
        assertEquals(setOf("v2"), extra.claimedViewIds)
        assertEquals(subtreeCovering(next, setOf("v2")), r.layoutFor(extra, next))
        assertEquals(hideClaimed(next, setOf("v2")), r.layoutFor(r.main(), next))
    }

    @Test
    fun overlappingClaimRejectedRegistryUnchanged() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = tree()
        assertNotNull(r.tryClaim("ws", setOf("v1", "v2"), bounds, "a", t))
        val before = r.claimedUnion("ws")
        assertNull(r.tryClaim("ws", setOf("v2", "v3"), bounds, "b", t))
        assertEquals(before, r.claimedUnion("ws"))
        assertEquals(1, r.extras("ws").size)
    }

    @Test
    fun wholeCanvasAndExtrasAreMutuallyExclusive() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        assertNotNull(r.tryClaim("ws", setOf("v3"), bounds, "e1", tree()))
        assertNull(r.tryClaimCanvas("ws", bounds, "canvas"))

        val r2 = WindowHostRegistry()
        r2.setWorkspaceOnMain("ws")
        assertNotNull(r2.tryClaimCanvas("ws", bounds, "canvas"))
        assertNull(r2.tryClaim("ws", setOf("v3"), bounds, "e1", tree()))
        assertNull(r2.tryClaimCanvas("ws", bounds, "canvas-2"))
        assertEquals(1, r2.extras("ws").size)
        val canvas = r2.extras("ws").single()
        val t = tree()
        assertEquals(t, r2.layoutFor(canvas, t))
        assertNull(r2.layoutFor(r2.main(), t))
    }

    @Test
    fun tryClaimRejectsEmptyViewIds() {
        val r = WindowHostRegistry()
        assertNull(r.tryClaim("ws", emptySet(), bounds, "x", tree()))
    }

    @Test
    fun rebaseDropsMissingIdsClosesEmptyAndUnclaimsDisconnected() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val extra = r.tryClaim("ws", setOf("v1", "v2"), bounds, "e", tree())
        assertNotNull(extra)

        val onlyV1 = LayoutNode.Group("g1", listOf("v1"), "v1")
        r.rebase("ws", onlyV1)
        assertEquals(setOf("v1"), r.extras("ws").single().claimedViewIds)

        r.rebase("ws", LayoutNode.Group("g2", listOf("v3"), "v3"))
        assertTrue(r.extras("ws").isEmpty())

        val r2 = WindowHostRegistry()
        r2.setWorkspaceOnMain("ws")
        assertNotNull(r2.tryClaim("ws", setOf("v1", "v2"), bounds, "diag", tree()))
        // Both ids still live but sit on a diagonal of a larger tree — no covering node.
        r2.rebase(
            "ws",
            LayoutNode.Split(
                "row",
                listOf(0.5, 0.5),
                listOf(
                    LayoutNode.Group("g1", listOf("v1"), "v1"),
                    LayoutNode.Split(
                        "column",
                        listOf(0.5, 0.5),
                        listOf(
                            LayoutNode.Group("g2", listOf("v2"), "v2"),
                            LayoutNode.Group("g3", listOf("v3"), "v3"),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(r2.extras("ws").isEmpty())
    }

    @Test
    fun rebaseOverlappingExtrasFirstKeepsLaterDrops() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = tree()
        assertNotNull(r.tryClaim("ws", setOf("v1", "v2"), bounds, "first", t))
        assertNotNull(r.tryClaim("ws", setOf("v3"), bounds, "second", t))
        // Both extras still hold live ids that now sit in one group together with
        // each other — remaining sets overlap on v1 after the tree collapses.
        r.rebase("ws", LayoutNode.Group("g", listOf("v1"), "v1"))
        val extras = r.extras("ws")
        assertEquals(listOf("first"), extras.map { it.id })
        assertEquals(setOf("v1"), extras.single().claimedViewIds)
    }

    @Test
    fun layoutForMainDoesNotHideOrphanClaims() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = tree()
        assertNotNull(r.tryClaim("ws", setOf("v1", "v2"), bounds, "e", t))
        val other = LayoutNode.Group("g1", listOf("v1", "v3"), "v1")
        assertEquals(other, r.layoutFor(r.main(), other))
    }

    @Test
    fun layoutForUnknownHostIsNull() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val ghost = WindowHost("ghost", "ws", setOf("v3"), bounds, isMain = false)
        assertNull(r.layoutFor(ghost, tree()))
    }

    @Test
    fun unclaimReturnsIdsToMain() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val extra = r.tryClaim("ws", setOf("v3"), bounds, "e", tree())
        assertNotNull(extra)
        r.unclaim(extra.id)
        assertTrue(r.extras("ws").isEmpty())
        val t = tree()
        assertEquals(t, r.layoutFor(r.main(), t))
        r.unclaim(r.main().id)
        assertEquals("main", r.main().id)
    }

    @Test
    fun planTearOutTabSplitsMultiViewGroup() {
        val t = LayoutNode.Group("g1", listOf("v1", "v2"), "v1")
        val plan = planTearOutTab(t, "v2", "g-new")
        assertNotNull(plan)
        val (transform, claim) = plan
        assertEquals(setOf("v2"), claim)
        val next = transform(t)
        assertEquals(splitGroup(t, "g1", "v2", "row", "g-new"), next)
    }

    @Test
    fun planTearOutTabSingleViewIsIdentity() {
        val t = LayoutNode.Group("g1", listOf("v1"), "v1")
        val plan = planTearOutTab(t, "v1", "g-new")
        assertNotNull(plan)
        val (transform, claim) = plan
        assertEquals(setOf("v1"), claim)
        assertEquals(t, transform(t))
    }

    @Test
    fun planTearOutTabUnknownViewIsNull() {
        val t = LayoutNode.Group("g1", listOf("v1"), "v1")
        assertNull(planTearOutTab(t, "missing", "g-new"))
    }

    @Test
    fun tearOutTabTwoTabGroupYieldsExtraHostAndMainKeepsTheOther() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = LayoutNode.Group("g1", listOf("v1", "v2"), "v1")
        var applied: LayoutNode? = null
        val extra = tearOutTab(
            registry = r,
            tree = t,
            viewId = "v2",
            workspaceId = "ws",
            newGroupId = "g-new",
            bounds = bounds,
            hostId = "extra-1",
            edit = { next -> applied = next; next },
        )
        assertNotNull(extra)
        assertEquals(setOf("v2"), extra.claimedViewIds)
        val next = applied!!
        assertEquals(subtreeCovering(next, setOf("v2")), r.layoutFor(extra, next))
        assertEquals(hideClaimed(next, setOf("v2")), r.layoutFor(r.main(), next))
        assertEquals(setOf("v1"), collectViewIds(r.layoutFor(r.main(), next)!!).toSet())
    }

    @Test
    fun tearOutTabUnknownViewIsNull() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = LayoutNode.Group("g1", listOf("v1"), "v1")
        assertNull(
            tearOutTab(r, t, "missing", "ws", "g-new", bounds, "extra-1") { it },
        )
        assertTrue(r.extras("ws").isEmpty())
    }

    @Test
    fun tearOutGroupClaimsWholeGroup() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = tree()
        val extra = tearOutGroup(r, t, "g1", "ws", bounds, "extra-g")
        assertNotNull(extra)
        assertEquals(setOf("v1", "v2"), extra.claimedViewIds)
        assertEquals(hideClaimed(t, setOf("v1", "v2")), r.layoutFor(r.main(), t))
    }

    @Test
    fun tearOutGroupUnknownIsNull() {
        val r = WindowHostRegistry()
        assertNull(tearOutGroup(r, tree(), "missing", "ws", bounds, "x"))
    }

    @Test
    fun tearOutCanvasClaimsWholeWorkspace() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val extra = tearOutCanvas(r, "ws", bounds, "canvas")
        assertNotNull(extra)
        assertTrue(extra.claimedViewIds.isEmpty())
        assertNull(r.layoutFor(r.main(), tree()))
        assertEquals(tree(), r.layoutFor(extra, tree()))
    }
}
