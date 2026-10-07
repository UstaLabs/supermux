package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kotlin source port of Mermaid 12.0.0 edge path generation:
 * - rendering-util/rendering-elements/edges.js
 * - utils/lineWithOffset.ts
 * - d3-shape/src/curve/linear.js
 * - d3-shape/src/curve/step.js
 */
internal object MermaidEdgePathPort {
    /**
     * Mermaid: rendering-util/rendering-elements/edges.js -> positionEdgeLabel
     * and utils.ts -> calcLabelPosition / isLabelCoordinateInPath.
     */
    fun positionEdgeLabel(
        layoutAnchor: ScenePoint,
        points: List<ScenePoint>,
        commands: List<ScenePathCommand>,
    ): ScenePoint {
        val middlePoint = points.getOrNull(points.size / 2) ?: return layoutAnchor
        return if (isLabelCoordinateInPath(middlePoint, commands)) {
            layoutAnchor
        } else {
            calcLabelPosition(points)
        }
    }

    fun generate(
        points: List<ScenePoint>,
        curve: String,
        arrowStart: SceneArrowHead,
        arrowEnd: SceneArrowHead,
    ): GMResult<List<ScenePathCommand>, MermaidError> {
        if (points.size < 2) {
            return GMResult.Err(
                MermaidError.Layout("Mermaid edge path requires at least two points"),
            )
        }

        return when (curve) {
            "rounded" -> GMResult.Ok(
                generateRoundedPath(
                    points = applyMarkerOffsetsToPoints(points, arrowStart, arrowEnd),
                    radius = ROUNDING_RADIUS,
                ),
            )
            in D3_CURVES -> GMResult.Ok(
                D3CurvePort.generate(
                    points = applyLineOffsets(fixCorners(points), arrowStart, arrowEnd),
                    curve = curve,
                ),
            )
            else -> GMResult.Ok(
                D3CurvePort.generate(
                    points = applyLineOffsets(fixCorners(points), arrowStart, arrowEnd),
                    curve = DEFAULT_CURVE,
                ),
            )
        }
    }

    private fun isLabelCoordinateInPath(
        point: ScenePoint,
        commands: List<ScenePathCommand>,
    ): Boolean {
        val roundedX = point.x.roundToInt().toString()
        val roundedY = point.y.roundToInt().toString()
        return commands.asSequence()
            .flatMap { command -> command.coordinates() }
            .map { coordinate -> coordinate.roundToInt().toString() }
            .any { coordinate ->
                coordinate.contains(roundedX) || coordinate.contains(roundedY)
            }
    }

    private fun calcLabelPosition(points: List<ScenePoint>): ScenePoint {
        if (points.size == 1) return points.first()
        val lengths = points.zipWithNext { first, second ->
            hypot(second.x - first.x, second.y - first.y)
        }
        val target = lengths.sum() / 2f
        var traversed = 0f
        lengths.forEachIndexed { index, length ->
            if (traversed + length >= target && length > 0f) {
                val ratio = (target - traversed) / length
                return ScenePoint(
                    x = points[index].x + (points[index + 1].x - points[index].x) * ratio,
                    y = points[index].y + (points[index + 1].y - points[index].y) * ratio,
                )
            }
            traversed += length
        }
        return points.lastOrNull() ?: ScenePoint(0f, 0f)
    }

    private fun ScenePathCommand.coordinates(): Sequence<Float> = when (this) {
        is ScenePathCommand.MoveTo -> sequenceOf(point.x, point.y)
        is ScenePathCommand.LineTo -> sequenceOf(point.x, point.y)
        is ScenePathCommand.QuadraticTo -> sequenceOf(control.x, control.y, end.x, end.y)
        is ScenePathCommand.CubicTo -> sequenceOf(
            control1.x,
            control1.y,
            control2.x,
            control2.y,
            end.x,
            end.y,
        )
        is ScenePathCommand.ArcTo -> sequenceOf(end.x, end.y)
    }

    private fun generateRoundedPath(
        points: List<ScenePoint>,
        radius: Float,
    ): List<ScenePathCommand> {
        val commands = mutableListOf<ScenePathCommand>()
        points.forEachIndexed { index, current ->
            val previous = points.getOrNull(index - 1)
            val next = points.getOrNull(index + 1)
            when {
                index == 0 -> commands += ScenePathCommand.MoveTo(current)
                index == points.lastIndex -> commands += ScenePathCommand.LineTo(current)
                previous == null || next == null -> commands += ScenePathCommand.LineTo(current)
                else -> {
                    val incomingX = current.x - previous.x
                    val incomingY = current.y - previous.y
                    val outgoingX = next.x - current.x
                    val outgoingY = next.y - current.y
                    val incomingLength = hypot(incomingX, incomingY)
                    val outgoingLength = hypot(outgoingX, outgoingY)

                    if (incomingLength < EPSILON || outgoingLength < EPSILON) {
                        commands += ScenePathCommand.LineTo(current)
                        return@forEachIndexed
                    }

                    val incomingNormalX = incomingX / incomingLength
                    val incomingNormalY = incomingY / incomingLength
                    val outgoingNormalX = outgoingX / outgoingLength
                    val outgoingNormalY = outgoingY / outgoingLength
                    val dot = incomingNormalX * outgoingNormalX +
                        incomingNormalY * outgoingNormalY
                    val angle = acos(dot.coerceIn(-1f, 1f))

                    if (angle < EPSILON || abs(PI.toFloat() - angle) < EPSILON) {
                        commands += ScenePathCommand.LineTo(current)
                        return@forEachIndexed
                    }

                    val cutLength = min(
                        radius / sin(angle / 2f),
                        min(incomingLength / 2f, outgoingLength / 2f),
                    )
                    val curveStart = ScenePoint(
                        x = current.x - incomingNormalX * cutLength,
                        y = current.y - incomingNormalY * cutLength,
                    )
                    val curveEnd = ScenePoint(
                        x = current.x + outgoingNormalX * cutLength,
                        y = current.y + outgoingNormalY * cutLength,
                    )
                    commands += ScenePathCommand.LineTo(curveStart)
                    commands += ScenePathCommand.QuadraticTo(
                        control = current,
                        end = curveEnd,
                    )
                }
            }
        }
        return commands
    }

    private fun applyMarkerOffsetsToPoints(
        points: List<ScenePoint>,
        arrowStart: SceneArrowHead,
        arrowEnd: SceneArrowHead,
    ): List<ScenePoint> {
        val adjusted = points.toMutableList()
        markerOffset(arrowStart).takeIf { offset -> offset > 0f }?.let { offset ->
            val first = points[0]
            val second = points[1]
            val angle = atan2(second.y - first.y, second.x - first.x)
            adjusted[0] = ScenePoint(
                x = first.x + offset * cos(angle),
                y = first.y + offset * sin(angle),
            )
        }
        markerOffset(arrowEnd).takeIf { offset -> offset > 0f }?.let { offset ->
            val lastIndex = points.lastIndex
            val last = points[lastIndex]
            val previous = points[lastIndex - 1]
            val angle = atan2(last.y - previous.y, last.x - previous.x)
            adjusted[lastIndex] = ScenePoint(
                x = last.x - offset * cos(angle),
                y = last.y - offset * sin(angle),
            )
        }
        return adjusted
    }

    private fun applyLineOffsets(
        points: List<ScenePoint>,
        arrowStart: SceneArrowHead,
        arrowEnd: SceneArrowHead,
    ): List<ScenePoint> = points.mapIndexed { index, point ->
        ScenePoint(
            x = lineXWithOffset(points, point, index, arrowStart, arrowEnd),
            y = lineYWithOffset(points, point, index, arrowStart, arrowEnd),
        )
    }

    private fun lineXWithOffset(
        points: List<ScenePoint>,
        point: ScenePoint,
        index: Int,
        arrowStart: SceneArrowHead,
        arrowEnd: SceneArrowHead,
    ): Float {
        var offset = 0f
        val directionIsRight = points.first().x >= points.last().x
        val startMarkerHeight = markerOffset(arrowStart).takeIf { offset -> offset > 0f }
        val endMarkerHeight = markerOffset(arrowEnd).takeIf { offset -> offset > 0f }

        if (index == 0 && startMarkerHeight != null) {
            val (angle, deltaX) = deltaAndLineAngle(points[0], points[1])
            offset = startMarkerHeight * cos(angle) * if (deltaX >= 0f) 1f else -1f
        } else if (index == points.lastIndex && endMarkerHeight != null) {
            val (angle, deltaX) = deltaAndLineAngle(
                points[points.lastIndex],
                points[points.lastIndex - 1],
            )
            offset = endMarkerHeight * cos(angle) * if (deltaX >= 0f) 1f else -1f
        }

        val differenceToEnd = abs(point.x - points.last().x)
        val differenceInYEnd = abs(point.y - points.last().y)
        val differenceToStart = abs(point.x - points.first().x)
        val differenceInYStart = abs(point.y - points.first().y)
        if (
            endMarkerHeight != null &&
            differenceToEnd < endMarkerHeight &&
            differenceToEnd > 0f &&
            differenceInYEnd < endMarkerHeight
        ) {
            var adjustment = endMarkerHeight + EXTRA_MARKER_ROOM - differenceToEnd
            adjustment *= if (directionIsRight) -1f else 1f
            offset -= adjustment
        }
        if (
            startMarkerHeight != null &&
            differenceToStart < startMarkerHeight &&
            differenceToStart > 0f &&
            differenceInYStart < startMarkerHeight
        ) {
            var adjustment = startMarkerHeight + EXTRA_MARKER_ROOM - differenceToStart
            adjustment *= if (directionIsRight) -1f else 1f
            offset += adjustment
        }
        return point.x + offset
    }

    private fun lineYWithOffset(
        points: List<ScenePoint>,
        point: ScenePoint,
        index: Int,
        arrowStart: SceneArrowHead,
        arrowEnd: SceneArrowHead,
    ): Float {
        var offset = 0f
        val directionIsUp = points.first().y >= points.last().y
        val startMarkerHeight = markerOffset(arrowStart).takeIf { offset -> offset > 0f }
        val endMarkerHeight = markerOffset(arrowEnd).takeIf { offset -> offset > 0f }

        if (index == 0 && startMarkerHeight != null) {
            val (angle, _, deltaY) = deltaAndLineAngle(points[0], points[1])
            offset = startMarkerHeight * abs(sin(angle)) * if (deltaY >= 0f) 1f else -1f
        } else if (index == points.lastIndex && endMarkerHeight != null) {
            val (angle, _, deltaY) = deltaAndLineAngle(
                points[points.lastIndex],
                points[points.lastIndex - 1],
            )
            offset = endMarkerHeight * abs(sin(angle)) * if (deltaY >= 0f) 1f else -1f
        }

        val differenceToEnd = abs(point.y - points.last().y)
        val differenceInXEnd = abs(point.x - points.last().x)
        val differenceToStart = abs(point.y - points.first().y)
        val differenceInXStart = abs(point.x - points.first().x)
        if (
            endMarkerHeight != null &&
            differenceToEnd < endMarkerHeight &&
            differenceToEnd > 0f &&
            differenceInXEnd < endMarkerHeight
        ) {
            var adjustment = endMarkerHeight + EXTRA_MARKER_ROOM - differenceToEnd
            adjustment *= if (directionIsUp) -1f else 1f
            offset -= adjustment
        }
        if (
            startMarkerHeight != null &&
            differenceToStart < startMarkerHeight &&
            differenceToStart > 0f &&
            differenceInXStart < startMarkerHeight
        ) {
            var adjustment = startMarkerHeight + EXTRA_MARKER_ROOM - differenceToStart
            adjustment *= if (directionIsUp) -1f else 1f
            offset += adjustment
        }
        return point.y + offset
    }

    private fun deltaAndLineAngle(
        first: ScenePoint,
        second: ScenePoint,
    ): DeltaAndAngle {
        val deltaX = second.x - first.x
        val deltaY = second.y - first.y
        return DeltaAndAngle(
            angle = atan(deltaY / deltaX),
            deltaX = deltaX,
            deltaY = deltaY,
        )
    }

    private fun fixCorners(points: List<ScenePoint>): List<ScenePoint> {
        val cornerPositions = points.indices.filter { index ->
            if (index == 0 || index == points.lastIndex) {
                false
            } else {
                val previous = points[index - 1]
                val current = points[index]
                val next = points[index + 1]
                (
                    previous.x == current.x &&
                        current.y == next.y &&
                        abs(current.x - next.x) > ROUNDING_RADIUS &&
                        abs(current.y - previous.y) > ROUNDING_RADIUS
                    ) || (
                    previous.y == current.y &&
                        current.x == next.x &&
                        abs(current.x - previous.x) > ROUNDING_RADIUS &&
                        abs(current.y - next.y) > ROUNDING_RADIUS
                    )
            }
        }.toSet()

        return buildList {
            points.forEachIndexed { index, point ->
                if (index !in cornerPositions) {
                    add(point)
                    return@forEachIndexed
                }

                val previous = points[index - 1]
                val next = points[index + 1]
                val newPrevious = findAdjacentPoint(previous, point, ROUNDING_RADIUS)
                val newNext = findAdjacentPoint(next, point, ROUNDING_RADIUS)
                val xDifference = newNext.x - newPrevious.x
                val yDifference = newNext.y - newPrevious.y
                add(newPrevious)

                var newCorner = point
                if (
                    abs(next.x - previous.x) > 10f &&
                    abs(next.y - previous.y) >= 10f
                ) {
                    val arcOffset = sqrt(2f) * 2f
                    newCorner = if (point.x == newPrevious.x) {
                        ScenePoint(
                            x = if (xDifference < 0f) {
                                newPrevious.x - ROUNDING_RADIUS + arcOffset
                            } else {
                                newPrevious.x + ROUNDING_RADIUS - arcOffset
                            },
                            y = if (yDifference < 0f) {
                                newPrevious.y - arcOffset
                            } else {
                                newPrevious.y + arcOffset
                            },
                        )
                    } else {
                        ScenePoint(
                            x = if (xDifference < 0f) {
                                newPrevious.x - arcOffset
                            } else {
                                newPrevious.x + arcOffset
                            },
                            y = if (yDifference < 0f) {
                                newPrevious.y - ROUNDING_RADIUS + arcOffset
                            } else {
                                newPrevious.y + ROUNDING_RADIUS - arcOffset
                            },
                        )
                    }
                }
                add(newCorner)
                add(newNext)
            }
        }
    }

    private fun findAdjacentPoint(
        pointA: ScenePoint,
        pointB: ScenePoint,
        distance: Float,
    ): ScenePoint {
        val xDifference = pointB.x - pointA.x
        val yDifference = pointB.y - pointA.y
        val length = sqrt(xDifference * xDifference + yDifference * yDifference)
        val ratio = distance / length
        return ScenePoint(
            x = pointB.x - ratio * xDifference,
            y = pointB.y - ratio * yDifference,
        )
    }

    private fun markerOffset(arrow: SceneArrowHead): Float =
        MermaidMarkerPort.pathOffset(arrow)

    private data class DeltaAndAngle(
        val angle: Float,
        val deltaX: Float,
        val deltaY: Float,
    )

    private const val EPSILON = 1e-5f
    private const val ROUNDING_RADIUS = 5f
    private const val EXTRA_MARKER_ROOM = 1f
    private const val DEFAULT_CURVE = "basis"
    private val D3_CURVES = setOf(
        "linear",
        "basis",
        "cardinal",
        "bumpX",
        "bumpY",
        "catmullRom",
        "monotoneX",
        "monotoneY",
        "natural",
        "step",
        "stepBefore",
        "stepAfter",
    )
}
