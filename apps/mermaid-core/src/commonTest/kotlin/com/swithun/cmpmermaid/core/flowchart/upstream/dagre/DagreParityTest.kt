package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.EdgeRef
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DagreParityTest {
    @Test
    fun matchesUpstreamHorizontalChain() {
        val graph = Graph<DagreNode, DagreEdge, DagreGraphLabel>(
            multigraph = true,
            compound = true,
        ).setGraph(
            DagreGraphLabel(
                rankdir = "LR",
                nodesep = 52f,
                ranksep = 72f,
                marginx = 8f,
                marginy = 8f,
            ),
        )
        listOf("A", "B", "C").forEach {
            graph.setNode(it, DagreNode(width = 64f, height = 42f))
        }
        graph.setEdge("A", "B", DagreEdge(labelpos = "c"), "e0")
        graph.setEdge("B", "C", DagreEdge(labelpos = "c"), "e1")

        assertIs<GMResult.Ok<Unit>>(DagreLayout.run(graph))

        assertNode(graph, "A", 40f, 29f)
        assertNode(graph, "B", 176f, 29f)
        assertNode(graph, "C", 312f, 29f)
        assertEquals(352f, graph.graph().width, 0.001f)
        assertEquals(58f, graph.graph().height, 0.001f)
        assertEquals(
            listOf(72f to 29f, 108f to 29f, 144f to 29f),
            graph.edgeOrThrow(EdgeRef("A", "B", "e0")).points.map { it.x to it.y },
        )
        assertEquals(
            listOf(208f to 29f, 244f to 29f, 280f to 29f),
            graph.edgeOrThrow(EdgeRef("B", "C", "e1")).points.map { it.x to it.y },
        )
    }

    @Test
    fun matchesUpstreamBranchOrderingAndCoordinates() {
        val graph = graph(
            nodes = listOf("A", "B", "C", "D"),
            edges = listOf(
                Triple("A", "B", "e0"),
                Triple("A", "C", "e1"),
                Triple("B", "D", "e2"),
                Triple("C", "D", "e3"),
            ),
        )

        assertIs<GMResult.Ok<Unit>>(DagreLayout.run(graph))

        assertNode(graph, "A", 97f, 29f)
        assertNode(graph, "B", 40f, 121f)
        assertNode(graph, "C", 154f, 121f)
        assertNode(graph, "D", 97f, 213f)
        assertEquals(194f, graph.graph().width, 0.001f)
        assertEquals(242f, graph.graph().height, 0.001f)
    }

    @Test
    fun matchesUpstreamLongLabeledEdge() {
        val graph = graph(nodes = listOf("A", "B"), edges = emptyList())
        graph.setEdge(
            "A",
            "B",
            DagreEdge(
                minlen = 3,
                width = 50f,
                height = 20f,
                labelpos = "c",
            ),
            "e0",
        )

        assertIs<GMResult.Ok<Unit>>(DagreLayout.run(graph))

        assertNode(graph, "A", 40f, 29f)
        assertNode(graph, "B", 40f, 241f)
        val edge = graph.edgeOrThrow(EdgeRef("A", "B", "e0"))
        assertEquals(40f, requireNotNull(edge.x), 0.001f)
        assertEquals(135f, requireNotNull(edge.y), 0.001f)
        assertEquals(listOf(50f, 75f, 100f, 135f, 170f, 195f, 220f), edge.points.map { it.y })
    }

    @Test
    fun matchesUpstreamParallelEdgeLanes() {
        val graph = graph(nodes = listOf("A", "B"), edges = emptyList(), direction = "LR")
        listOf(30f, 40f, 50f).forEachIndexed { index, width ->
            graph.setEdge(
                "A",
                "B",
                DagreEdge(width = width, height = 14f, labelpos = "c"),
                "e$index",
            )
        }

        assertIs<GMResult.Ok<Unit>>(DagreLayout.run(graph))

        assertNode(graph, "A", 40f, 49f)
        assertNode(graph, "B", 204f, 49f)
        assertEquals(244f, graph.graph().width, 0.001f)
        assertEquals(98f, graph.graph().height, 0.001f)
        assertEquals(15f, requireNotNull(graph.edgeOrThrow(EdgeRef("A", "B", "e0")).y), 0.001f)
        assertEquals(49f, requireNotNull(graph.edgeOrThrow(EdgeRef("A", "B", "e1")).y), 0.001f)
        assertEquals(83f, requireNotNull(graph.edgeOrThrow(EdgeRef("A", "B", "e2")).y), 0.001f)
        assertEquals(
            listOf(35.7317f, 15f, 35.7317f),
            graph.edgeOrThrow(EdgeRef("A", "B", "e0")).points.map {
                kotlin.math.round(it.y * 10_000f) / 10_000f
            },
        )
    }

    @Test
    fun matchesUpstreamCycleRemovalAndRestoration() {
        val graph = graph(
            direction = "LR",
            nodes = listOf("A", "B", "C"),
            edges = listOf(
                Triple("A", "B", "e0"),
                Triple("B", "C", "e1"),
                Triple("C", "A", "e2"),
            ),
        )

        var ordered = emptyList<Triple<String, Int, Int>>()
        var positioned = emptyMap<String, Pair<Float, Float>>()
        var xTrace: Position.PositionTrace? = null
        assertIs<GMResult.Ok<Unit>>(
            DagreLayout.run(graph) { stage, layout ->
                if (stage == "order") {
                    ordered = layout.nodes().mapNotNull { id ->
                        val node = layout.nodeOrThrow(id)
                        if (node.rank != null && node.order != null) {
                            Triple(id, node.rank!!, node.order!!)
                        } else {
                            null
                        }
                    }.sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
                }
                if (stage == "position") {
                    positioned = layout.nodes().associateWith {
                        layout.nodeOrThrow(it).let { node -> node.x to node.y }
                    }
                }
                if (stage == "adjustCoordinateSystem") {
                    xTrace = Position.traceX(layout)
                }
            },
        )

        assertEquals(
            listOf(
                Triple("A", 0, 0),
                Triple("_d3", 1, 0),
                Triple("_d5", 1, 1),
                Triple("B", 2, 0),
                Triple("_d6", 2, 1),
                Triple("_d4", 3, 0),
                Triple("_d7", 3, 1),
                Triple("C", 4, 0),
            ),
            ordered,
        )
        assertEquals(
            mapOf(
                "A" to 28f,
                "_d3" to 0f,
                "_d5" to 56f,
                "B" to 0f,
                "_d6" to 56f,
                "_d4" to 0f,
                "_d7" to 56f,
                "C" to 28f,
            ),
            requireNotNull(xTrace).balanced,
            "${requireNotNull(xTrace).roots}\n" +
                "${requireNotNull(xTrace).alignments}\n" +
                "${requireNotNull(xTrace).conflicts}",
        )
        assertEquals(
            mapOf(
                "A" to (28f to 32f),
                "B" to (0f to 146f),
                "C" to (28f to 260f),
                "_d3" to (0f to 89f),
                "_d4" to (0f to 203f),
                "_d5" to (56f to 89f),
                "_d6" to (56f to 146f),
                "_d7" to (56f to 203f),
            ),
            positioned,
        )
        assertEquals(
            mapOf(
                "A" to (40f to 57f),
                "B" to (154f to 29f),
                "C" to (268f to 57f),
            ),
            graph.nodes().associateWith { graph.nodeOrThrow(it).let { node -> node.x to node.y } },
        )
        assertNode(graph, "A", 40f, 57f)
        assertNode(graph, "B", 154f, 29f)
        assertNode(graph, "C", 268f, 57f)
        assertEquals(
            listOf(236f to 72.7193f, 211f to 85f, 154f to 85f, 97f to 85f, 72f to 72.7193f),
            graph.edgeOrThrow(EdgeRef("C", "A", "e2")).points.map {
                it.x to (kotlin.math.round(it.y * 10_000f) / 10_000f)
            },
        )
    }

    @Test
    fun matchesUpstreamCompoundBounds() {
        val graph = graph(
            nodes = listOf("G", "A", "B", "C"),
            edges = listOf(
                Triple("A", "B", "e0"),
                Triple("B", "C", "e1"),
            ),
        )
        graph.setParent("A", "G")
        graph.setParent("B", "G")

        assertIs<GMResult.Ok<Unit>>(DagreLayout.run(graph))

        assertNode(graph, "G", 75f, 100f)
        assertNode(graph, "A", 75f, 54f)
        assertNode(graph, "B", 75f, 146f)
        assertNode(graph, "C", 75f, 263f)
        assertEquals(134f, graph.nodeOrThrow("G").width, 0.001f)
        assertEquals(184f, graph.nodeOrThrow("G").height, 0.001f)
        assertEquals(150f, graph.graph().width, 0.001f)
        assertEquals(292f, graph.graph().height, 0.001f)
    }

    @Test
    fun matchesUpstreamNestedCompoundBounds() {
        val graph = graph(
            nodes = listOf("G", "H", "A", "B", "C"),
            edges = listOf(
                Triple("A", "B", "e0"),
                Triple("B", "C", "e1"),
            ),
        )
        graph.nodeOrThrow("H").width = 0f
        graph.nodeOrThrow("H").height = 0f
        graph.setParent("H", "G")
        graph.setParent("A", "H")
        graph.setParent("B", "H")

        assertIs<GMResult.Ok<Unit>>(DagreLayout.run(graph))

        assertNode(graph, "G", 95f, 125f)
        assertNode(graph, "H", 95f, 125f)
        assertNode(graph, "A", 95f, 79f)
        assertNode(graph, "B", 95f, 171f)
        assertNode(graph, "C", 95f, 313f)
        assertEquals(174f, graph.nodeOrThrow("G").width, 0.001f)
        assertEquals(234f, graph.nodeOrThrow("G").height, 0.001f)
        assertEquals(134f, graph.nodeOrThrow("H").width, 0.001f)
        assertEquals(184f, graph.nodeOrThrow("H").height, 0.001f)
        assertEquals(190f, graph.graph().width, 0.001f)
        assertEquals(342f, graph.graph().height, 0.001f)
    }

    private fun graph(
        direction: String = "TB",
        nodes: List<String>,
        edges: List<Triple<String, String, String>>,
    ): DagreGraph {
        val graph = Graph<DagreNode, DagreEdge, DagreGraphLabel>(
            multigraph = true,
            compound = true,
        ).setGraph(
            DagreGraphLabel(
                rankdir = direction,
                nodesep = 50f,
                ranksep = 50f,
                marginx = 8f,
                marginy = 8f,
            ),
        )
        nodes.forEach { id ->
            val size = if (id == "G") 0f else 64f
            graph.setNode(id, DagreNode(width = size, height = if (id == "G") 0f else 42f))
        }
        edges.forEach { (from, to, name) ->
            graph.setEdge(from, to, DagreEdge(labelpos = "c"), name)
        }
        return graph
    }

    private fun assertNode(
        graph: DagreGraph,
        id: String,
        expectedX: Float,
        expectedY: Float,
    ) {
        val node = graph.nodeOrThrow(id)
        assertEquals(expectedX, node.x, 0.001f, "$id.x")
        assertEquals(expectedY, node.y, 0.001f, "$id.y")
    }
}
