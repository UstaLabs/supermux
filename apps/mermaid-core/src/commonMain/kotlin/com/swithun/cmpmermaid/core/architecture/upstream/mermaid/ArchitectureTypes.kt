package com.swithun.cmpmermaid.core.architecture.upstream.mermaid

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/architecture/architectureTypes.ts.
 */
internal enum class ArchitectureDirection {
    L,
    R,
    T,
    B,
}

internal enum class ArchitectureAlignment {
    Vertical,
    Horizontal,
    Bend,
}

internal enum class ArchitectureAlignmentDirection {
    Row,
    Column,
}

internal data class ArchitectureDirectionPair(
    val source: ArchitectureDirection,
    val target: ArchitectureDirection,
)

internal data class ArchitecturePosition(
    val x: Int,
    val y: Int,
)

internal sealed interface ArchitectureNode {
    val id: String
    val parent: String?
    val edges: MutableList<ArchitectureEdge>
}

internal data class ArchitectureService(
    override val id: String,
    val icon: String? = null,
    val iconText: String? = null,
    val title: String? = null,
    override val parent: String? = null,
    override val edges: MutableList<ArchitectureEdge> = mutableListOf(),
) : ArchitectureNode

internal data class ArchitectureJunction(
    override val id: String,
    override val parent: String? = null,
    override val edges: MutableList<ArchitectureEdge> = mutableListOf(),
) : ArchitectureNode

internal data class ArchitectureGroup(
    val id: String,
    val icon: String? = null,
    val title: String? = null,
    val parent: String? = null,
)

internal data class ArchitectureEdge(
    val lhsId: String,
    val lhsDir: ArchitectureDirection,
    val lhsInto: Boolean = false,
    val lhsGroup: Boolean = false,
    val rhsId: String,
    val rhsDir: ArchitectureDirection,
    val rhsInto: Boolean = false,
    val rhsGroup: Boolean = false,
    val title: String? = null,
)

internal data class ArchitectureLayoutHint(
    val direction: ArchitectureAlignmentDirection,
    val members: List<String>,
)

internal data class ArchitectureDataStructures(
    val adjacencyList: Map<String, Map<ArchitectureDirectionPair, String>>,
    val spatialMaps: List<Map<String, ArchitecturePosition>>,
    val groupAlignments: Map<String, ArchitectureAlignment>,
)

internal fun getArchitectureDirectionPair(
    source: ArchitectureDirection,
    target: ArchitectureDirection,
): ArchitectureDirectionPair? =
    ArchitectureDirectionPair(source, target).takeUnless { source == target }

internal fun shiftPositionByArchitectureDirectionPair(
    position: ArchitecturePosition,
    pair: ArchitectureDirectionPair,
): ArchitecturePosition {
    val xShift = when {
        pair.source == ArchitectureDirection.L -> -1
        pair.source == ArchitectureDirection.R -> 1
        pair.target == ArchitectureDirection.L -> 1
        pair.target == ArchitectureDirection.R -> -1
        else -> 0
    }
    val yShift = when {
        pair.source == ArchitectureDirection.T -> 1
        pair.source == ArchitectureDirection.B -> -1
        pair.target == ArchitectureDirection.T -> 1
        pair.target == ArchitectureDirection.B -> -1
        else -> 0
    }
    return ArchitecturePosition(
        x = position.x + xShift,
        y = position.y + yShift,
    )
}

internal fun getArchitectureDirectionAlignment(
    first: ArchitectureDirection,
    second: ArchitectureDirection,
): ArchitectureAlignment = when {
    first.isHorizontal() != second.isHorizontal() -> ArchitectureAlignment.Bend
    first.isHorizontal() -> ArchitectureAlignment.Horizontal
    else -> ArchitectureAlignment.Vertical
}

internal fun ArchitectureDirection.isHorizontal(): Boolean =
    this == ArchitectureDirection.L || this == ArchitectureDirection.R

internal fun ArchitectureDirection.opposite(): ArchitectureDirection = when (this) {
    ArchitectureDirection.L -> ArchitectureDirection.R
    ArchitectureDirection.R -> ArchitectureDirection.L
    ArchitectureDirection.T -> ArchitectureDirection.B
    ArchitectureDirection.B -> ArchitectureDirection.T
}

internal fun architectureGroupAlignmentKey(
    first: String,
    second: String,
): String {
    val (lower, upper) = listOf(first, second).sorted()
    return "\"$lower\"-\"$upper\""
}
