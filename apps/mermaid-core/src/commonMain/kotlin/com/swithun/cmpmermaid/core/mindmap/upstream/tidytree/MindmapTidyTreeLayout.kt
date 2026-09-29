package com.swithun.cmpmermaid.core.mindmap.upstream.tidytree

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.mindmap.MindmapPlacement
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDocument
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapNode
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapNodeType
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Mermaid 12.0.0:
 * packages/mermaid-layout-tidy-tree/src/layout.ts.
 */
internal object MindmapTidyTreeLayout {
    fun layout(
        document: MindmapDocument,
        nodeSizes: Map<Int, SceneSize>,
    ): GMResult<MindmapPlacement, MermaidError> {
        if (document.nodes.isEmpty()) {
            return layoutError("No nodes found in Mindmap layout data")
        }
        document.nodes.forEach { node ->
            val size = nodeSizes[node.id]
                ?: return layoutError("Mindmap node '${node.nodeId}' has no measured size")
            if (
                !size.width.isFinite() ||
                !size.height.isFinite() ||
                size.width < 0f ||
                size.height < 0f
            ) {
                return layoutError("Mindmap node '${node.nodeId}' has an invalid measured size")
            }
        }

        val children = linkedMapOf<Int, MutableList<Int>>()
        val parents = linkedMapOf<Int, Int>()
        document.edges.forEach { edge ->
            if (edge.start !in nodeSizes || edge.end !in nodeSizes) {
                return layoutError("Mindmap edge '${edge.id}' references an unknown node")
            }
            children.getOrPut(edge.start) { mutableListOf() } += edge.end
            parents[edge.end] = edge.start
        }
        val root = document.nodes.firstOrNull { node -> node.id !in parents }
            ?: document.nodes.first()
        val rootChildren = children[root.id].orEmpty()
        val leftChildren = mutableListOf<Int>()
        val rightChildren = mutableListOf<Int>()
        rootChildren.forEachIndexed { index, childId ->
            if (index % 2 == 0) {
                leftChildren += childId
            } else {
                rightChildren += childId
            }
        }

        val nodesById = document.nodes.associateBy(MindmapNode::id)
        val leftTree = buildSubTree(
            side = "left",
            rootChildren = leftChildren,
            children = children,
            nodesById = nodesById,
            nodeSizes = nodeSizes,
        )
        val rightTree = buildSubTree(
            side = "right",
            rootChildren = rightChildren,
            children = children,
            nodesById = nodesById,
            nodeSizes = nodeSizes,
        )
        val leftResult = leftTree?.also { tree ->
            NonLayeredTidyTreeLayout.layout(tree, SIBLING_GAP, CONNECTION_PADDING)
        }
        val rightResult = rightTree?.also { tree ->
            NonLayeredTidyTreeLayout.layout(tree, SIBLING_GAP, CONNECTION_PADDING)
        }
        val rootSize = nodeSizes[root.id]
            ?: return layoutError("Mindmap root '${root.nodeId}' has no measured size")
        val positioned = combineAndPositionTrees(
            root = root,
            rootSize = rootSize,
            leftResult = leftResult,
            rightResult = rightResult,
            nodesById = nodesById,
            nodeSizes = nodeSizes,
        )
        val centers = positioned.mapValues { (_, node) -> ScenePoint(node.x, node.y) }
        val edgePoints = linkedMapOf<String, List<ScenePoint>>()
        document.edges.forEach { edge ->
            val source = positioned[edge.start]
                ?: return layoutError("Tidy-tree did not position edge source '${edge.start}'")
            val target = positioned[edge.end]
                ?: return layoutError("Tidy-tree did not position edge target '${edge.end}'")
            edgePoints[edge.id] = calculateEdgePoints(source, target)
        }
        return GMResult.Ok(
            MindmapPlacement(
                nodeCenters = centers,
                edgePoints = edgePoints,
            ),
        )
    }

    private fun buildSubTree(
        side: String,
        rootChildren: List<Int>,
        children: Map<Int, List<Int>>,
        nodesById: Map<Int, MindmapNode>,
        nodeSizes: Map<Int, SceneSize>,
    ): TidyTreeNode? {
        if (rootChildren.isEmpty()) {
            return null
        }
        return TidyTreeNode(
            id = "virtual-root-$side",
            width = 1f,
            height = 1f,
            children = rootChildren.mapNotNull { childId ->
                convertNodeToTidyTreeTransposed(
                    id = childId,
                    children = children,
                    nodesById = nodesById,
                    nodeSizes = nodeSizes,
                )
            },
        )
    }

    private fun convertNodeToTidyTreeTransposed(
        id: Int,
        children: Map<Int, List<Int>>,
        nodesById: Map<Int, MindmapNode>,
        nodeSizes: Map<Int, SceneSize>,
    ): TidyTreeNode? {
        if (id !in nodesById) {
            return null
        }
        val size = nodeSizes[id] ?: return null
        return TidyTreeNode(
            id = id.toString(),
            width = size.height,
            height = size.width,
            children = children[id].orEmpty().mapNotNull { childId ->
                convertNodeToTidyTreeTransposed(
                    id = childId,
                    children = children,
                    nodesById = nodesById,
                    nodeSizes = nodeSizes,
                )
            },
        )
    }

    private fun combineAndPositionTrees(
        root: MindmapNode,
        rootSize: SceneSize,
        leftResult: TidyTreeNode?,
        rightResult: TidyTreeNode?,
        nodesById: Map<Int, MindmapNode>,
        nodeSizes: Map<Int, SceneSize>,
    ): Map<Int, PositionedNode> {
        val rootX = 0f
        val rootY = 0f
        val treeSpacing = rootSize.width / 2f + ROOT_TREE_SPACING
        val leftNodes = mutableListOf<PositionedNode>()
        val rightNodes = mutableListOf<PositionedNode>()

        leftResult?.children?.let { nodes ->
            positionLeftTreeBidirectional(
                nodes = nodes,
                positionedNodes = leftNodes,
                offsetX = rootX - treeSpacing,
                offsetY = rootY,
                nodesById = nodesById,
                nodeSizes = nodeSizes,
            )
        }
        rightResult?.children?.let { nodes ->
            positionRightTreeBidirectional(
                nodes = nodes,
                positionedNodes = rightNodes,
                offsetX = rootX + treeSpacing,
                offsetY = rootY,
                nodesById = nodesById,
                nodeSizes = nodeSizes,
            )
        }

        val leftCenterY = firstLevelCenterY(leftNodes, left = true)
        val rightCenterY = firstLevelCenterY(rightNodes, left = false)
        val result = linkedMapOf<Int, PositionedNode>()
        result[root.id] = PositionedNode(
            id = root.id,
            x = rootX,
            y = rootY + ROOT_VERTICAL_OFFSET,
            section = TreeSection.Root,
            width = rootSize.width,
            height = rootSize.height,
            type = root.type,
        )
        leftNodes.forEach { node ->
            result[node.id] = node.copy(
                x = node.x - node.width / 2f,
                y = node.y - leftCenterY + node.height / 2f,
                section = TreeSection.Left,
            )
        }
        rightNodes.forEach { node ->
            result[node.id] = node.copy(
                x = node.x + node.width / 2f,
                y = node.y - rightCenterY + node.height / 2f,
                section = TreeSection.Right,
            )
        }
        return result
    }

    private fun firstLevelCenterY(
        nodes: List<PositionedNode>,
        left: Boolean,
    ): Float {
        if (nodes.isEmpty()) {
            return 0f
        }
        val firstLevelX = if (left) {
            nodes.maxOf(PositionedNode::x)
        } else {
            nodes.minOf(PositionedNode::x)
        }
        val firstLevel = nodes.filter { node -> node.x == firstLevelX }
        if (firstLevel.isEmpty()) {
            return 0f
        }
        val minimumY = firstLevel.minOf { node -> node.y - node.height / 2f }
        val maximumY = firstLevel.maxOf { node -> node.y + node.height / 2f }
        return (minimumY + maximumY) / 2f
    }

    private fun positionLeftTreeBidirectional(
        nodes: List<TidyTreeNode>,
        positionedNodes: MutableList<PositionedNode>,
        offsetX: Float,
        offsetY: Float,
        nodesById: Map<Int, MindmapNode>,
        nodeSizes: Map<Int, SceneSize>,
    ) {
        nodes.forEach { node ->
            val id = node.id.toIntOrNull() ?: return@forEach
            val size = nodeSizes[id] ?: return@forEach
            positionedNodes += PositionedNode(
                id = id,
                x = offsetX - node.y,
                y = offsetY + node.x,
                width = size.width,
                height = size.height,
                type = nodesById[id]?.type,
            )
            positionLeftTreeBidirectional(
                nodes = node.children,
                positionedNodes = positionedNodes,
                offsetX = offsetX,
                offsetY = offsetY,
                nodesById = nodesById,
                nodeSizes = nodeSizes,
            )
        }
    }

    private fun positionRightTreeBidirectional(
        nodes: List<TidyTreeNode>,
        positionedNodes: MutableList<PositionedNode>,
        offsetX: Float,
        offsetY: Float,
        nodesById: Map<Int, MindmapNode>,
        nodeSizes: Map<Int, SceneSize>,
    ) {
        nodes.forEach { node ->
            val id = node.id.toIntOrNull() ?: return@forEach
            val size = nodeSizes[id] ?: return@forEach
            positionedNodes += PositionedNode(
                id = id,
                x = offsetX + node.y,
                y = offsetY + node.x,
                width = size.width,
                height = size.height,
                type = nodesById[id]?.type,
            )
            positionRightTreeBidirectional(
                nodes = node.children,
                positionedNodes = positionedNodes,
                offsetX = offsetX,
                offsetY = offsetY,
                nodesById = nodesById,
                nodeSizes = nodeSizes,
            )
        }
    }

    private fun calculateEdgePoints(
        source: PositionedNode,
        target: PositionedNode,
    ): List<ScenePoint> {
        val sourceCenter = ScenePoint(source.x, source.y)
        val targetCenter = ScenePoint(target.x, target.y)
        val sourceRound = source.type == MindmapNodeType.Cloud ||
            source.type == MindmapNodeType.Bang
        val targetRound = target.type == MindmapNodeType.Cloud ||
            target.type == MindmapNodeType.Bang
        var start = if (sourceRound) {
            computeCircleEdgeIntersection(source, targetCenter, sourceCenter)
        } else {
            intersection(source, sourceCenter, targetCenter)
        }
        var end = if (targetRound) {
            computeCircleEdgeIntersection(target, sourceCenter, targetCenter)
        } else {
            intersection(target, targetCenter, sourceCenter)
        }
        val points = mutableListOf(start)
        when (source.section) {
            TreeSection.Left ->
                points += ScenePoint(
                    source.x - source.width / 2f - INTERSECTION_SHIFT,
                    source.y,
                )
            TreeSection.Right ->
                points += ScenePoint(
                    source.x + source.width / 2f + INTERSECTION_SHIFT,
                    source.y,
                )
            TreeSection.Root -> when (target.section) {
                TreeSection.Right ->
                    points += ScenePoint(
                        source.x + source.width / 2f + INTERSECTION_SHIFT,
                        source.y,
                    )
                TreeSection.Left ->
                    points += ScenePoint(
                        source.x - source.width / 2f - INTERSECTION_SHIFT,
                        source.y,
                    )
                else -> Unit
            }
            null -> Unit
        }
        when (target.section) {
            TreeSection.Left ->
                points += ScenePoint(
                    target.x + target.width / 2f + INTERSECTION_SHIFT,
                    target.y,
                )
            TreeSection.Right ->
                points += ScenePoint(
                    target.x - target.width / 2f - INTERSECTION_SHIFT,
                    target.y,
                )
            TreeSection.Root -> when (source.section) {
                TreeSection.Right ->
                    points += ScenePoint(
                        target.x + target.width / 2f + INTERSECTION_SHIFT,
                        target.y,
                    )
                TreeSection.Left ->
                    points += ScenePoint(
                        target.x - target.width / 2f - INTERSECTION_SHIFT,
                        target.y,
                    )
                else -> Unit
            }
            null -> Unit
        }
        points += end

        val secondPoint = points.getOrElse(1) { targetCenter }
        start = if (sourceRound) {
            computeCircleEdgeIntersection(source, secondPoint, sourceCenter)
        } else {
            intersection(source, secondPoint, sourceCenter)
        }
        points[0] = start
        val secondLastPoint = points.getOrElse(points.lastIndex - 1) { sourceCenter }
        end = if (targetRound) {
            computeCircleEdgeIntersection(target, secondLastPoint, targetCenter)
        } else {
            intersection(target, secondLastPoint, targetCenter)
        }
        points[points.lastIndex] = end
        return points
    }

    private fun computeCircleEdgeIntersection(
        circle: PositionedNode,
        lineStart: ScenePoint,
        lineEnd: ScenePoint,
    ): ScenePoint {
        val radius = min(circle.width, circle.height) / 2f
        val deltaX = lineEnd.x - lineStart.x
        val deltaY = lineEnd.y - lineStart.y
        val length = hypot(deltaX, deltaY)
        if (length == 0f) {
            return lineStart
        }
        return ScenePoint(
            x = circle.x - deltaX / length * radius,
            y = circle.y - deltaY / length * radius,
        )
    }

    private fun intersection(
        node: PositionedNode,
        outsidePoint: ScenePoint,
        insidePoint: ScenePoint,
    ): ScenePoint {
        val x = node.x
        val y = node.y
        val deltaX = abs(x - insidePoint.x)
        val halfWidth = node.width / 2f
        var r = if (insidePoint.x < outsidePoint.x) {
            halfWidth - deltaX
        } else {
            halfWidth + deltaX
        }
        val halfHeight = node.height / 2f
        val qDistance = abs(outsidePoint.y - insidePoint.y)
        val rDistance = abs(outsidePoint.x - insidePoint.x)

        return if (
            abs(y - outsidePoint.y) * halfWidth >
            abs(x - outsidePoint.x) * halfHeight
        ) {
            val q = if (insidePoint.y < outsidePoint.y) {
                outsidePoint.y - halfHeight - y
            } else {
                y - halfHeight - outsidePoint.y
            }
            r = rDistance * q / qDistance
            var resultX = if (insidePoint.x < outsidePoint.x) {
                insidePoint.x + r
            } else {
                insidePoint.x - rDistance + r
            }
            var resultY = if (insidePoint.y < outsidePoint.y) {
                insidePoint.y + qDistance - q
            } else {
                insidePoint.y - qDistance + q
            }
            if (r == 0f) {
                resultX = outsidePoint.x
                resultY = outsidePoint.y
            }
            if (rDistance == 0f) {
                resultX = outsidePoint.x
            }
            if (qDistance == 0f) {
                resultY = outsidePoint.y
            }
            ScenePoint(resultX, resultY)
        } else {
            r = if (insidePoint.x < outsidePoint.x) {
                outsidePoint.x - halfWidth - x
            } else {
                x - halfWidth - outsidePoint.x
            }
            val q = qDistance * r / rDistance
            var resultX = if (insidePoint.x < outsidePoint.x) {
                insidePoint.x + rDistance - r
            } else {
                insidePoint.x - rDistance + r
            }
            var resultY = if (insidePoint.y < outsidePoint.y) {
                insidePoint.y + q
            } else {
                insidePoint.y - q
            }
            if (r == 0f) {
                resultX = outsidePoint.x
                resultY = outsidePoint.y
            }
            if (rDistance == 0f) {
                resultX = outsidePoint.x
            }
            if (qDistance == 0f) {
                resultY = outsidePoint.y
            }
            ScenePoint(resultX, resultY)
        }
    }

    private fun <T> layoutError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Layout(message))

    private data class PositionedNode(
        val id: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val type: MindmapNodeType?,
        val section: TreeSection? = null,
    )

    private enum class TreeSection {
        Root,
        Left,
        Right,
    }

    private const val SIBLING_GAP = 20f
    private const val CONNECTION_PADDING = 40f
    private const val ROOT_TREE_SPACING = 30f
    private const val ROOT_VERTICAL_OFFSET = 20f
    private const val INTERSECTION_SHIFT = 30f
}
