package com.swithun.cmpmermaid.core.sankey.upstream.mermaid

internal data class SankeyNode(
    val id: String,
)

internal data class SankeyLink(
    val source: SankeyNode,
    val target: SankeyNode,
    val value: Double,
)

internal data class SankeyGraph(
    val nodes: List<SankeyNode>,
    val links: List<SankeyLink>,
)
