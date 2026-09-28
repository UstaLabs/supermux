package com.swithun.cmpmermaid.core.mindmap.upstream.cose

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CoseNodeInput(
    val id: String,
    val size: SceneSize,
)

internal data class CoseEdgeInput(
    val id: String,
    val source: String,
    val target: String,
)

internal data class CoseLayoutResult(
    val nodeCenters: Map<String, ScenePoint>,
)

/**
 * Flat-forest translation of the exact CoSE path selected by Mermaid 12.0.0
 * Mindmap:
 *
 * cytoscape-cose-bilkent 4.1.0 -> src/index.js
 * cose-base 1.0.3 -> CoSELayout.classicLayout / positionNodesRadially / tick
 * layout-base 1.0.2 -> FDLayout force and FR-grid calculations
 *
 * MindmapDB always produces one flat tree. Compound and non-forest branches
 * remain outside this adapter instead of being replaced with a different
 * algorithm.
 */
internal object CoseBilkentLayout {
    fun layout(
        nodeInputs: List<CoseNodeInput>,
        edgeInputs: List<CoseEdgeInput>,
    ): GMResult<CoseLayoutResult, MermaidError> {
        if (nodeInputs.isEmpty()) {
            return GMResult.Err(MermaidError.Layout("CoSE-Bilkent requires at least one node"))
        }
        val nodes = linkedMapOf<String, Node>()
        nodeInputs.forEach { input ->
            if (
                input.id in nodes ||
                !input.size.width.isFinite() ||
                !input.size.height.isFinite() ||
                input.size.width <= 0f ||
                input.size.height <= 0f
            ) {
                return GMResult.Err(
                    MermaidError.Layout("Invalid CoSE-Bilkent node '${input.id}'"),
                )
            }
            nodes[input.id] = Node(
                id = input.id,
                rect = Rect(
                    x = -input.size.width.toDouble() / 2.0,
                    y = -input.size.height.toDouble() / 2.0,
                    width = input.size.width.toDouble(),
                    height = input.size.height.toDouble(),
                ),
            )
        }

        val edges = mutableListOf<Edge>()
        edgeInputs.forEach { input ->
            val source = nodes[input.source]
                ?: return GMResult.Err(
                    MermaidError.Layout("CoSE-Bilkent edge '${input.id}' has no source"),
                )
            val target = nodes[input.target]
                ?: return GMResult.Err(
                    MermaidError.Layout("CoSE-Bilkent edge '${input.id}' has no target"),
                )
            if (source === target || source.edges.any { edge -> edge.other(source) === target }) {
                return@forEach
            }
            val edge = Edge(input.id, source, target)
            source.edges += edge
            target.edges += edge
            edges += edge
        }

        val forest = flatForest(nodes.values.toList())
        if (forest.isEmpty()) {
            return GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "CoSE-Bilkent non-forest layout",
                    message = "Native Mindmap only accepts Mermaid's flat-tree data contract",
                ),
            )
        }
        positionNodesRadially(forest)
        runSpringEmbedder(nodes.values.toList(), edges)

        return GMResult.Ok(
            CoseLayoutResult(
                nodeCenters = nodes.mapValues { (_, node) ->
                    ScenePoint(node.rect.centerX.toFloat(), node.rect.centerY.toFloat())
                },
            ),
        )
    }

    private fun flatForest(nodes: List<Node>): List<List<Node>> {
        val forest = mutableListOf<List<Node>>()
        var isForest = true
        var visited = linkedSetOf<Node>()
        val queue = ArrayDeque<Node>()
        var parents = mutableMapOf<Node, Node>()
        val unprocessed = nodes.toMutableList()

        while (unprocessed.isNotEmpty() && isForest) {
            queue += unprocessed.first()
            while (queue.isNotEmpty() && isForest) {
                val current = queue.removeFirst()
                visited += current
                current.edges.forEach { edge ->
                    val neighbor = edge.other(current)
                    if (parents[current] !== neighbor) {
                        if (neighbor !in visited) {
                            queue += neighbor
                            parents[neighbor] = current
                        } else {
                            isForest = false
                        }
                    }
                }
            }
            if (!isForest) {
                forest.clear()
            } else {
                val tree = visited.toList()
                forest += tree
                unprocessed.removeAll(tree.toSet())
                visited = linkedSetOf()
                parents = mutableMapOf()
            }
        }
        return forest
    }

    private fun positionNodesRadially(forest: List<List<Node>>) {
        val columns = ceil(sqrt(forest.size.toDouble())).toInt()
        var height = 0.0
        var currentY = 0.0
        var currentX = 0.0
        var extent = PointD(0.0, 0.0)

        forest.forEachIndexed { index, tree ->
            if (index % columns == 0) {
                currentX = 0.0
                currentY = height
                if (index != 0) {
                    currentY += COMPONENT_SEPARATION
                }
                height = 0.0
            }
            val center = findCenterOfTree(tree)
            extent = radialLayout(tree, center, PointD(currentX, currentY))
            if (extent.y > height) {
                height = floor(extent.y)
            }
            currentX = floor(extent.x + COMPONENT_SEPARATION)
        }

        val bounds = bounds(forest.flatten())
        val targetLeft = WORLD_CENTER_X - extent.x / 2.0
        val targetTop = WORLD_CENTER_Y - extent.y / 2.0
        forest.flatten().forEach { node ->
            node.rect.x = targetLeft + node.rect.x - (bounds.left - GRAPH_MARGIN)
            node.rect.y = targetTop + node.rect.y - (bounds.top - GRAPH_MARGIN)
        }
    }

    /**
     * Preserves layout-base 1.0.2 Layout.findCenterOfTree iteration order,
     * including its in-place list removal behavior.
     */
    private fun findCenterOfTree(nodes: List<Node>): Node {
        val list = nodes.toMutableList()
        val removedNodes = mutableListOf<Node>()
        val remainingDegrees = mutableMapOf<Node, Int>()
        var foundCenter = list.size == 1 || list.size == 2
        var centerNode = list.first()

        list.forEach { node ->
            val degree = node.neighbors().size
            remainingDegrees[node] = degree
            if (degree == 1) {
                removedNodes += node
            }
        }
        var tempList = removedNodes.toMutableList()
        while (!foundCenter) {
            @Suppress("UNUSED_VARIABLE")
            val tempList2 = tempList.toList()
            tempList = mutableListOf()
            var index = 0
            while (index < list.size) {
                val node = list[index]
                list.removeAt(index)
                node.neighbors().forEach { neighbor ->
                    if (neighbor !in removedNodes) {
                        val newDegree = (remainingDegrees[neighbor] ?: 0) - 1
                        if (newDegree == 1) {
                            tempList += neighbor
                        }
                        remainingDegrees[neighbor] = newDegree
                    }
                }
                index += 1
            }
            removedNodes += tempList
            if (list.size == 1 || list.size == 2) {
                foundCenter = true
                centerNode = list.first()
            }
        }
        return centerNode
    }

    private fun radialLayout(
        tree: List<Node>,
        center: Node,
        startingPoint: PointD,
    ): PointD {
        val radialSeparation = max(
            tree.maxOf { node -> node.rect.diagonal },
            DEFAULT_RADIAL_SEPARATION,
        )
        branchRadialLayout(
            node = center,
            parent = null,
            startAngle = 0.0,
            endAngle = 359.0,
            distance = 0.0,
            radialSeparation = radialSeparation,
        )
        val bounds = bounds(tree)
        tree.forEach { node ->
            node.rect.x = startingPoint.x + node.rect.x - bounds.left
            node.rect.y = startingPoint.y + node.rect.y - bounds.top
        }
        return PointD(
            x = startingPoint.x + bounds.right - bounds.left,
            y = startingPoint.y + bounds.bottom - bounds.top,
        )
    }

    private fun branchRadialLayout(
        node: Node,
        parent: Node?,
        startAngle: Double,
        endAngle: Double,
        distance: Double,
        radialSeparation: Double,
    ) {
        var halfInterval = (endAngle - startAngle + 1.0) / 2.0
        if (halfInterval < 0.0) {
            halfInterval += 180.0
        }
        val nodeAngle = (halfInterval + startAngle) % 360.0
        val theta = nodeAngle * TWO_PI / 360.0
        node.rect.setCenter(distance * cos(theta), distance * sin(theta))

        val neighborEdges = node.edges.toMutableList()
        var childCount = neighborEdges.size
        if (parent != null) {
            childCount -= 1
        }
        if (childCount == 0) {
            return
        }

        var incidentCount = neighborEdges.size
        val parentEdges = if (parent == null) {
            mutableListOf()
        } else {
            node.edges.filterTo(mutableListOf()) { edge -> edge.other(node) === parent }
        }
        while (parentEdges.size > 1) {
            val edge = parentEdges.removeAt(0)
            neighborEdges.remove(edge)
            incidentCount -= 1
            childCount -= 1
        }
        val startIndex = if (parent == null) {
            0
        } else {
            (neighborEdges.indexOf(parentEdges.first()) + 1) % incidentCount
        }
        val stepAngle = abs(endAngle - startAngle) / childCount
        var branchCount = 0
        var edgeIndex = startIndex
        while (branchCount != childCount) {
            val neighbor = neighborEdges[edgeIndex].other(node)
            if (neighbor !== parent) {
                val childStartAngle = (startAngle + branchCount * stepAngle) % 360.0
                val childEndAngle = (childStartAngle + stepAngle) % 360.0
                branchRadialLayout(
                    node = neighbor,
                    parent = node,
                    startAngle = childStartAngle,
                    endAngle = childEndAngle,
                    distance = distance + radialSeparation,
                    radialSeparation = radialSeparation,
                )
                branchCount += 1
            }
            edgeIndex = (edgeIndex + 1) % incidentCount
        }
    }

    private fun runSpringEmbedder(
        nodes: List<Node>,
        edges: List<Edge>,
    ) {
        var totalIterations = 0
        var totalDisplacement = 0.0
        var oldTotalDisplacement = 0.0
        val maximumIterations = max(nodes.size * 5, MAX_ITERATIONS)
        var coolingFactor = if (nodes.size > ADAPTATION_LOWER_NODE_LIMIT) {
            max(
                COOLING_ADAPTATION_FACTOR,
                1.0 -
                    (nodes.size - ADAPTATION_LOWER_NODE_LIMIT).toDouble() /
                    (ADAPTATION_UPPER_NODE_LIMIT - ADAPTATION_LOWER_NODE_LIMIT) *
                    (1.0 - COOLING_ADAPTATION_FACTOR),
            )
        } else {
            1.0
        }
        val initialCoolingFactor = coolingFactor
        val maximumNodeDisplacement = MAX_NODE_DISPLACEMENT
        val totalDisplacementThreshold =
            DISPLACEMENT_THRESHOLD_PER_NODE * nodes.size
        val repulsionRange = 2.0 * DEFAULT_EDGE_LENGTH
        val maximumCoolingCycle =
            maximumIterations.toDouble() / CONVERGENCE_CHECK_PERIOD
        val finalTemperature =
            CONVERGENCE_CHECK_PERIOD.toDouble() / maximumIterations
        var coolingCycle = 0
        var grid = Grid.empty()

        while (true) {
            totalIterations += 1
            if (totalIterations == maximumIterations) {
                break
            }
            if (totalIterations % CONVERGENCE_CHECK_PERIOD == 0) {
                val oscillating = totalIterations > maximumIterations / 3 &&
                    abs(totalDisplacement - oldTotalDisplacement) < 2.0
                val converged = totalDisplacement < totalDisplacementThreshold
                oldTotalDisplacement = totalDisplacement
                if (converged || oscillating) {
                    break
                }
                coolingCycle += 1
                coolingFactor = max(
                    initialCoolingFactor -
                        coolingCycle.toDouble().pow(
                            ln(100.0 * (initialCoolingFactor - finalTemperature)) /
                                ln(maximumCoolingCycle),
                        ) / 100.0,
                    finalTemperature,
                )
            }

            totalDisplacement = 0.0
            val graphBounds = graphBounds(nodes)
            edges.forEach(::applySpringForce)

            if (totalIterations % GRID_CALCULATION_CHECK_PERIOD == 1) {
                grid = buildGrid(nodes, graphBounds, repulsionRange)
            }
            val processed = mutableSetOf<Node>()
            nodes.forEach { node ->
                if (totalIterations % GRID_CALCULATION_CHECK_PERIOD == 1) {
                    node.surrounding = surroundingNodes(
                        node = node,
                        grid = grid,
                        processed = processed,
                        repulsionRange = repulsionRange,
                    )
                }
                node.surrounding.forEach { other ->
                    applyRepulsionForce(node, other)
                }
                processed += node
            }

            nodes.forEach { node ->
                var dx = coolingFactor *
                    (node.springForceX + node.repulsionForceX) /
                    node.noOfChildren
                var dy = coolingFactor *
                    (node.springForceY + node.repulsionForceY) /
                    node.noOfChildren
                val maximum = coolingFactor * maximumNodeDisplacement
                if (abs(dx) > maximum) {
                    dx = maximum * sign(dx)
                }
                if (abs(dy) > maximum) {
                    dy = maximum * sign(dy)
                }
                node.rect.x += dx
                node.rect.y += dy
                totalDisplacement += abs(dx) + abs(dy)
                node.resetForces()
            }
        }
    }

    private fun applySpringForce(edge: Edge) {
        val clipped = clipRectangles(edge.target.rect, edge.source.rect)
        if (clipped.overlap) {
            return
        }
        var lengthX = clipped.a.x - clipped.b.x
        var lengthY = clipped.a.y - clipped.b.y
        if (abs(lengthX) < 1.0) {
            lengthX = sign(lengthX)
        }
        if (abs(lengthY) < 1.0) {
            lengthY = sign(lengthY)
        }
        val length = sqrt(lengthX * lengthX + lengthY * lengthY)
        if (length == 0.0) {
            return
        }
        val springForce = DEFAULT_SPRING_STRENGTH * (length - DEFAULT_EDGE_LENGTH)
        val springForceX = springForce * lengthX / length
        val springForceY = springForce * lengthY / length
        edge.source.springForceX += springForceX
        edge.source.springForceY += springForceY
        edge.target.springForceX -= springForceX
        edge.target.springForceY -= springForceY
    }

    private fun applyRepulsionForce(
        nodeA: Node,
        nodeB: Node,
    ) {
        if (nodeA.rect.intersects(nodeB.rect)) {
            val overlap = separationAmount(
                rectA = nodeA.rect,
                rectB = nodeB.rect,
                separationBuffer = DEFAULT_EDGE_LENGTH / 2.0,
            )
            val repulsionForceX = 2.0 * overlap.x
            val repulsionForceY = 2.0 * overlap.y
            val childrenConstant =
                nodeA.noOfChildren * nodeB.noOfChildren /
                    (nodeA.noOfChildren + nodeB.noOfChildren)
            nodeA.repulsionForceX -= childrenConstant * repulsionForceX
            nodeA.repulsionForceY -= childrenConstant * repulsionForceY
            nodeB.repulsionForceX += childrenConstant * repulsionForceX
            nodeB.repulsionForceY += childrenConstant * repulsionForceY
            return
        }

        val clipped = clipRectangles(nodeA.rect, nodeB.rect)
        var distanceX = clipped.b.x - clipped.a.x
        var distanceY = clipped.b.y - clipped.a.y
        if (abs(distanceX) < MIN_REPULSION_DISTANCE) {
            distanceX = sign(distanceX) * MIN_REPULSION_DISTANCE
        }
        if (abs(distanceY) < MIN_REPULSION_DISTANCE) {
            distanceY = sign(distanceY) * MIN_REPULSION_DISTANCE
        }
        val distanceSquared = distanceX * distanceX + distanceY * distanceY
        val distance = sqrt(distanceSquared)
        if (distance == 0.0) {
            return
        }
        val repulsionForce =
            DEFAULT_REPULSION_STRENGTH *
                nodeA.noOfChildren *
                nodeB.noOfChildren /
                distanceSquared
        val repulsionForceX = repulsionForce * distanceX / distance
        val repulsionForceY = repulsionForce * distanceY / distance
        nodeA.repulsionForceX -= repulsionForceX
        nodeA.repulsionForceY -= repulsionForceY
        nodeB.repulsionForceX += repulsionForceX
        nodeB.repulsionForceY += repulsionForceY
    }

    private fun buildGrid(
        nodes: List<Node>,
        bounds: Bounds,
        repulsionRange: Double,
    ): Grid {
        val sizeX = ceil((bounds.right - bounds.left) / repulsionRange)
            .toInt()
            .coerceAtLeast(1)
        val sizeY = ceil((bounds.bottom - bounds.top) / repulsionRange)
            .toInt()
            .coerceAtLeast(1)
        val cells = Array(sizeX) { Array(sizeY) { mutableListOf<Node>() } }
        nodes.forEach { node ->
            val startX = floor((node.rect.x - bounds.left) / repulsionRange)
                .toInt()
                .coerceIn(0, sizeX - 1)
            val finishX = floor((node.rect.right - bounds.left) / repulsionRange)
                .toInt()
                .coerceIn(0, sizeX - 1)
            val startY = floor((node.rect.y - bounds.top) / repulsionRange)
                .toInt()
                .coerceIn(0, sizeY - 1)
            val finishY = floor((node.rect.bottom - bounds.top) / repulsionRange)
                .toInt()
                .coerceIn(0, sizeY - 1)
            node.startX = startX
            node.finishX = finishX
            node.startY = startY
            node.finishY = finishY
            for (x in startX..finishX) {
                for (y in startY..finishY) {
                    cells[x][y] += node
                }
            }
        }
        return Grid(cells)
    }

    private fun surroundingNodes(
        node: Node,
        grid: Grid,
        processed: Set<Node>,
        repulsionRange: Double,
    ): List<Node> {
        val surrounding = linkedSetOf<Node>()
        val cells = grid.cells
        for (x in node.startX - 1..node.finishX + 1) {
            for (y in node.startY - 1..node.finishY + 1) {
                if (
                    x !in cells.indices ||
                    cells.isEmpty() ||
                    y !in cells[0].indices
                ) {
                    continue
                }
                cells[x][y].forEach { other ->
                    if (node === other || other in processed) {
                        return@forEach
                    }
                    val distanceX = abs(node.rect.centerX - other.rect.centerX) -
                        (node.rect.width / 2.0 + other.rect.width / 2.0)
                    val distanceY = abs(node.rect.centerY - other.rect.centerY) -
                        (node.rect.height / 2.0 + other.rect.height / 2.0)
                    if (distanceX <= repulsionRange && distanceY <= repulsionRange) {
                        surrounding += other
                    }
                }
            }
        }
        return surrounding.toList()
    }

    private fun graphBounds(nodes: List<Node>): Bounds {
        val raw = bounds(nodes)
        return Bounds(
            left = raw.left - GRAPH_MARGIN,
            top = raw.top - GRAPH_MARGIN,
            right = raw.right + GRAPH_MARGIN,
            bottom = raw.bottom + GRAPH_MARGIN,
        )
    }

    private fun bounds(nodes: List<Node>): Bounds = Bounds(
        left = nodes.minOf { node -> node.rect.x },
        top = nodes.minOf { node -> node.rect.y },
        right = nodes.maxOf { node -> node.rect.right },
        bottom = nodes.maxOf { node -> node.rect.bottom },
    )

    private fun separationAmount(
        rectA: Rect,
        rectB: Rect,
        separationBuffer: Double,
    ): PointD {
        var overlapX = min(rectA.right, rectB.right) - max(rectA.x, rectB.x)
        var overlapY = min(rectA.bottom, rectB.bottom) - max(rectA.y, rectB.y)
        if (rectA.x <= rectB.x && rectA.right >= rectB.right) {
            overlapX += min(rectB.x - rectA.x, rectA.right - rectB.right)
        } else if (rectB.x <= rectA.x && rectB.right >= rectA.right) {
            overlapX += min(rectA.x - rectB.x, rectB.right - rectA.right)
        }
        if (rectA.y <= rectB.y && rectA.bottom >= rectB.bottom) {
            overlapY += min(rectB.y - rectA.y, rectA.bottom - rectB.bottom)
        } else if (rectB.y <= rectA.y && rectB.bottom >= rectA.bottom) {
            overlapY += min(rectA.y - rectB.y, rectB.bottom - rectA.bottom)
        }

        val directionX = if (rectA.centerX < rectB.centerX) -1.0 else 1.0
        val directionY = if (rectA.centerY < rectB.centerY) -1.0 else 1.0
        var slope = abs((rectB.centerY - rectA.centerY) / (rectB.centerX - rectA.centerX))
        if (rectB.centerY == rectA.centerY && rectB.centerX == rectA.centerX) {
            slope = 1.0
        }
        var moveByY = slope * overlapX
        var moveByX = overlapY / slope
        if (overlapX < moveByX) {
            moveByX = overlapX
        } else {
            moveByY = overlapY
        }
        return PointD(
            x = -directionX * (moveByX / 2.0 + separationBuffer),
            y = -directionY * (moveByY / 2.0 + separationBuffer),
        )
    }

    private fun clipRectangles(
        rectA: Rect,
        rectB: Rect,
    ): ClippedLine {
        if (rectA.intersects(rectB)) {
            return ClippedLine(
                a = PointD(rectA.centerX, rectA.centerY),
                b = PointD(rectB.centerX, rectB.centerY),
                overlap = true,
            )
        }
        val dx = rectB.centerX - rectA.centerX
        val dy = rectB.centerY - rectA.centerY
        fun boundary(rect: Rect, directionX: Double, directionY: Double): PointD {
            val xScale = if (directionX == 0.0) {
                Double.POSITIVE_INFINITY
            } else {
                rect.width / 2.0 / abs(directionX)
            }
            val yScale = if (directionY == 0.0) {
                Double.POSITIVE_INFINITY
            } else {
                rect.height / 2.0 / abs(directionY)
            }
            val scale = min(xScale, yScale)
            return PointD(
                x = rect.centerX + directionX * scale,
                y = rect.centerY + directionY * scale,
            )
        }
        return ClippedLine(
            a = boundary(rectA, dx, dy),
            b = boundary(rectB, -dx, -dy),
            overlap = false,
        )
    }

    private fun sign(value: Double): Double = when {
        value > 0.0 -> 1.0
        value < 0.0 -> -1.0
        else -> 0.0
    }

    private data class PointD(
        val x: Double,
        val y: Double,
    )

    private data class Bounds(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    )

    private data class ClippedLine(
        val a: PointD,
        val b: PointD,
        val overlap: Boolean,
    )

    private data class Rect(
        var x: Double,
        var y: Double,
        val width: Double,
        val height: Double,
    ) {
        val right: Double get() = x + width
        val bottom: Double get() = y + height
        val centerX: Double get() = x + width / 2.0
        val centerY: Double get() = y + height / 2.0
        val diagonal: Double get() = sqrt(width * width + height * height)

        fun intersects(other: Rect): Boolean =
            right >= other.x &&
                bottom >= other.y &&
                other.right >= x &&
                other.bottom >= y

        fun setCenter(
            centerX: Double,
            centerY: Double,
        ) {
            x = centerX - width / 2.0
            y = centerY - height / 2.0
        }
    }

    private class Node(
        val id: String,
        val rect: Rect,
    ) {
        val edges = mutableListOf<Edge>()
        var springForceX = 0.0
        var springForceY = 0.0
        var repulsionForceX = 0.0
        var repulsionForceY = 0.0
        var noOfChildren = 1.0
        var startX = 0
        var finishX = 0
        var startY = 0
        var finishY = 0
        var surrounding = emptyList<Node>()

        fun neighbors(): Set<Node> =
            edges.mapTo(linkedSetOf()) { edge -> edge.other(this) }

        fun resetForces() {
            springForceX = 0.0
            springForceY = 0.0
            repulsionForceX = 0.0
            repulsionForceY = 0.0
        }
    }

    private data class Edge(
        val id: String,
        val source: Node,
        val target: Node,
    ) {
        fun other(node: Node): Node = if (source === node) target else source
    }

    private data class Grid(
        val cells: Array<Array<MutableList<Node>>>,
    ) {
        companion object {
            fun empty(): Grid = Grid(emptyArray())
        }
    }

    private const val DEFAULT_EDGE_LENGTH = 50.0
    private const val DEFAULT_SPRING_STRENGTH = 0.45
    private const val DEFAULT_REPULSION_STRENGTH = 4500.0
    private const val DEFAULT_RADIAL_SEPARATION = DEFAULT_EDGE_LENGTH
    private const val COMPONENT_SEPARATION = 60.0
    private const val GRAPH_MARGIN = 15.0
    private const val WORLD_CENTER_X = 1200.0
    private const val WORLD_CENTER_Y = 900.0
    private const val MAX_ITERATIONS = 2500
    private const val CONVERGENCE_CHECK_PERIOD = 100
    private const val GRID_CALCULATION_CHECK_PERIOD = 10
    private const val MIN_REPULSION_DISTANCE = DEFAULT_EDGE_LENGTH / 10.0
    private const val MAX_NODE_DISPLACEMENT = 300.0
    private const val DISPLACEMENT_THRESHOLD_PER_NODE =
        3.0 * DEFAULT_EDGE_LENGTH / 100.0
    private const val COOLING_ADAPTATION_FACTOR = 0.33
    private const val ADAPTATION_LOWER_NODE_LIMIT = 1000
    private const val ADAPTATION_UPPER_NODE_LIMIT = 5000
    private const val TWO_PI = 2.0 * kotlin.math.PI
}
