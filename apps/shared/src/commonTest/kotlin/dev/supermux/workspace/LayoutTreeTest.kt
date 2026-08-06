package dev.supermux.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parity suite for [LayoutTree]. Mirrors the reference suite
 * src/core/workspace/layout-tree.test.ts case for case, including the exact
 * validation message strings — the broker rejects a layout with those messages
 * and the client must predict the same rejection before it sends one.
 *
 * Same precedent as PredictiveEchoTest and TerminalKeysTest: when you change one
 * side, change both, or the two drift and only production notices.
 */
class LayoutTreeTest {

    private fun group(id: String, viewIds: List<String>, activeViewId: String? = null): LayoutNode =
        LayoutNode.Group(id, viewIds, activeViewId ?: viewIds.firstOrNull())

    @Test
    fun singleViewLayout_makes_a_one_group_layout_with_the_view_active() {
        val l = singleViewLayout("g1", "v1")
        assertEquals(LayoutNode.Group("g1", listOf("v1"), "v1"), l)
        assertNull(validateLayout(l))
    }

    @Test
    fun collectViewIds_walks_the_whole_tree_in_document_order() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g2", listOf("v2", "v3"))))
        assertEquals(listOf("v1", "v2", "v3"), collectViewIds(l))
    }

    @Test
    fun validateLayout_rejects_a_duplicate_view_id() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g2", listOf("v1"))))
        assertEquals("duplicate view id: v1", validateLayout(l))
    }

    @Test
    fun validateLayout_rejects_an_empty_group() {
        assertEquals("empty group: g1", validateLayout(LayoutNode.Group("g1", emptyList(), null)))
    }

    @Test
    fun validateLayout_rejects_an_activeViewId_that_is_not_in_the_group() {
        assertEquals("activeViewId not in group g1: v9", validateLayout(LayoutNode.Group("g1", listOf("v1"), "v9")))
    }

    @Test
    fun validateLayout_rejects_a_split_whose_sizes_length_differs_from_its_children_length() {
        val l = LayoutNode.Split("row", listOf(1.0), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals("split sizes length 1 does not match children length 2", validateLayout(l))
    }

    @Test
    fun validateLayout_rejects_a_split_with_fewer_than_two_children() {
        val l = LayoutNode.Split("row", listOf(1.0), listOf(group("g1", listOf("v1"))))
        assertEquals("split needs at least 2 children, got 1", validateLayout(l))
    }

    @Test
    fun validateLayout_rejects_sizes_that_do_not_add_up_to_1() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.2), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals("split sizes must add up to 1, got 0.7", validateLayout(l))
    }

    @Test
    fun validateLayout_rejects_a_non_positive_size() {
        val l = LayoutNode.Split("row", listOf(0.0, 1.0), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals("split sizes must all be greater than 0", validateLayout(l))
    }

    @Test
    fun validateLayout_accepts_a_valid_nested_tree() {
        val l = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                group("g1", listOf("v1")),
                LayoutNode.Split("column", listOf(0.6, 0.4), listOf(group("g2", listOf("v2", "v3"), "v2"), group("g3", listOf("v4")))),
            ),
        )
        assertNull(validateLayout(l))
    }

    @Test
    fun normalizeLayout_drops_an_empty_group_and_collapses_the_single_child_split() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), LayoutNode.Group("g2", emptyList(), null)))
        assertEquals(group("g1", listOf("v1")), normalizeLayout(l))
    }

    @Test
    fun normalizeLayout_returns_null_when_every_group_is_empty() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", emptyList(), null), LayoutNode.Group("g2", emptyList(), null)))
        assertNull(normalizeLayout(l))
    }

    @Test
    fun normalizeLayout_repairs_sizes_after_a_child_is_dropped() {
        val l = LayoutNode.Split(
            "row", listOf(0.2, 0.3, 0.5),
            listOf(group("g1", listOf("v1")), LayoutNode.Group("gx", emptyList(), null), group("g3", listOf("v3"))),
        )
        assertEquals(
            LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g3", listOf("v3")))),
            normalizeLayout(l),
        )
    }

    @Test
    fun normalizeLayout_keeps_the_sizes_when_no_child_was_dropped() {
        // The rule the TypeScript comment calls out: an unrelated close must not
        // reset a splitter the user dragged.
        val l = LayoutNode.Split("row", listOf(0.2, 0.8), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals(l, normalizeLayout(l))
    }

    @Test
    fun normalizeLayout_repairs_an_activeViewId_that_left_the_group() {
        val l = LayoutNode.Group("g1", listOf("v1", "v2"), "v9")
        assertEquals(group("g1", listOf("v1", "v2"), "v1"), normalizeLayout(l))
    }

    @Test
    fun addViewToGroup_appends_the_view_and_makes_it_active() {
        val l = singleViewLayout("g1", "v1")
        assertEquals(group("g1", listOf("v1", "v2"), "v2"), addViewToGroup(l, "g1", "v2"))
    }

    @Test
    fun addViewToGroup_leaves_the_tree_alone_when_the_group_id_is_unknown() {
        val l = singleViewLayout("g1", "v1")
        assertEquals(l, addViewToGroup(l, "nope", "v2"))
    }

    @Test
    fun removeViewFromLayout_removes_the_view_and_normalizes() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals(group("g1", listOf("v1")), removeViewFromLayout(l, "v2"))
    }

    @Test
    fun removeViewFromLayout_returns_null_when_the_last_view_goes() {
        assertNull(removeViewFromLayout(singleViewLayout("g1", "v1"), "v1"))
    }

    @Test
    fun removeViewFromLayout_picks_a_new_active_view_when_the_active_one_goes() {
        val l = group("g1", listOf("v1", "v2"), "v1")
        assertEquals(group("g1", listOf("v2"), "v2"), removeViewFromLayout(l, "v1"))
    }

    // --- Kotlin-only helpers (no TypeScript counterpart) ---

    @Test
    fun firstGroupId_finds_the_leftmost_group() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals("g1", firstGroupId(l))
    }

    @Test
    fun splitGroup_moves_the_view_into_a_new_group_beside_the_old_one() {
        val l = group("g1", listOf("v1", "v2"), "v1")
        val out = splitGroup(l, "g1", "v2", "row", "g2")
        assertEquals(
            LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                LayoutNode.Group("g1", listOf("v1"), "v1"),
                LayoutNode.Group("g2", listOf("v2"), "v2"),
            )),
            out,
        )
        assertNull(validateLayout(out))
    }

    @Test
    fun splitGroup_refuses_to_split_a_group_holding_one_view() {
        // Splitting the only view would leave an empty group, which is invalid.
        val l = singleViewLayout("g1", "v1")
        assertEquals(l, splitGroup(l, "g1", "v1", "row", "g2"))
    }

    @Test
    fun splitGroup_leaves_the_tree_alone_for_an_unknown_group() {
        val l = group("g1", listOf("v1", "v2"), "v1")
        assertEquals(l, splitGroup(l, "nope", "v2", "row", "g2"))
    }
}
