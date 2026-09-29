package com.swithun.cmpmermaid.core.mindmap

import com.swithun.cmpmermaid.core.mindmap.upstream.tidytree.NonLayeredTidyTreeLayout
import com.swithun.cmpmermaid.core.mindmap.upstream.tidytree.TidyTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals

class NonLayeredTidyTreeLayoutTest {
    @Test
    fun matchesNonLayeredTidyTreeLayout202ReferenceCoordinates() {
        val child = TidyTreeNode(
            id = "a",
            width = 20f,
            height = 80f,
            children = listOf(
                TidyTreeNode(
                    id = "c",
                    width = 30f,
                    height = 60f,
                ),
            ),
        )
        val sibling = TidyTreeNode(
            id = "b",
            width = 40f,
            height = 70f,
        )
        val root = TidyTreeNode(
            id = "v",
            width = 1f,
            height = 1f,
            children = listOf(child, sibling),
        )

        val bounds = NonLayeredTidyTreeLayout.layout(
            treeData = root,
            gap = 20f,
            bottomPadding = 40f,
        )

        assertEquals(54.5f, root.x)
        assertEquals(0f, root.y)
        assertEquals(15f, child.x)
        assertEquals(41f, child.y)
        assertEquals(10f, child.children.single().x)
        assertEquals(161f, child.children.single().y)
        assertEquals(55f, sibling.x)
        assertEquals(41f, sibling.y)
        assertEquals(10f, bounds.left)
        assertEquals(95f, bounds.right)
        assertEquals(0f, bounds.top)
        assertEquals(221f, bounds.bottom)
    }
}
