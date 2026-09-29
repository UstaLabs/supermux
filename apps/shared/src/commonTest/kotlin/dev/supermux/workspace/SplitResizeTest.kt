package dev.supermux.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [resizeSplitSeam]: dragging one seam must leave every other seam where it is on screen. */
class SplitResizeTest {

    private fun g(id: String): LayoutNode = LayoutNode.Group(id, listOf("v$id"), "v$id")

    /** Absolute positions (fractions of the root) of every seam along [dir], in tree order. */
    private fun seams(node: LayoutNode, dir: String, start: Double = 0.0, extent: Double = 1.0): List<Double> =
        when (node) {
            is LayoutNode.Group -> emptyList()
            is LayoutNode.Split -> {
                val out = mutableListOf<Double>()
                var pos = start
                node.children.forEachIndexed { i, c ->
                    val e = if (node.direction == dir) node.sizes[i] * extent else extent
                    val s = if (node.direction == dir) pos else start
                    out += seams(c, dir, s, e)
                    if (node.direction == dir) {
                        pos += e
                        if (i < node.children.lastIndex) out += pos
                    }
                }
                out
            }
        }

    private fun assertClose(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size, "seam count: $expected vs $actual")
        expected.zip(actual).forEach { (e, a) -> assertTrue(kotlin.math.abs(e - a) < 1e-9, "$expected vs $actual") }
    }

    @Test
    fun flat_split_moves_only_the_dragged_seam() {
        val l = LayoutNode.Split("row", listOf(0.25, 0.25, 0.5), listOf(g("a"), g("b"), g("c")))
        val r = resizeSplitSeam(l, 0, 0.1)
        assertClose(listOf(0.35, 0.5), seams(r, "row"))
        assertNull(validateLayout(r))
    }

    @Test
    fun nested_same_direction_seam_stays_put_when_the_outer_seam_moves() {
        // A | [B | C]  — seams at 0.5 and 0.75.
        val l = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(g("a"), LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("b"), g("c")))),
        )
        val r = resizeSplitSeam(l, 0, -0.2)
        assertClose(listOf(0.3, 0.75), seams(r, "row"))
        assertNull(validateLayout(r))
    }

    @Test
    fun nested_seam_on_the_left_side_stays_put_too() {
        // [A | B] | C — seams at 0.25 and 0.5.
        val l = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("a"), g("b"))), g("c")),
        )
        val r = resizeSplitSeam(l, 0, 0.2)
        assertClose(listOf(0.25, 0.7), seams(r, "row"))
        assertNull(validateLayout(r))
    }

    @Test
    fun seams_inside_a_perpendicular_split_are_walked_through() {
        // A | column[ row[B | C], D ] — the B|C seam at 0.75 must stay.
        val l = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                g("a"),
                LayoutNode.Split(
                    "column", listOf(0.5, 0.5),
                    listOf(LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("b"), g("c"))), g("d")),
                ),
            ),
        )
        val r = resizeSplitSeam(l, 0, 0.1)
        assertClose(listOf(0.6, 0.75), seams(r, "row"))
        assertNull(validateLayout(r))
    }

    @Test
    fun drag_clamps_at_the_nested_pane_that_absorbs_it() {
        // A | [B | C], B|C at 0.75: pushing the outer seam right stops short of 0.75.
        val l = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(g("a"), LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("b"), g("c")))),
        )
        val r = resizeSplitSeam(l, 0, 0.9)
        val s = seams(r, "row")
        assertTrue(s[0] < 0.75 && s[0] > 0.6, "outer seam clamped before B|C, got $s")
        assertClose(listOf(0.75), listOf(s[1]))
        assertNull(validateLayout(r))
    }

    @Test
    fun out_of_range_seam_changes_nothing() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("a"), g("b")))
        assertSame(l, resizeSplitSeam(l, 1, 0.1))
    }

    @Test
    fun splitSizesByPath_replays_to_the_same_tree() {
        val l = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(g("a"), LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("b"), g("c")))),
        )
        val r = resizeSplitSeam(l, 0, -0.2)
        val replayed = splitSizesByPath(r).fold(l as LayoutNode) { t, (p, s) -> setSplitSizes(t, p, s) }
        assertEquals(r, replayed)
        // Idempotent: replaying twice lands on the same tree.
        assertEquals(r, splitSizesByPath(r).fold(replayed) { t, (p, s) -> setSplitSizes(t, p, s) })
    }

    // --- resizeLayoutEdges: the whole pane area resized (window edge, sidebar) ---

    private fun extents(node: LayoutNode, extent: Double): List<Double> =
        (node as LayoutNode.Split).sizes.map { it * extent }

    @Test
    fun right_edge_growth_goes_to_the_right_most_pane() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("a"), g("b")))
        val r = resizeLayoutEdges(l, 1000.0, 1200.0, 0.0, 600.0, 600.0, 0.0, min = 100.0)
        assertClose(listOf(500.0, 700.0), extents(r, 1200.0))
        assertNull(validateLayout(r))
    }

    @Test
    fun left_edge_moving_in_shrinks_only_the_left_most_pane() {
        // Sidebar widened by 200: the area's left edge moved right.
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("a"), g("b")))
        val r = resizeLayoutEdges(l, 1000.0, 800.0, 200.0, 600.0, 600.0, 0.0, min = 100.0)
        assertClose(listOf(300.0, 500.0), extents(r, 800.0))
    }

    @Test
    fun a_shrink_past_the_minimum_cascades_inward() {
        val l = LayoutNode.Split("row", listOf(1.0 / 3, 1.0 / 3, 1.0 / 3), listOf(g("a"), g("b"), g("c")))
        val r = resizeLayoutEdges(l, 900.0, 400.0, 0.0, 600.0, 600.0, 0.0, min = 100.0)
        assertClose(listOf(200.0, 100.0, 100.0), extents(r, 400.0))
        assertNull(validateLayout(r))
    }

    @Test
    fun below_every_minimum_the_panes_are_squeezed_alike() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("a"), g("b")))
        val r = resizeLayoutEdges(l, 1000.0, 100.0, 0.0, 600.0, 600.0, 0.0, min = 100.0)
        assertClose(listOf(50.0, 50.0), extents(r, 100.0))
        assertNull(validateLayout(r))
    }

    @Test
    fun top_and_bottom_edges_resize_the_top_and_bottom_rows() {
        val l = LayoutNode.Split("column", listOf(0.5, 0.5), listOf(g("a"), g("b")))
        val bottom = resizeLayoutEdges(l, 800.0, 800.0, 0.0, 1000.0, 1200.0, 0.0, min = 100.0)
        assertClose(listOf(500.0, 700.0), extents(bottom, 1200.0))
        val top = resizeLayoutEdges(l, 800.0, 800.0, 0.0, 1000.0, 1200.0, -200.0, min = 100.0)
        assertClose(listOf(700.0, 500.0), extents(top, 1200.0))
    }

    @Test
    fun nested_seams_stay_put_on_a_window_resize() {
        // A | column[ row[B | C], D ] at 1000 wide: seams at 500 and 750.
        val l = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                g("a"),
                LayoutNode.Split(
                    "column", listOf(0.5, 0.5),
                    listOf(LayoutNode.Split("row", listOf(0.5, 0.5), listOf(g("b"), g("c"))), g("d")),
                ),
            ),
        )
        val r = resizeLayoutEdges(l, 1000.0, 1400.0, 0.0, 600.0, 600.0, 0.0, min = 100.0)
        assertClose(listOf(500.0 / 1400, 750.0 / 1400), seams(r, "row"))
        assertNull(validateLayout(r))
    }

    @Test
    fun an_unchanged_area_returns_an_equal_tree() {
        val l = LayoutNode.Split("row", listOf(0.3, 0.7), listOf(g("a"), g("b")))
        assertEquals(l, resizeLayoutEdges(l, 1000.0, 1000.0, 0.0, 600.0, 600.0, 0.0, min = 100.0))
    }
}
