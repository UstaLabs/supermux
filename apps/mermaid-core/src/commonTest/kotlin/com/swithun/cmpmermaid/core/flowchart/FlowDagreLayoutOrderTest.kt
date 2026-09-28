package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlowDagreLayoutOrderTest {
    @Test
    fun propagatesParentSpacingToExtractedSubgraphs() {
        val document = spacingDocument()
        val placement = assertIs<GMResult.Ok<FlowLayoutPlacement>>(
            FlowDagreLayout.layout(
                document = document,
                nodeSizes = document.nodes.keys.associateWith { SceneSize(40f, 30f) },
                nodeShapeLayouts = emptyMap(),
                edgeLabelSizes = emptyMap(),
                options = MermaidRenderOptions(
                    nodeSpacing = 140f,
                    rankSpacing = 80f,
                ),
            ),
        ).value

        val first = placement.nodeBounds.getValue("A")
        val second = placement.nodeBounds.getValue("B")
        val target = placement.nodeBounds.getValue("C")
        val sameRankGap = abs(first.center.x - second.center.x) - first.width
        val crossRankGap = target.top - maxOf(first.bottom, second.bottom)

        assertEquals(140f, sameRankGap, 0.01f)
        assertEquals(105f, crossRankGap, 0.01f)
    }

    @Test
    fun preservesUpstreamCompoundNodeInsertionOrder() {
        val document = checkoutDocument()
        val placement = assertIs<GMResult.Ok<FlowLayoutPlacement>>(
            FlowDagreLayout.layout(
                document = document,
                nodeSizes = document.nodes.keys.associateWith { SceneSize(64f, 42f) },
                nodeShapeLayouts = emptyMap(),
                edgeLabelSizes = emptyMap(),
                options = MermaidRenderOptions(),
            ),
        ).value

        val repair = placement.nodeBounds.getValue("Repair").center
        val quote = placement.nodeBounds.getValue("Quote").center

        assertTrue(
            repair.y > quote.y,
            "Mermaid 12 places the repair branch below the checkout main path: " +
                "repair=$repair, quote=$quote",
        )
    }

    private fun spacingDocument(): FlowchartDocument {
        val nodeIds = listOf("A", "B", "C")
        return FlowchartDocument(
            direction = FlowDirection.LeftToRight,
            nodes = nodeIds.associateWithTo(linkedMapOf()) { id ->
                FlowNode(
                    id = id,
                    label = id,
                    labelSpans = emptyList(),
                    labelType = FlowLabelType.Text,
                    shape = SceneShapeKind.Rectangle,
                    padding = 8f,
                    minWidth = null,
                    look = "classic",
                )
            },
            edges = listOf(
                FlowEdge(id = "e0", from = "A", to = "C", look = "classic"),
                FlowEdge(id = "e1", from = "B", to = "C", look = "classic"),
            ),
            subgraphs = listOf(
                FlowSubgraph(
                    id = "group",
                    label = "Group",
                    labelSpans = emptyList(),
                    nodeIds = nodeIds.toSet(),
                    direction = FlowDirection.TopToBottom,
                    padding = 8f,
                    look = "classic",
                ),
            ),
        )
    }

    private fun checkoutDocument(): FlowchartDocument {
        val nodeIds = listOf(
            "Customer",
            "Cart",
            "Validate",
            "Repair",
            "Quote",
            "Reserve",
            "Inventory",
            "Release",
            "Authorize",
            "Payment",
            "CreateOrder",
            "Arrange",
            "Shipment",
            "Refund",
            "Confirm",
            "Complete",
            "Failed",
        )
        val nodes = nodeIds.associateWithTo(linkedMapOf()) { id ->
            FlowNode(
                id = id,
                label = id,
                labelSpans = emptyList(),
                labelType = FlowLabelType.Text,
                shape = SceneShapeKind.Rectangle,
                padding = 8f,
                minWidth = null,
                look = "classic",
            )
        }
        val edgePairs = listOf(
            "Customer" to "Cart",
            "Cart" to "Validate",
            "Validate" to "Repair",
            "Repair" to "Cart",
            "Validate" to "Quote",
            "Quote" to "Reserve",
            "Reserve" to "Inventory",
            "Inventory" to "Release",
            "Inventory" to "Authorize",
            "Authorize" to "Payment",
            "Payment" to "Release",
            "Payment" to "CreateOrder",
            "CreateOrder" to "Arrange",
            "Arrange" to "Shipment",
            "Shipment" to "Refund",
            "Refund" to "Release",
            "Shipment" to "Confirm",
            "Confirm" to "Complete",
            "Release" to "Failed",
        )
        val orchestrationNodes = linkedSetOf(
            "Quote",
            "Reserve",
            "Inventory",
            "Release",
            "Authorize",
            "Payment",
            "CreateOrder",
            "Arrange",
            "Shipment",
            "Refund",
        )

        return FlowchartDocument(
            direction = FlowDirection.LeftToRight,
            nodes = nodes,
            edges = edgePairs.mapIndexed { index, (from, to) ->
                FlowEdge(
                    id = "e$index",
                    from = from,
                    to = to,
                    look = "classic",
                )
            },
            subgraphs = listOf(
                FlowSubgraph(
                    id = "Orchestration",
                    label = "Checkout orchestration",
                    labelSpans = emptyList(),
                    nodeIds = orchestrationNodes,
                    padding = 8f,
                    look = "classic",
                ),
            ),
        )
    }
}
