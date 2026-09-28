package com.swithun.cmpmermaid.core.mindmap.upstream.mermaid

/**
 * Mermaid 12.0.0: packages/mermaid/src/diagrams/mindmap/mindmapTypes.ts.
 */
internal data class MindmapNode(
    val id: Int,
    val nodeId: String,
    val level: Int,
    val description: String,
    val type: MindmapNodeType,
    val children: MutableList<MindmapNode> = mutableListOf(),
    val width: Float,
    val padding: Float,
    val isRoot: Boolean,
    var section: Int? = null,
    var classNames: String? = null,
    var icon: String? = null,
)

internal enum class MindmapNodeType(
    val upstreamValue: Int,
) {
    Default(0),
    RoundedRectangle(1),
    Rectangle(2),
    Circle(3),
    Cloud(4),
    Bang(5),
    Hexagon(6),
}

internal data class MindmapDocument(
    val root: MindmapNode,
    val nodes: List<MindmapNode>,
    val edges: List<MindmapEdge>,
    val title: String?,
)

internal data class MindmapEdge(
    val id: String,
    val start: Int,
    val end: Int,
    val depth: Int,
    val section: Int?,
)
