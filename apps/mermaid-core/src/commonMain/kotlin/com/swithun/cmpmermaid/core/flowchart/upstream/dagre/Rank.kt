package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Algorithms
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.EdgeRef
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/rank/index.js
 * src/dagre/rank/util.js
 * src/dagre/rank/feasible-tree.js
 * src/dagre/rank/network-simplex.js
 */
internal object Rank {
    fun run(graph: DagreGraph) {
        when (graph.graph().ranker) {
            "longest-path" -> longestPath(graph)
            "tight-tree" -> {
                longestPath(graph)
                feasibleTree(graph)
            }
            else -> networkSimplex(graph)
        }
    }

    internal fun longestPath(graph: DagreGraph) {
        val visited = mutableSetOf<String>()

        fun visit(id: String): Int {
            val node = graph.nodeOrThrow(id)
            if (!visited.add(id)) return node.rankOrThrow()
            val rank = graph.outEdges(id)
                .minOfOrNull { edge ->
                    visit(edge.w) - graph.edgeOrThrow(edge).minlen
                } ?: 0
            node.rank = rank
            return rank
        }

        graph.sources().forEach(::visit)
    }

    internal fun slack(graph: DagreGraph, edge: EdgeRef): Int =
        graph.nodeOrThrow(edge.w).rankOrThrow() -
            graph.nodeOrThrow(edge.v).rankOrThrow() -
            graph.edgeOrThrow(edge).minlen

    private fun feasibleTree(graph: DagreGraph): TightTree {
        val tree = Graph<TreeNode, TreeEdge, Unit>(directed = false).setGraph(Unit)
        val start = graph.nodes().firstOrNull() ?: return tree
        tree.setNode(start, TreeNode())

        while (tightTree(tree, graph) < graph.nodeCount()) {
            val edge = graph.edges()
                .filter { tree.hasNode(it.v) != tree.hasNode(it.w) }
                .minByOrNull { slack(graph, it) }
                ?: break
            val delta = if (tree.hasNode(edge.v)) slack(graph, edge) else -slack(graph, edge)
            tree.nodes().forEach {
                val node = graph.nodeOrThrow(it)
                node.rank = node.rankOrThrow() + delta
            }
        }
        return tree
    }

    private fun tightTree(tree: TightTree, graph: DagreGraph): Int {
        fun visit(id: String) {
            graph.nodeEdges(id).forEach { edge ->
                val other = edge.other(id)
                if (!tree.hasNode(other) && slack(graph, edge) == 0) {
                    tree.setNode(other, TreeNode())
                    tree.setEdge(id, other, TreeEdge())
                    visit(other)
                }
            }
        }
        tree.nodes().toList().forEach(::visit)
        return tree.nodeCount()
    }

    private fun networkSimplex(input: DagreGraph) {
        val graph = DagreUtil.simplify(input)
        longestPath(graph)
        val tree = feasibleTree(graph)
        initLowLimValues(tree)
        initCutValues(tree, graph)
        var edge = leaveEdge(tree)
        while (edge != null) {
            val entering = enterEdge(tree, graph, edge) ?: break
            exchangeEdges(tree, graph, edge, entering)
            edge = leaveEdge(tree)
        }
    }

    private fun initCutValues(tree: TightTree, graph: DagreGraph) {
        val ordered = Algorithms.postorder(tree, tree.nodes()).dropLast(1)
        ordered.forEach { child ->
            val parent = tree.nodeOrThrow(child).parent ?: return@forEach
            tree.edgeOrThrow(edgeBetween(tree, child, parent)).cutvalue =
                calcCutValue(tree, graph, child)
        }
    }

    private fun calcCutValue(tree: TightTree, graph: DagreGraph, child: String): Float {
        val parent = tree.nodeOrThrow(child).parent ?: return 0f
        var childIsTail = true
        var graphEdge = graph.edge(child, parent)
        if (graphEdge == null) {
            childIsTail = false
            graphEdge = graph.edge(parent, child)
        }
        var cutValue = graphEdge?.weight ?: 0f
        graph.nodeEdges(child).forEach { edge ->
            val isOutEdge = edge.v == child
            val other = if (isOutEdge) edge.w else edge.v
            if (other == parent) return@forEach
            val pointsToHead = isOutEdge == childIsTail
            val otherWeight = graph.edgeOrThrow(edge).weight
            cutValue += if (pointsToHead) otherWeight else -otherWeight
            if (tree.hasEdge(child, other)) {
                val otherCutValue = tree.edge(child, other)?.cutvalue ?: 0f
                cutValue += if (pointsToHead) -otherCutValue else otherCutValue
            }
        }
        return cutValue
    }

    private fun initLowLimValues(tree: TightTree) {
        val root = tree.nodes().firstOrNull() ?: return
        val visited = mutableSetOf<String>()

        fun visit(id: String, next: Int, parent: String?): Int {
            var nextLimit = next
            val low = nextLimit
            visited += id
            tree.neighbors(id).forEach { neighbor ->
                if (neighbor !in visited) {
                    nextLimit = visit(neighbor, nextLimit, id)
                }
            }
            tree.nodeOrThrow(id).apply {
                this.low = low
                lim = nextLimit
                this.parent = parent
            }
            return nextLimit + 1
        }
        visit(root, 1, null)
    }

    private fun leaveEdge(tree: TightTree): EdgeRef? =
        tree.edges().firstOrNull { tree.edgeOrThrow(it).cutvalue < 0f }

    private fun enterEdge(
        tree: TightTree,
        graph: DagreGraph,
        leaving: EdgeRef,
    ): EdgeRef? {
        var tail = leaving.v
        var head = leaving.w
        if (!graph.hasEdge(tail, head)) {
            tail = leaving.w
            head = leaving.v
        }
        val tailNode = tree.nodeOrThrow(tail)
        val headNode = tree.nodeOrThrow(head)
        var descendantRoot = tailNode
        var flip = false
        if (tailNode.lim > headNode.lim) {
            descendantRoot = headNode
            flip = true
        }
        return graph.edges()
            .filter { edge ->
                flip == isDescendant(tree.nodeOrThrow(edge.v), descendantRoot) &&
                    flip != isDescendant(tree.nodeOrThrow(edge.w), descendantRoot)
            }
            .minByOrNull { slack(graph, it) }
    }

    private fun exchangeEdges(
        tree: TightTree,
        graph: DagreGraph,
        leaving: EdgeRef,
        entering: EdgeRef,
    ) {
        tree.removeEdge(leaving)
        tree.setEdge(entering.v, entering.w, TreeEdge())
        initLowLimValues(tree)
        initCutValues(tree, graph)
        updateRanks(tree, graph)
    }

    private fun updateRanks(tree: TightTree, graph: DagreGraph) {
        val root = tree.nodes().firstOrNull { tree.nodeOrThrow(it).parent == null } ?: return
        Algorithms.preorder(tree, root).drop(1).forEach { id ->
            val parent = tree.nodeOrThrow(id).parent ?: return@forEach
            val direct = graph.edge(id, parent)
            val edge = direct ?: graph.edge(parent, id) ?: return@forEach
            graph.nodeOrThrow(id).rank =
                graph.nodeOrThrow(parent).rankOrThrow() +
                if (direct == null) edge.minlen else -edge.minlen
        }
    }

    private fun isDescendant(node: TreeNode, root: TreeNode): Boolean =
        root.low <= node.lim && node.lim <= root.lim

    private fun edgeBetween(tree: TightTree, first: String, second: String): EdgeRef =
        tree.nodeEdges(first, second).first()
}

private typealias TightTree = Graph<TreeNode, TreeEdge, Unit>
