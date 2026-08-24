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
    fun claimSucceedsAndPartitionsLayout() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val extra = r.tryClaim("ws", setOf("v2"), bounds, "extra-1")
        assertNotNull(extra)
        assertEquals(setOf("v2"), extra.claimedViewIds)
        assertEquals(setOf("v2"), r.claimedUnion("ws"))
        assertEquals(listOf(extra), r.extras("ws"))

        val t = tree()
        val mainLayout = r.layoutFor(r.main(), t)
        assertEquals(hideClaimed(t, setOf("v2")), mainLayout)
        assertEquals(subtreeCovering(t, setOf("v2")), r.layoutFor(extra, t))
    }

    @Test
    fun overlappingClaimRejectedRegistryUnchanged() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        assertNotNull(r.tryClaim("ws", setOf("v1", "v2"), bounds, "a"))
        val before = r.claimedUnion("ws")
        assertNull(r.tryClaim("ws", setOf("v2", "v3"), bounds, "b"))
        assertEquals(before, r.claimedUnion("ws"))
        assertEquals(1, r.extras("ws").size)
    }

    @Test
    fun wholeCanvasAndExtrasAreMutuallyExclusive() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        assertNotNull(r.tryClaim("ws", setOf("v1"), bounds, "e1"))
        assertNull(r.tryClaimCanvas("ws", bounds, "canvas"))

        val r2 = WindowHostRegistry()
        r2.setWorkspaceOnMain("ws")
        assertNotNull(r2.tryClaimCanvas("ws", bounds, "canvas"))
        assertNull(r2.tryClaim("ws", setOf("v1"), bounds, "e1"))
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
        assertNull(r.tryClaim("ws", emptySet(), bounds, "x"))
    }

    @Test
    fun rebaseDropsMissingIdsClosesEmptyAndUnclaimsDisconnected() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val extra = r.tryClaim("ws", setOf("v1", "v2"), bounds, "e")
        assertNotNull(extra)

        val onlyV1 = LayoutNode.Group("g1", listOf("v1"), "v1")
        r.rebase("ws", onlyV1)
        assertEquals(setOf("v1"), r.extras("ws").single().claimedViewIds)

        r.rebase("ws", LayoutNode.Group("g2", listOf("v3"), "v3"))
        assertTrue(r.extras("ws").isEmpty())

        val r2 = WindowHostRegistry()
        r2.setWorkspaceOnMain("ws")
        // Diagonal claim: v1 and v3 live in different children, no covering subtree.
        val diagonal = r2.tryClaim("ws", setOf("v1", "v3"), bounds, "diag")
        assertNotNull(diagonal)
        r2.rebase("ws", tree())
        assertTrue(r2.extras("ws").isEmpty())
    }

    @Test
    fun unclaimReturnsIdsToMain() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val extra = r.tryClaim("ws", setOf("v2"), bounds, "e")
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
}
