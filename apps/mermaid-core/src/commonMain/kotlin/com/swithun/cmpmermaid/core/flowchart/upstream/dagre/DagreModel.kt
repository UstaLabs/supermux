package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.EdgeRef

/**
 * Strongly typed equivalents of the mutable label objects used by
 * dagre-d3-es 7.0.14. Fields retain upstream names for diffability.
 */
internal data class DagreGraphLabel(
    var nodesep: Float = 50f,
    var edgesep: Float = 20f,
    var ranksep: Float = 50f,
    var marginx: Float = 0f,
    var marginy: Float = 0f,
    var acyclicer: String? = null,
    var ranker: String? = null,
    var rankdir: String = "tb",
    var align: String? = null,
    var maxRank: Int = 0,
    var nodeRankFactor: Int = 1,
    var nestingRoot: String? = null,
    var root: String? = null,
    var width: Float = 0f,
    var height: Float = 0f,
    val dummyChains: MutableList<String> = mutableListOf(),
)

internal data class DagreNode(
    var width: Float = 0f,
    var height: Float = 0f,
    var x: Float = 0f,
    var y: Float = 0f,
    var rank: Int? = null,
    var order: Int? = null,
    var dummy: String? = null,
    var edgeObj: EdgeRef? = null,
    var edgeLabel: DagreEdge? = null,
    var labelpos: String? = null,
    var low: Int = 0,
    var lim: Int = 0,
    var treeParent: String? = null,
    var root: String? = null,
    var minRank: Int? = null,
    var maxRank: Int? = null,
    var borderTop: String? = null,
    var borderBottom: String? = null,
    var borderType: String? = null,
    val borderLeft: MutableMap<Int, String> = mutableMapOf(),
    val borderRight: MutableMap<Int, String> = mutableMapOf(),
    val selfEdges: MutableList<SelfEdge> = mutableListOf(),
    var originalId: String? = null,
    var dir: String? = null,
    var clusterNode: Boolean = false,
    var clusterData: DagreNode? = null,
    var graph: DagreGraph? = null,
)

internal data class DagreEdge(
    var minlen: Int = 1,
    var weight: Float = 1f,
    var width: Float = 0f,
    var height: Float = 0f,
    var labeloffset: Float = 10f,
    var labelpos: String = "r",
    var x: Float? = null,
    var y: Float? = null,
    var labelRank: Int? = null,
    var reversed: Boolean = false,
    var forwardName: String? = null,
    var nestingEdge: Boolean = false,
    var cutvalue: Float = 0f,
    val points: MutableList<ScenePoint> = mutableListOf(),
    var originalIndex: Int? = null,
    var fromCluster: String? = null,
    var toCluster: String? = null,
    var selfLoop: DagreSelfLoop? = null,
    var originalEdge: DagreEdge? = null,
)

internal data class DagreSelfLoop(
    val id: String,
    val order: Int,
)

internal data class SelfEdge(
    val edge: EdgeRef,
    val label: DagreEdge,
)

internal data class TreeNode(
    var low: Int = 0,
    var lim: Int = 0,
    var parent: String? = null,
)

internal data class TreeEdge(
    var cutvalue: Float = 0f,
)

internal fun DagreNode.rankOrThrow(): Int =
    requireNotNull(rank) { "Dagre node rank is not assigned" }

internal fun DagreNode.orderOrThrow(): Int =
    requireNotNull(order) { "Dagre node order is not assigned" }
