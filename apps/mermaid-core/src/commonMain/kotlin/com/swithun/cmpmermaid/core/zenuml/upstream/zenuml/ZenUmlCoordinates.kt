package com.swithun.cmpmermaid.core.zenuml.upstream.zenuml

import kotlin.math.abs
import kotlin.math.max

internal const val ZEN_UML_MARGIN: Double = 20.0
internal const val ZEN_UML_ARROW_HEAD_WIDTH: Double = 10.0
internal const val ZEN_UML_OCCURRENCE_WIDTH: Double = 15.0
internal const val ZEN_UML_OCCURRENCE_SIDE_WIDTH: Double = 7.0
internal const val ZEN_UML_MIN_PARTICIPANT_WIDTH: Double = 80.0
internal const val ZEN_UML_PARTICIPANT_ICON_WIDTH: Double = 40.0
internal const val ZEN_UML_FRAGMENT_PADDING_X: Double = 10.0
internal const val ZEN_UML_FRAGMENT_MIN_WIDTH: Double = 100.0

internal enum class ZenUmlTextType {
    MessageContent,
    ParticipantName,
}

internal fun interface ZenUmlWidthProvider {
    fun measure(text: String, type: ZenUmlTextType): Double
}

/**
 * @zenuml/core 3.49.2:
 * src/positioning/Coordinates.ts -> Coordinates.
 */
internal class ZenUmlCoordinates(
    document: ZenUmlDocument,
    private val widthProvider: ZenUmlWidthProvider,
) {
    private val participants = document.participants
    private val participantByName = participants.associateBy(ZenUmlParticipant::name)
    private val participantWidths = mutableMapOf<String, Double>()
    private val messages = collectMessages(document.statements)
    private val constraints: Array<DoubleArray> =
        Array(participants.size) { DoubleArray(participants.size) }
    private val positions: List<Double>

    init {
        withParticipantGaps()
        withMessageGaps()
        positions = findOptimal(constraints)
    }

    fun orderedParticipantNames(): List<String> = participants.map(ZenUmlParticipant::name)

    fun getPosition(participantName: String?): Double {
        if (participantName == null) return 0.0
        val index = participants.indexOfFirst { participant -> participant.name == participantName }
        if (index < 0 || participants.isEmpty()) return 0.0
        return half(participants.first().name) + positions[index]
    }

    fun half(participantName: String): Double =
        participantByName[participantName]?.let(::participantWidth)?.div(2.0) ?: 0.0

    fun left(participantName: String): Double = getPosition(participantName) - half(participantName)

    fun right(participantName: String): Double = getPosition(participantName) + half(participantName)

    fun getWidth(): Double {
        val last = participants.lastOrNull() ?: return 200.0
        return max(getPosition(last.name) + half(last.name), 200.0)
    }

    fun distance(left: String, right: String): Double = getPosition(right) - getPosition(left)

    fun getMessageWidth(message: ZenUmlMessage): Double {
        var width = widthProvider.measure(messageSignature(message), ZenUmlTextType.MessageContent)
        if (message.kind == ZenUmlMessageKind.Creation) {
            width += half(message.to)
        }
        return width
    }

    private fun withParticipantGaps() {
        participants.indices.forEach { leftIndex ->
            val rightIndex = leftIndex + 1
            if (rightIndex < participants.size) {
                constraints[leftIndex][rightIndex] =
                    half(participants[leftIndex].name) + half(participants[rightIndex].name)
            }
        }
    }

    private fun withMessageGaps() {
        messages.forEach { message ->
            val fromIndex = participants.indexOfFirst { participant -> participant.name == message.from }
            val toIndex = participants.indexOfFirst { participant -> participant.name == message.to }
            if (fromIndex < 0 || toIndex < 0) return@forEach
            val leftIndex = minOf(fromIndex, toIndex)
            val rightIndex = maxOf(fromIndex, toIndex)
            constraints[leftIndex][rightIndex] = max(
                constraints[leftIndex][rightIndex],
                getMessageWidth(message) + ZEN_UML_ARROW_HEAD_WIDTH + ZEN_UML_OCCURRENCE_WIDTH,
            )
        }
    }

    private fun participantWidth(participant: ZenUmlParticipant): Double =
        participantWidths.getOrPut(participant.name) {
            val iconWidth = if (participant.type != null) ZEN_UML_PARTICIPANT_ICON_WIDTH else 0.0
            val emojiWidth = if (participant.emoji != null) 24.0 else 0.0
            max(
                widthProvider.measure(
                    participant.displayName,
                    ZenUmlTextType.ParticipantName,
                ) + iconWidth + emojiWidth,
                ZEN_UML_MIN_PARTICIPANT_WIDTH,
            ) + ZEN_UML_MARGIN
        }

    private fun messageSignature(message: ZenUmlMessage): String =
        if (message.isSelf && message.assignment != null) {
            "${message.assignment.text} = ${message.label}"
        } else {
            message.label
        }

    private fun collectMessages(statements: List<ZenUmlStatement>): List<ZenUmlMessage> =
        buildList {
            statements.forEach { statement ->
                when (statement) {
                    is ZenUmlMessage -> {
                        add(statement)
                        addAll(collectMessages(statement.body))
                    }
                    is ZenUmlFragment -> {
                        statement.sections.forEach { section ->
                            addAll(collectMessages(section.statements))
                        }
                    }
                    is ZenUmlDivider -> Unit
                }
            }
        }
}

/**
 * @zenuml/core 3.49.2:
 * src/positioning/david/DavidEisenstat.ts -> find_optimal.
 */
internal fun findOptimal(matrix: Array<DoubleArray>): List<Double> {
    val graph = graphFromMatrix(matrix)
    val gaps = MutableList(max(matrix.size - 1, 0)) { Dual(position = 0.0, velocity = 1.0) }
    while (true) {
        val (delta, table) = longestPathTable(graph, gaps)
        if (delta == Double.POSITIVE_INFINITY) {
            return table.map { entry -> entry.maximum.position }
        }
        if (table.lastOrNull()?.maximum?.velocity?.let { velocity -> velocity > 0.0 } == true) {
            freezeCriticalGaps(table, gaps)
        } else {
            advanceGaps(gaps, delta)
        }
    }
}

private data class Dual(
    var position: Double,
    var velocity: Double,
)

private data class ConstraintEdge(
    val index: Int,
    val length: Dual,
)

private data class LongestPathEntry(
    val argument: Int?,
    val maximum: Dual,
)

private data class LongestPathResult(
    val delta: Double,
    val table: List<LongestPathEntry>,
)

private fun graphFromMatrix(matrix: Array<DoubleArray>): List<List<ConstraintEdge>> =
    matrix.indices.map { right ->
        buildList {
            for (left in 0 until right) {
                val length = matrix[left][right]
                if (length > 0.0) {
                    add(ConstraintEdge(left, Dual(length, 0.0)))
                }
            }
        }
    }

private fun longestPathTable(
    graph: List<List<ConstraintEdge>>,
    gaps: List<Dual>,
): LongestPathResult {
    var delta = Double.POSITIVE_INFINITY
    var maximum = Dual(0.0, 0.0)
    val table = mutableListOf<LongestPathEntry>()
    graph.indices.forEach { right ->
        var argument: Int? = null
        if (right > 0) {
            maximum = maximum + gaps[right - 1]
        }
        graph[right].forEach { edge ->
            val candidate = table[edge.index].maximum + edge.length
            val comparison = dualLessThan(maximum, candidate)
            if (comparison.lessThan) {
                argument = edge.index
                maximum = candidate
            }
            delta = minOf(delta, comparison.delta)
        }
        table += LongestPathEntry(argument, maximum)
    }
    return LongestPathResult(delta, table)
}

private data class DualComparison(
    val lessThan: Boolean,
    val delta: Double,
)

private fun dualLessThan(left: Dual, right: Dual): DualComparison {
    val difference = left.position - right.position
    val lessThan =
        difference < -DUAL_EPSILON ||
            (abs(difference) <= DUAL_EPSILON && left.velocity < right.velocity)
    val high = if (lessThan) right else left
    val low = if (lessThan) left else right
    val delta = if (high.velocity < low.velocity) {
        (high.position - low.position) / (low.velocity - high.velocity)
    } else {
        Double.POSITIVE_INFINITY
    }
    return DualComparison(lessThan, delta)
}

private fun freezeCriticalGaps(
    table: List<LongestPathEntry>,
    gaps: List<Dual>,
) {
    var index = table.lastIndex
    while (index > 0) {
        val argument = table[index].argument
        if (argument != null) {
            index = argument
        } else {
            index--
            gaps[index].velocity = 0.0
        }
    }
}

private fun advanceGaps(
    gaps: List<Dual>,
    delta: Double,
) {
    gaps.forEach { gap ->
        gap.position += gap.velocity * delta
    }
}

private operator fun Dual.plus(other: Dual): Dual =
    Dual(position + other.position, velocity + other.velocity)

private const val DUAL_EPSILON: Double = 1.4901161193847656e-8
