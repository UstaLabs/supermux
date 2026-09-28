package com.swithun.cmpmermaid.core.treemap

import com.swithun.cmpmermaid.core.treemap.upstream.d3.D3NumberFormat
import com.swithun.cmpmermaid.core.treemap.upstream.d3.D3TreemapLayout
import com.swithun.cmpmermaid.core.treemap.upstream.mermaid.TreemapNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class D3TreemapLayoutTest {
    @Test
    fun matchesD3Hierarchy312SquarifyCoordinates() {
        val root = TreemapNode(
            name = "",
            children = mutableListOf(
                TreemapNode(
                    name = "Root",
                    children = mutableListOf(
                        TreemapNode(name = "A", value = 10.0),
                        TreemapNode(name = "B", value = 20.0),
                        TreemapNode(
                            name = "Group",
                            children = mutableListOf(
                                TreemapNode(name = "C", value = 15.0),
                                TreemapNode(name = "D", value = 5.0),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val nodes = D3TreemapLayout.layout(
            data = root,
            width = 1000.0,
            height = 400.0,
            paddingInner = 10.0,
        ).descendants().associateBy { node -> node.data.name }

        assertNode(nodes.getValue(""), 50.0, 0.0, 0.0, 1000.0, 400.0)
        assertNode(nodes.getValue("Root"), 50.0, 10.0, 35.0, 990.0, 390.0)
        assertNode(nodes.getValue("B"), 20.0, 20.0, 70.0, 398.0, 380.0)
        assertNode(nodes.getValue("Group"), 20.0, 408.0, 70.0, 786.0, 380.0)
        assertNode(nodes.getValue("A"), 10.0, 796.0, 70.0, 980.0, 380.0)
        assertNode(nodes.getValue("C"), 15.0, 418.0, 105.0, 684.0, 370.0)
        assertNode(nodes.getValue("D"), 5.0, 694.0, 105.0, 776.0, 370.0)
    }

    @Test
    fun matchesD3Format312ForDocumentedSpecifiers() {
        assertFormat(",", 1234.56, "1,234.56")
        assertFormat("", 1234.56, "1234.56")
        assertFormat(".1f", 1234.56, "1234.6")
        assertFormat(".1%", 0.35, "35.0%")
        assertFormat(",.2", 1234.56, "1.2e+3")
        assertNull(D3NumberFormat.formatter("invalid"))
    }

    private fun assertFormat(
        format: String,
        value: Double,
        expected: String,
    ) {
        val formatter = assertNotNull(D3NumberFormat.formatter(format))
        assertEquals(expected, formatter(value))
    }

    private fun assertNode(
        node: com.swithun.cmpmermaid.core.treemap.upstream.d3.D3TreemapNode,
        value: Double,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ) {
        assertEquals(value, node.value)
        assertEquals(x0, node.x0)
        assertEquals(y0, node.y0)
        assertEquals(x1, node.x1)
        assertEquals(y1, node.y1)
    }
}
