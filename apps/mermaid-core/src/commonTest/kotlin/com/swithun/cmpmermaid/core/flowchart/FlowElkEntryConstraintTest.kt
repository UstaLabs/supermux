package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowElkEntryConstraintTest {
    @Test
    fun nominatesOneEntryForEachComponentWithoutANaturalSource() {
        val document = document(
            nodeIds = listOf("A", "B", "C", "D", "E"),
            edges = listOf(
                "A" to "B",
                "B" to "C",
                "C" to "A",
                "D" to "E",
            ),
        )

        assertEquals(setOf("A"), FlowElkLayout.findCyclicEntryNodes(document))
    }

    @Test
    fun followsMermaidEdgeOrderWhenBreakingTheCandidateCycle() {
        val document = document(
            nodeIds = listOf("A", "B", "C"),
            edges = listOf(
                "B" to "C",
                "C" to "A",
                "A" to "B",
            ),
        )

        assertEquals(setOf("B"), FlowElkLayout.findCyclicEntryNodes(document))
    }

    private fun document(
        nodeIds: List<String>,
        edges: List<Pair<String, String>>,
    ): FlowchartDocument = FlowchartDocument(
        direction = FlowDirection.TopToBottom,
        nodes = nodeIds.associateWith { id ->
            FlowNode(
                id = id,
                label = id,
                labelSpans = emptyList(),
                labelType = FlowLabelType.Text,
                shape = SceneShapeKind.Rectangle,
                padding = 15f,
                minWidth = null,
                look = "classic",
            )
        },
        edges = edges.mapIndexed { index, (from, to) ->
            FlowEdge(
                id = "edge-$index",
                from = from,
                to = to,
                look = "classic",
            )
        },
        subgraphs = emptyList(),
    )
}
