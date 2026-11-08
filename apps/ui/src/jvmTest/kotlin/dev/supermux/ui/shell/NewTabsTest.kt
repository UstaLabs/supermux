package dev.supermux.ui.shell

import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.addViewToGroup
import dev.supermux.workspace.normalizeLayout
import dev.supermux.workspace.reorderWithinGroup
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.setSplitSizes
import dev.supermux.workspace.splitGroup
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewTabsTest {
    private val nt = NEW_TAB_PREFIX + "1"
    private val real = LayoutNode.Group("g", listOf("a", "b"), "b")
    private val open = mapOf(nt to NewTabSlot("g", Int.MAX_VALUE, selected = true, shownOver = "b"))

    /** What WorkspacePanes does with an edit PaneHost made on the drawn tree. */
    private fun route(edit: (LayoutNode) -> LayoutNode, tree: LayoutNode, slots: Map<String, NewTabSlot>): Pair<LayoutNode, Map<String, NewTabSlot>> {
        val routed = newTabEdit(tree, slots, edit) ?: return tree to slots
        return (normalizeLayout(routed.onReal(tree)) ?: tree) to slots + routed.slots
    }

    /** What NewTabUpkeep does once the broker has made [view] for New tab [nt] in [tree]. */
    private fun pick(tree: LayoutNode, slots: Map<String, NewTabSlot>, nt: String, view: String, from: Pair<String, String?>?): Pair<LayoutNode, Map<String, NewTabSlot>> {
        val picked = newTabPicked(tree, slots, nt, view, from)!!
        return picked.onReal(tree) to slots - nt + picked.slots
    }

    // ── A New tab in a strip ───────────────────────────────────────────────────────────────────────

    @Test fun aNewTabIsDrawnLastAndOnShow() {
        assertEquals(LayoutNode.Group("g", listOf("a", "b", nt), nt), withNewTabs(real, open))
    }

    @Test fun takingItOutGivesBackTheRealTree() {
        assertEquals(real, withoutNewTabs(withNewTabs(real, open), real))
    }

    @Test fun plusAgainShowsTheNewTabAlreadyThere() {
        val state = NewTabsState()
        state.open("g", "b")
        state.slots[state.slots.keys.single()] = state.slots.values.single().copy(selected = false)
        state.open("g", "b")
        assertEquals(1, state.slots.size)
        assertTrue(state.slots.values.single().selected)
    }

    @Test fun clickingARealTabLeavesTheNewTabInTheStripUnselected() {
        val (tree, slots) = route({ setActiveViewInGroup(it, "g", "a") }, real, open)
        assertEquals(LayoutNode.Group("g", listOf("a", "b"), "a"), tree)
        assertEquals(LayoutNode.Group("g", listOf("a", "b", nt), "a"), withNewTabs(tree, slots))
    }

    @Test fun clickingTheNewTabAgainShowsItWithoutTouchingTheRealTree() {
        val (t1, s1) = route({ setActiveViewInGroup(it, "g", "a") }, real, open)
        val (t2, s2) = route({ setActiveViewInGroup(it, "g", nt) }, t1, s1)
        assertEquals(t1, t2)
        assertEquals(nt, (withNewTabs(t2, s2) as LayoutNode.Group).activeViewId)
    }

    @Test fun draggingTheNewTabFirstMovesOnlyIt() {
        val (tree, slots) = route({ reorderWithinGroup(it, "g", nt, 0) }, real, open)
        assertEquals(real, tree)
        assertEquals(listOf(nt, "a", "b"), (withNewTabs(tree, slots) as LayoutNode.Group).viewIds)
    }

    @Test fun aRealTabDraggedPastTheNewTabLandsThereInTheRealTreeToo() {
        val (tree, slots) = route({ reorderWithinGroup(it, "g", "a", 2) }, real, open)
        assertEquals(listOf("b", "a"), (tree as LayoutNode.Group).viewIds)
        assertEquals(listOf("b", nt, "a"), (withNewTabs(tree, slots) as LayoutNode.Group).viewIds)
    }

    @Test fun theNewTabCannotMakeAPaneOfItsOwn() {
        val (tree, slots) = route({ splitGroup(it, "g", nt, "row", "g2") }, real, open)
        assertEquals(real, tree)
        assertEquals(open, slots)
    }

    @Test fun aRealTabSplitOffLeavingTheNewTabAloneIsStillMade() {
        val first = mapOf(nt to NewTabSlot("g", 0, selected = false))
        val one = LayoutNode.Group("g", listOf("a"), "a")
        val (tree, _) = route({ splitGroup(it, "g", "a", "row", "g2") }, one, first)
        assertEquals(LayoutNode.Group("g2", listOf("a"), "a"), tree)
    }

    @Test fun anotherTabMadeActiveByTheBrokerShowsInsteadOfTheNewTab() {
        val brokerMoved = LayoutNode.Group("g", listOf("a", "b", "c"), "c")
        assertEquals("c", (withNewTabs(brokerMoved, open) as LayoutNode.Group).activeViewId)
    }

    // ── The workspace's split buttons ──────────────────────────────────────────────────────────────

    private val p = LayoutNode.Group("p", listOf("x"), "x")

    @Test fun splitRightOnOnePaneMakesTwoColumns() {
        assertEquals(LayoutNode.Split("row", listOf(0.5, 0.5), listOf(real, p)), withPaneAtEdge(real, "row", p))
    }

    @Test fun splitRightOnColumnsAddsTheRightmostAndKeepsTheirProportions() {
        val cols = LayoutNode.Split("row", listOf(0.6, 0.4), listOf(LayoutNode.Group("a", listOf("1")), LayoutNode.Group("b", listOf("2"))))
        val out = withPaneAtEdge(cols, "row", p) as LayoutNode.Split
        assertEquals(listOf("a", "b", "p"), out.children.map { (it as LayoutNode.Group).id })
        assertEquals(listOf(0.4, 0.8 / 3, 1.0 / 3).map { Math.round(it * 1e6) }, out.sizes.map { Math.round(it * 1e6) })
    }

    @Test fun splitDownUnderRowsAddsOneMoreFullWidthRow() {
        val rows = LayoutNode.Split("column", listOf(0.6, 0.4), listOf(LayoutNode.Group("t", listOf("1")), LayoutNode.Group("b", listOf("2"))))
        val out = withPaneAtEdge(rows, "column", p) as LayoutNode.Split
        assertEquals(listOf("t", "b", "p"), out.children.map { (it as LayoutNode.Group).id })
    }

    @Test fun theButtonsSitOnTheTopRightPane() {
        val two = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", listOf("a")), LayoutNode.Group("g2", listOf("b"))))
        assertEquals("g2", topRightGroupId(LayoutNode.Split("column", listOf(0.6, 0.4), listOf(two, LayoutNode.Group("g3", listOf("c"))))))
    }

    /** The workspace a sequence of split buttons draws, from one pane, before anything is picked. */
    private fun splits(vararg dirs: String): LayoutNode.Split {
        val state = NewTabsState()
        val start = LayoutNode.Group("g", listOf("a"), "a")
        for (d in dirs) state.splitWorkspace(d, start)
        return normalizeLayout(withNewTabs(start, state.slots)) as LayoutNode.Split
    }

    // Ahmet's bug (2026-11-07): right, right, down put the row under only part of the workspace.
    // The panes sit in a hash map keyed by random ids, so a wrong order shows up only for some ids:
    // each sequence runs many times.
    @Test fun rightRightDownPutsTheNewRowUnderAllThreeColumns() = repeat(40) {
        val root = splits("row", "row", "column")
        assertEquals("column", root.direction)
        val top = root.children[0] as LayoutNode.Split
        assertEquals("row", top.direction)
        assertEquals(3, top.children.size)
        assertEquals("g", (top.children[0] as LayoutNode.Group).id)
        assertTrue(root.children[1] is LayoutNode.Group)
    }

    @Test fun downThenRightPutsTheNewColumnBesideBothRows() = repeat(40) {
        val root = splits("column", "row")
        assertEquals("row", root.direction)
        assertEquals("column", (root.children[0] as LayoutNode.Split).direction)
        assertTrue(root.children[1] is LayoutNode.Group)
    }

    @Test fun rightDownRightEndsWithAFullHeightColumn() = repeat(40) {
        val root = splits("row", "column", "row")
        assertEquals("row", root.direction)
        val left = root.children[0] as LayoutNode.Split
        assertEquals("column", left.direction)
        assertEquals("row", (left.children[0] as LayoutNode.Split).direction)
        assertTrue(root.children[1] is LayoutNode.Group)
    }

    @Test fun downDownStacksTwoFullWidthRows() = repeat(40) {
        val root = splits("column", "column")
        assertEquals("column", root.direction)
        assertEquals(3, root.children.size)
        assertTrue(root.children.all { it is LayoutNode.Group })
    }

    // ── Picking: the view takes the place its New tab is drawn in ─────────────────────────────────

    /** Opens [dirs] from one pane, then picks each in [order] (indexes into the opening order). */
    private fun openThenPick(
        dirs: List<String>,
        order: List<Int>,
        start: LayoutNode = LayoutNode.Group("g", listOf("a"), "a"),
    ): Pair<LayoutNode, Map<String, NewTabSlot>> {
        val state = NewTabsState()
        var tree: LayoutNode = start
        for (d in dirs) state.splitWorkspace(d, tree)
        val ids = state.slots.entries.sortedBy { it.value.seq }.map { it.key }
        var slots = state.slots.toMap()
        for (i in order) {
            val nt = ids[i]
            val view = "v$i"
            // The broker puts the view in a real pane and shows it there.
            val target = topRightGroupId(tree)
            val before = groupActiveViewId(tree, target)
            val (t, s) = pick(addViewToGroup(tree, target, view), slots, nt, view, target to before)
            tree = t
            slots = s
        }
        return tree to slots
    }

    @Test fun picksInOpeningOrderEndAsTheyWereDrawn() = repeat(20) {
        val (tree, slots) = openThenPick(listOf("row", "row", "column"), listOf(0, 1, 2))
        assertTrue(slots.isEmpty())
        val root = tree as LayoutNode.Split
        assertEquals("column", root.direction)
        assertEquals(listOf(listOf("a"), listOf("v0"), listOf("v1")), (root.children[0] as LayoutNode.Split).children.map { (it as LayoutNode.Group).viewIds })
        assertEquals(listOf("v2"), (root.children[1] as LayoutNode.Group).viewIds)
    }

    @Test fun theLastPanePickedFirstKeepsTheFullWidthRow() = repeat(20) {
        val (tree, slots) = openThenPick(listOf("row", "row", "column"), listOf(2))
        assertEquals(2, slots.size)
        val drawn = normalizeLayout(withNewTabs(tree, slots)) as LayoutNode.Split
        assertEquals("column", drawn.direction)
        assertEquals(listOf("v2"), (drawn.children.last() as LayoutNode.Group).viewIds)
        assertEquals(3, groupIdsIn(drawn.children.first()).size)
    }

    // The broker shows a new view where it adds it; once the view has moved to its own pane, the
    // pane it was added to goes back to the tab it showed (b), not to its first one (a).
    @Test fun thePaneAPickWasAddedToShowsWhatItShowedBefore() {
        val (tree, _) = openThenPick(listOf("row"), listOf(0), start = LayoutNode.Group("g", listOf("a", "b"), "b"))
        assertEquals("b", groupActiveViewId(tree, "g"))
    }

    @Test fun aPickedTabLandsAtTheNewTabsIndexAndShows() {
        val slots = mapOf(nt to NewTabSlot("g", 1, selected = true, shownOver = "b"))
        val broker = LayoutNode.Group("g", listOf("a", "b", "c"), "c")
        assertEquals(LayoutNode.Group("g", listOf("a", "c", "b"), "c"), placeWhereDrawn(broker, slots, nt, "c"))
    }

    private fun groupIdsIn(node: LayoutNode): List<String> = when (node) {
        is LayoutNode.Group -> listOf(node.id)
        is LayoutNode.Split -> node.children.flatMap { groupIdsIn(it) }
    }

    // ── A pending pane resizes like any pane ──────────────────────────────────────────────────────
    // Ahmet (2026-11-08), on a pane split off before anything is picked in it: "that causes it not to
    // be able to resize". Its seam is dragged on the drawn tree, which the real tree has no split for.

    private fun sizes(node: LayoutNode) = (node as LayoutNode.Split).sizes.map { Math.round(it * 1e6) }

    private fun sizes(vararg s: Double) = s.map { Math.round(it * 1e6) }

    private val one = LayoutNode.Group("g", listOf("a"), "a")
    private val twoColumns = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", listOf("a"), "a"), LayoutNode.Group("g2", listOf("b"), "b")))

    private fun split(tree: LayoutNode, vararg dirs: String): Map<String, NewTabSlot> =
        NewTabsState().apply { for (d in dirs) splitWorkspace(d, tree) }.slots.toMap()

    private fun dragSeams(to: List<Double>, at: List<Int> = emptyList()): (LayoutNode) -> LayoutNode = { setSplitSizes(it, at, to) }

    @Test fun aSplitRightPaneKeepsTheSizeItsSeamIsDraggedTo() {
        val (tree, slots) = route(dragSeams(listOf(0.7, 0.3)), one, split(one, "row"))
        assertEquals(one, tree)
        assertEquals(sizes(0.7, 0.3), sizes(withNewTabs(tree, slots)))
    }

    @Test fun aSplitDownPaneKeepsTheSizeItsSeamIsDraggedTo() {
        val (tree, slots) = route(dragSeams(listOf(0.65, 0.35)), twoColumns, split(twoColumns, "column"))
        assertEquals(twoColumns, tree)
        assertEquals(sizes(0.65, 0.35), sizes(withNewTabs(tree, slots)))
    }

    @Test fun theRealPanesBesideAPendingOneKeepWhereTheirSeamIsDragged() {
        val (tree, slots) = route(dragSeams(listOf(0.5, 1.0 / 6, 1.0 / 3)), twoColumns, split(twoColumns, "row"))
        assertEquals(sizes(0.75, 0.25), sizes(tree))
        assertEquals(sizes(0.5, 1.0 / 6, 1.0 / 3), sizes(withNewTabs(tree, slots)))
    }

    @Test fun twoPendingPanesKeepTheSizesTheirSeamsAreDraggedTo() = repeat(20) {
        val (tree, slots) = route(dragSeams(listOf(0.5, 0.2, 0.3)), one, split(one, "row", "row"))
        assertEquals(one, tree)
        assertEquals(sizes(0.5, 0.2, 0.3), sizes(withNewTabs(tree, slots)))
    }

    @Test fun aPickKeepsTheSizeThePaneWasDraggedTo() {
        val (dragged, slots) = route(dragSeams(listOf(0.7, 0.3)), one, split(one, "row"))
        val (tree, left) = pick(addViewToGroup(dragged, "g", "v"), slots, slots.keys.single(), "v", "g" to "a")
        assertTrue(left.isEmpty())
        assertEquals(sizes(0.7, 0.3), sizes(tree))
        assertEquals(listOf("v"), ((tree as LayoutNode.Split).children[1] as LayoutNode.Group).viewIds)
    }

    // Picking the later of two panes first anchors the earlier one to the pane before it: every pane
    // stays the size it is drawn at, the picked one included.
    @Test fun pickingTheLaterOfTwoColumnsFirstLeavesEveryPaneWhereItWas() = repeat(20) {
        val (dragged, slots) = route(dragSeams(listOf(0.5, 0.2, 0.3)), one, split(one, "row", "row"))
        val later = slots.entries.maxBy { it.value.seq }.key
        val (tree, left) = pick(addViewToGroup(dragged, "g", "v"), slots, later, "v", "g" to "a")
        assertEquals(sizes(0.7, 0.3), sizes(tree))
        val drawn = withNewTabs(tree, left) as LayoutNode.Split
        assertEquals(sizes(0.7, 0.3), sizes(drawn))
        assertEquals(sizes(0.5 / 0.7, 0.2 / 0.7), sizes(drawn.children[0]))
    }

    @Test fun pickingTheBottomRowFirstAfterRightThenDownLeavesEveryPaneWhereItWas() = repeat(20) {
        val slots0 = split(one, "row", "column")
        val (inner, slots1) = route(dragSeams(listOf(0.6, 0.4), at = listOf(0)), one, slots0)
        val (dragged, slots) = route(dragSeams(listOf(0.7, 0.3)), inner, slots1)
        val bottom = slots.entries.maxBy { it.value.seq }.key
        val (tree, left) = pick(addViewToGroup(dragged, "g", "v"), slots, bottom, "v", "g" to "a")
        assertEquals(sizes(0.7, 0.3), sizes(tree))
        val drawn = withNewTabs(tree, left) as LayoutNode.Split
        assertEquals(sizes(0.7, 0.3), sizes(drawn))
        assertEquals(sizes(0.6, 0.4), sizes(drawn.children[0]))
    }

    // ── What a pick does, and which New tabs go away ──────────────────────────────────────────────

    private val files = ViewDto("files", "w", "editor", state = buildJsonObject { put("mode", JsonPrimitive("tree")) })

    @Test fun filesAlreadyOpenIsASwitchToIt() {
        val tree = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(real, LayoutNode.Group("g2", listOf("files"), "files")))
        assertEquals(NewTabPick.SwitchTo("files", "g2"), newTabPick(NewViewKind.EDITOR, tree, mapOf("files" to files)))
    }

    @Test fun aTerminalIsAlwaysANewView() {
        val tree = LayoutNode.Group("g", listOf("files"), "files")
        assertEquals(NewTabPick.Add, newTabPick(NewViewKind.TERMINAL, tree, mapOf("files" to files)))
    }

    @Test fun aNewTabWhosePaneWentAwayCloses() {
        assertEquals(setOf(nt), newTabsToClose(LayoutNode.Group("other", listOf("z"), "z"), open, replaced = emptySet()))
    }

    @Test fun aWorkspaceSplitNeverClosesOnItsOwn() {
        val state = NewTabsState()
        state.splitWorkspace("row", real)
        assertTrue(newTabsToClose(LayoutNode.Group("other", listOf("z"), "z"), state.slots.toMap(), replaced = emptySet()).isEmpty())
    }

    // ── The rows ──────────────────────────────────────────────────────────────────────────────────

    private val ws = WorkspaceDto(id = "w", name = "Supermux PM", workdir = "/home/u/projects/supermux", repoRoot = "/home/u/projects/supermux", branch = "main")

    @Test fun everyKindGetsARowSayingWhatItIsFor() {
        val rows = newTabChoices(ws, real, emptyMap())
        assertEquals(listOf("Chat", "Terminal", "Files", "Changes", "Display"), rows.map { it.label })
        assertEquals("A shell in supermux", rows[1].detail)
    }

    @Test fun anOpenFilesRowSaysSo() {
        val tree = LayoutNode.Group("g", listOf("files"), "files")
        assertTrue(newTabChoices(ws, tree, mapOf("files" to files)).single { it.kind == NewViewKind.EDITOR }.alreadyOpen)
    }

    @Test fun thePhoneGetsItsOwnKinds() {
        assertEquals(phoneAddKinds(), newTabChoices(ws, real, emptyMap(), phoneAddKinds()).map { it.kind })
    }
}
