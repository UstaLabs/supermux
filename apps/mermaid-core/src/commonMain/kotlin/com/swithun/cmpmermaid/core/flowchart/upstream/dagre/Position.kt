package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/position/index.js
 * src/dagre/position/bk.js
 */
internal object Position {
    fun run(input: DagreGraph) {
        val graph = DagreUtil.asNonCompoundGraph(input)
        positionY(graph)
        traceX(graph).balanced.forEach { (id, x) -> graph.nodeOrThrow(id).x = x }
    }

    private fun positionY(graph: DagreGraph) {
        val layering = DagreUtil.buildLayerMatrix(graph)
        var previousY = 0f
        layering.forEach { layer ->
            val maximumHeight = layer.maxOfOrNull { graph.nodeOrThrow(it).height } ?: 0f
            layer.forEach { graph.nodeOrThrow(it).y = previousY + maximumHeight / 2f }
            previousY += maximumHeight + graph.graph().ranksep
        }
    }

    internal fun traceX(graph: DagreGraph): PositionTrace {
        val layering = DagreUtil.buildLayerMatrix(graph)
        val conflicts = findType1Conflicts(graph, layering).toMutableSet()
        conflicts += findType2Conflicts(graph, layering)
        val alignments = linkedMapOf<String, Map<String, Float>>()
        val roots = linkedMapOf<String, Map<String, String>>()

        for (vertical in listOf("u", "d")) {
            val verticalLayers = if (vertical == "u") layering else layering.asReversed()
            for (horizontal in listOf("l", "r")) {
                val adjusted = if (horizontal == "r") {
                    verticalLayers.map(List<String>::asReversed)
                } else {
                    verticalLayers
                }
                val neighbors: (String) -> List<String> =
                    if (vertical == "u") graph::predecessors else graph::successors
                val aligned = verticalAlignment(graph, adjusted, conflicts, neighbors)
                roots[vertical + horizontal] = aligned.root
                var coordinates: Map<String, Float> = horizontalCompaction(
                    graph,
                    adjusted,
                    aligned.root,
                    aligned.align,
                    reverseSep = horizontal == "r",
                )
                if (horizontal == "r") {
                    coordinates = coordinates.mapValues { -it.value }
                }
                alignments[vertical + horizontal] = coordinates
            }
        }
        val smallest = findSmallestWidthAlignment(graph, alignments.values)
        alignCoordinates(alignments, smallest)
        return PositionTrace(
            alignments = alignments.mapValues { it.value.toMap() },
            balanced = balance(alignments, graph.graph().align),
            roots = roots,
            conflicts = conflicts,
        )
    }

    private fun findType1Conflicts(
        graph: DagreGraph,
        layering: List<List<String>>,
    ): Set<Pair<String, String>> {
        val conflicts = mutableSetOf<Pair<String, String>>()
        for (layerIndex in 1 until layering.size) {
            val previous = layering[layerIndex - 1]
            val layer = layering[layerIndex]
            var lastPreviousPosition = 0
            var scanPosition = 0
            val previousLength = previous.size
            val lastNode = layer.lastOrNull()
            layer.forEachIndexed { index, id ->
                val inner = findOtherInnerSegmentNode(graph, id)
                val nextPreviousPosition =
                    inner?.let { graph.nodeOrThrow(it).orderOrThrow() } ?: previousLength
                if (inner != null || id == lastNode) {
                    layer.slice(scanPosition..index).forEach { scanNode ->
                        graph.predecessors(scanNode).forEach { predecessor ->
                            val predecessorNode = graph.nodeOrThrow(predecessor)
                            val position = predecessorNode.orderOrThrow()
                            if ((position < lastPreviousPosition || nextPreviousPosition < position) &&
                                !(predecessorNode.dummy != null &&
                                    graph.nodeOrThrow(scanNode).dummy != null)
                            ) {
                                conflicts += conflict(predecessor, scanNode)
                            }
                        }
                    }
                    scanPosition = index + 1
                    lastPreviousPosition = nextPreviousPosition
                }
            }
        }
        return conflicts
    }

    private fun findType2Conflicts(
        graph: DagreGraph,
        layering: List<List<String>>,
    ): Set<Pair<String, String>> {
        val conflicts = mutableSetOf<Pair<String, String>>()

        fun scan(
            south: List<String>,
            start: Int,
            end: Int,
            previousNorthBorder: Int?,
            nextNorthBorder: Int,
        ) {
            for (index in start until end) {
                val id = south[index]
                if (graph.nodeOrThrow(id).dummy != null) {
                    graph.predecessors(id).forEach { predecessor ->
                        val predecessorNode = graph.nodeOrThrow(predecessor)
                        if (predecessorNode.dummy != null &&
                            ((previousNorthBorder != null &&
                                predecessorNode.orderOrThrow() < previousNorthBorder) ||
                                predecessorNode.orderOrThrow() > nextNorthBorder)
                        ) {
                            conflicts += conflict(predecessor, id)
                        }
                    }
                }
            }
        }

        for (layerIndex in 1 until layering.size) {
            val north = layering[layerIndex - 1]
            val south = layering[layerIndex]
            var previousNorthPosition = -1
            var nextNorthPosition: Int? = null
            var southPosition = 0
            south.forEachIndexed { lookahead, id ->
                if (graph.nodeOrThrow(id).dummy == "border") {
                    graph.predecessors(id).firstOrNull()?.let { predecessor ->
                        val currentNorthPosition =
                            graph.nodeOrThrow(predecessor).orderOrThrow()
                        nextNorthPosition = currentNorthPosition
                        scan(
                            south,
                            southPosition,
                            lookahead,
                            previousNorthPosition,
                            currentNorthPosition,
                        )
                        southPosition = lookahead
                        previousNorthPosition = currentNorthPosition
                    }
                }
                scan(south, southPosition, south.size, nextNorthPosition, north.size)
            }
        }
        return conflicts
    }

    private fun findOtherInnerSegmentNode(graph: DagreGraph, id: String): String? =
        if (graph.nodeOrThrow(id).dummy != null) {
            graph.predecessors(id).firstOrNull { graph.nodeOrThrow(it).dummy != null }
        } else {
            null
        }

    private fun verticalAlignment(
        graph: DagreGraph,
        layering: List<List<String>>,
        conflicts: Set<Pair<String, String>>,
        neighbors: (String) -> List<String>,
    ): Alignment {
        val root = mutableMapOf<String, String>()
        val align = mutableMapOf<String, String>()
        val positions = mutableMapOf<String, Int>()
        layering.forEach { layer ->
            layer.forEachIndexed { index, id ->
                root[id] = id
                align[id] = id
                positions[id] = index
            }
        }

        layering.forEach { layer ->
            var previousIndex = -1
            layer.forEach { id ->
                val orderedNeighbors = neighbors(id).sortedBy { positions[it] }
                if (orderedNeighbors.isEmpty()) return@forEach
                val median = (orderedNeighbors.size - 1) / 2f
                for (index in floor(median).toInt()..ceil(median).toInt()) {
                    val neighbor = orderedNeighbors[index]
                    if (align[id] == id &&
                        previousIndex < positions.getValue(neighbor) &&
                        conflict(id, neighbor) !in conflicts
                    ) {
                        align[neighbor] = id
                        root[id] = root.getValue(neighbor)
                        align[id] = root.getValue(neighbor)
                        previousIndex = positions.getValue(neighbor)
                    }
                }
            }
        }
        return Alignment(root, align)
    }

    private fun horizontalCompaction(
        graph: DagreGraph,
        layering: List<List<String>>,
        root: Map<String, String>,
        align: Map<String, String>,
        reverseSep: Boolean,
    ): MutableMap<String, Float> {
        val coordinates = mutableMapOf<String, Float>()
        val blockGraph = buildBlockGraph(graph, layering, root, reverseSep)
        val expectedBorder = if (reverseSep) "borderLeft" else "borderRight"

        fun iterate(
            dependencies: (String) -> List<String>,
            assign: (String) -> Unit,
        ) {
            val stack = blockGraph.nodes().toMutableList()
            var element = stack.removeLastOrNull()
            val visited = mutableSetOf<String>()
            while (element != null) {
                if (element in visited) {
                    assign(element)
                } else {
                    visited += element
                    stack += element
                    stack += dependencies(element)
                }
                element = stack.removeLastOrNull()
            }
        }

        iterate(blockGraph::predecessors) { id ->
            coordinates[id] = blockGraph.inEdges(id).fold(0f) { accumulator, edge ->
                max(
                    accumulator,
                    coordinates.getValue(edge.v) + blockGraph.edgeOrThrow(edge),
                )
            }
        }
        iterate(blockGraph::successors) { id ->
            val minimum = blockGraph.outEdges(id).fold(Float.POSITIVE_INFINITY) { accumulator, edge ->
                min(
                    accumulator,
                    coordinates.getValue(edge.w) - blockGraph.edgeOrThrow(edge),
                )
            }
            if (minimum != Float.POSITIVE_INFINITY &&
                graph.nodeOrThrow(id).borderType != expectedBorder
            ) {
                coordinates[id] = max(coordinates.getValue(id), minimum)
            }
        }
        align.values.forEach { id ->
            coordinates[id] = coordinates.getValue(root.getValue(id))
        }
        return coordinates
    }

    private fun buildBlockGraph(
        graph: DagreGraph,
        layering: List<List<String>>,
        root: Map<String, String>,
        reverseSep: Boolean,
    ): Graph<Unit, Float, Unit> {
        val result = Graph<Unit, Float, Unit>().setGraph(Unit)
        layering.forEach { layer ->
            var previous: String? = null
            layer.forEach { id ->
                val idRoot = root.getValue(id)
                if (!result.hasNode(idRoot)) result.setNode(idRoot, Unit)
                previous?.let { previousId ->
                    val previousRoot = root.getValue(previousId)
                    if (!result.hasNode(previousRoot)) result.setNode(previousRoot, Unit)
                    val existing = result.edge(previousRoot, idRoot) ?: 0f
                    result.setEdge(
                        previousRoot,
                        idRoot,
                        max(
                            separation(graph, id, previousId, reverseSep),
                            existing,
                        ),
                    )
                }
                previous = id
            }
        }
        return result
    }

    private fun findSmallestWidthAlignment(
        graph: DagreGraph,
        alignments: Collection<Map<String, Float>>,
    ): Map<String, Float> = alignments.minBy { coordinates ->
        var maximum = Float.NEGATIVE_INFINITY
        var minimum = Float.POSITIVE_INFINITY
        coordinates.forEach { (id, x) ->
            val halfWidth = graph.nodeOrThrow(id).width / 2f
            maximum = max(maximum, x + halfWidth)
            minimum = min(minimum, x - halfWidth)
        }
        maximum - minimum
    }

    private fun alignCoordinates(
        alignments: MutableMap<String, Map<String, Float>>,
        target: Map<String, Float>,
    ) {
        val targetMinimum = target.values.minOrNull() ?: 0f
        val targetMaximum = target.values.maxOrNull() ?: 0f
        listOf("u", "d").forEach { vertical ->
            listOf("l", "r").forEach { horizontal ->
                val key = vertical + horizontal
                val coordinates = alignments.getValue(key)
                if (coordinates === target) return@forEach
                val delta = if (horizontal == "l") {
                    targetMinimum - (coordinates.values.minOrNull() ?: 0f)
                } else {
                    targetMaximum - (coordinates.values.maxOrNull() ?: 0f)
                }
                if (delta != 0f) {
                    alignments[key] = coordinates.mapValues { it.value + delta }
                }
            }
        }
    }

    private fun balance(
        alignments: Map<String, Map<String, Float>>,
        requested: String?,
    ): Map<String, Float> = alignments.getValue("ul").keys.associateWith { id ->
        if (requested != null) {
            alignments.getValue(requested.lowercase()).getValue(id)
        } else {
            val values = alignments.values.map { it.getValue(id) }.sorted()
            (values[1] + values[2]) / 2f
        }
    }

    private fun separation(
        graph: DagreGraph,
        first: String,
        second: String,
        reverse: Boolean,
    ): Float {
        val firstNode = graph.nodeOrThrow(first)
        val secondNode = graph.nodeOrThrow(second)
        var result = firstNode.width / 2f
        var delta = when (firstNode.labelpos?.lowercase()) {
            "l" -> -firstNode.width / 2f
            "r" -> firstNode.width / 2f
            else -> 0f
        }
        result += if (reverse) delta else -delta
        result += (if (firstNode.dummy != null) graph.graph().edgesep else graph.graph().nodesep) / 2f
        result += (if (secondNode.dummy != null) graph.graph().edgesep else graph.graph().nodesep) / 2f
        result += secondNode.width / 2f
        delta = when (secondNode.labelpos?.lowercase()) {
            "l" -> secondNode.width / 2f
            "r" -> -secondNode.width / 2f
            else -> 0f
        }
        result += if (reverse) delta else -delta
        return result
    }

    private fun conflict(first: String, second: String): Pair<String, String> =
        if (first <= second) first to second else second to first

    private data class Alignment(
        val root: Map<String, String>,
        val align: Map<String, String>,
    )

    internal data class PositionTrace(
        val alignments: Map<String, Map<String, Float>>,
        val balanced: Map<String, Float>,
        val roots: Map<String, Map<String, String>>,
        val conflicts: Set<Pair<String, String>>,
    )
}
