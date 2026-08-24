package dev.supermux.desktop.shell

import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.firstGroupId
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

    @Test
    fun dragEndedOutsideIsTrueWhenPointerMissesEveryWindow() {
        val windows = listOf(
            WindowBounds(0f, 0f, 100f, 100f),
            WindowBounds(200f, 0f, 100f, 100f),
        )
        assertTrue(dragEndedOutside(150f, 50f, windows))
        assertTrue(dragEndedOutside(-1f, 0f, emptyList()))
    }

    @Test
    fun dragEndedOutsideIsFalseWhenPointerHitsAWindow() {
        val windows = listOf(
            WindowBounds(0f, 0f, 100f, 100f),
            WindowBounds(200f, 0f, 100f, 100f),
        )
        assertTrue(!dragEndedOutside(50f, 50f, windows))
        assertTrue(!dragEndedOutside(200f, 0f, windows))
        assertTrue(!dragEndedOutside(299.9f, 99.9f, windows))
    }

    @Test
    fun transferExtraToMainDropsTheClaim() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = tree()
        assertNotNull(r.tryClaim("ws", setOf("v3"), bounds, "e", t))
        assertTrue(r.transfer("v3", r.main().id, t))
        assertTrue(r.extras("ws").isEmpty())
        assertEquals(t, r.layoutFor(r.main(), t))
    }

    @Test
    fun transferExtraToExtraMovesTheViewClaim() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = tree()
        assertNotNull(r.tryClaim("ws", setOf("v1", "v2"), bounds, "a", t))
        assertNotNull(r.tryClaim("ws", setOf("v3"), bounds, "b", t))
        val next = LayoutNode.Split(
            "row",
            listOf(0.5, 0.5),
            listOf(
                LayoutNode.Group("g1", listOf("v1"), "v1"),
                LayoutNode.Group("g2", listOf("v3", "v2"), "v3"),
            ),
        )
        assertTrue(r.transfer("v2", "b", next))
        assertEquals(setOf("v1"), r.extras("ws").first { it.id == "a" }.claimedViewIds)
        assertEquals(setOf("v3", "v2"), r.extras("ws").first { it.id == "b" }.claimedViewIds)
    }

    @Test
    fun emptyHostLayoutAddressesFirstGroup() {
        val t = tree()
        val placeholder = emptyHostLayout(t)
        assertEquals(LayoutNode.Group(firstGroupId(t)!!, emptyList(), null), placeholder)
    }

    @Test
    fun mergePersistedWindowHostsUnionsPendingWithLiveAndLiveWinsOnSameId() {
        val pending = listOf(
            PersistedWindowHost("other", "ws-b", listOf("v9"), 1f, 2f, 3f, 4f),
            PersistedWindowHost("live-id", "ws-a", listOf("old"), 0f, 0f, 10f, 10f),
        )
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws-a")
        assertNotNull(r.tryClaim("ws-a", setOf("v3"), bounds, "live-id", tree()))
        val merged = mergePersistedWindowHosts(pending, r.extras())
        assertEquals(2, merged.size)
        val other = merged.single { it.id == "other" }
        assertEquals("ws-b", other.workspaceId)
        val live = merged.single { it.id == "live-id" }
        assertEquals(listOf("v3"), live.claimedViewIds)
        assertEquals(bounds.width, live.width)
    }

    @Test
    fun tryRestoreKeepsUnrestoredHostsInPending() {
        val ui = ShellUiState()
        ui.pendingWindowHosts = listOf(
            PersistedWindowHost("a", "ws", listOf("v1", "v2"), 0f, 0f, 100f, 100f),
            PersistedWindowHost("b", "other", listOf("v9"), 1f, 1f, 100f, 100f),
            PersistedWindowHost("bad", "ws", listOf("v2"), 0f, 0f, 100f, 100f),
        )
        ui.tryRestoreWindowHosts("ws", tree())
        assertEquals(1, ui.windowHosts.extras("ws").size)
        assertEquals("a", ui.windowHosts.extras("ws").single().id)
        assertEquals(setOf("b", "bad"), ui.pendingWindowHosts.map { it.id }.toSet())
    }

    @Test
    fun tearOutTabFromTwoTabExtraYieldsTwoCoveringExtras() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = LayoutNode.Group("g1", listOf("v1", "v2"), "v1")
        assertNotNull(r.tryClaim("ws", setOf("v1", "v2"), bounds, "extra-src", t))
        var applied: LayoutNode? = null
        val extra = tearOutTab(
            registry = r,
            tree = t,
            viewId = "v2",
            workspaceId = "ws",
            newGroupId = "g-new",
            bounds = bounds,
            hostId = "extra-2",
            edit = { next -> applied = next; next },
        )
        assertNotNull(extra)
        val next = applied!!
        val extras = r.extras("ws")
        assertEquals(2, extras.size)
        assertEquals(setOf("v1"), extras.single { it.id == "extra-src" }.claimedViewIds)
        assertEquals(setOf("v2"), extras.single { it.id == "extra-2" }.claimedViewIds)
        assertNotNull(subtreeCovering(next, setOf("v1")))
        assertNotNull(subtreeCovering(next, setOf("v2")))
        assertEquals(subtreeCovering(next, setOf("v2")), r.layoutFor(extra, next))
        assertEquals(subtreeCovering(next, setOf("v1")), r.layoutFor(extras.single { it.id == "extra-src" }, next))
    }

    @Test
    fun tearOutTabFromSingletonExtraIsNoOp() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val t = LayoutNode.Group("g1", listOf("v1"), "v1")
        assertNotNull(r.tryClaim("ws", setOf("v1"), bounds, "extra-src", t))
        assertNull(
            tearOutTab(r, t, "v1", "ws", "g-new", bounds, "extra-2") { it },
        )
        assertEquals(1, r.extras("ws").size)
        assertEquals("extra-src", r.extras("ws").single().id)
    }

    @Test
    fun updateBoundsWritesExtraHostBounds() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        assertNotNull(r.tryClaim("ws", setOf("v3"), bounds, "e", tree()))
        val next = WindowBounds(40f, 50f, 640f, 480f)
        r.updateBounds("e", next)
        assertEquals(next, r.extras("ws").single().bounds)
        assertEquals(next, r.extras("ws").single().toPersisted().let {
            WindowBounds(it.x, it.y, it.width, it.height)
        })
    }

    @Test
    fun extraWindowTitleUsesWorkspaceAndActiveView() {
        val hosted = LayoutNode.Group("g1", listOf("v1", "v2"), "v2")
        val views = mapOf(
            "v1" to ViewDto("v1", "ws", "chat", title = "Chat A"),
            "v2" to ViewDto("v2", "ws", "editor", title = "Main.kt"),
        )
        assertEquals("Alpha — Main.kt", extraWindowTitle("Alpha", hosted, views))
        assertEquals("Alpha", extraWindowTitle("Alpha", null, views))
    }

    @Test
    fun expandClaimGrowsExtraToCoverASplitOffFile() {
        val r = WindowHostRegistry()
        r.setWorkspaceOnMain("ws")
        val before = LayoutNode.Group("g-tree", listOf("tree"), "tree")
        assertNotNull(r.tryClaim("ws", setOf("tree"), bounds, "extra", before))
        val after = LayoutNode.Split(
            "row",
            listOf(0.5, 0.5),
            listOf(
                LayoutNode.Group("g-tree", listOf("tree"), "tree"),
                LayoutNode.Group("g-file", listOf("f-new"), "f-new"),
            ),
        )
        assertTrue(r.expandClaim("extra", setOf("f-new"), after))
        assertEquals(setOf("tree", "f-new"), r.extras("ws").single().claimedViewIds)
        assertEquals(after, r.layoutFor(r.extras("ws").single(), after))
    }

    @Test
    fun workspaceIdsNeedingSessionKeepExtrasWhenSelectionChanges() {
        assertEquals(setOf("ws-a", "ws-b"), workspaceIdsNeedingSession("ws-b", listOf("ws-a")))
        assertEquals(setOf("ws-a"), workspaceIdsNeedingSession(null, listOf("ws-a")))
        assertEquals(setOf("ws-b"), workspaceIdsNeedingSession("ws-b", emptyList()))
        assertEquals(emptySet(), workspaceIdsNeedingSession(null, emptyList()))
    }
}
