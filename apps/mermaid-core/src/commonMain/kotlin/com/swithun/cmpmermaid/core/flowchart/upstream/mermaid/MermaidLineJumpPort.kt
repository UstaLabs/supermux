package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidElkLineHops
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Kotlin translation of Mermaid 12.0.0 rendering-elements/lineJump.ts.
 *
 * Crossing ownership and path rewriting stay platform independent. Compose
 * only interprets the resulting line, quadratic, arc, and move commands.
 */
internal object MermaidLineJumpPort {
    fun apply(
        paths: List<ScenePath>,
        style: MermaidElkLineHops,
        jumpRadius: Float = JUMP_RADIUS,
    ): List<ScenePath> {
        if (style == MermaidElkLineHops.Disabled) {
            return paths
        }
        val crossings = findEdgeIntersections(paths)
        if (crossings.isEmpty()) {
            return paths
        }
        val jumpsByEdge = crossings.groupBy(Crossing::jumpEdgeIndex)
        return paths.mapIndexed { index, path ->
            val jumps = jumpsByEdge[index].orEmpty()
            if (jumps.isEmpty() || !curveSupportsLineHops(path.curve)) {
                path
            } else {
                path.copy(
                    commands = rewriteEdgePath(
                        edge = path,
                        jumps = jumps,
                        style = style,
                        jumpRadius = jumpRadius,
                    ),
                )
            }
        }
    }

    private fun findEdgeIntersections(edges: List<ScenePath>): List<Crossing> = buildList {
        edges.forEachIndexed { firstIndex, first ->
            val firstSegments = buildSegmentList(first.points)
            for (secondIndex in firstIndex + 1 until edges.size) {
                val second = edges[secondIndex]
                val secondSegments = buildSegmentList(second.points)
                firstSegments.forEachIndexed { firstSegmentIndex, firstSegment ->
                    secondSegments.forEachIndexed { secondSegmentIndex, secondSegment ->
                        val hit = segmentIntersection(firstSegment, secondSegment)
                            ?: return@forEachIndexed
                        if (
                            crossingSitsInRoundedCorner(
                                first,
                                firstSegmentIndex,
                                hit.firstT,
                            ) ||
                            crossingSitsInRoundedCorner(
                                second,
                                secondSegmentIndex,
                                hit.secondT,
                            )
                        ) {
                            return@forEachIndexed
                        }
                        val firstHorizontal = firstSegment.isHorizontal()
                        val secondHorizontal = secondSegment.isHorizontal()
                        if (firstHorizontal != secondHorizontal && firstHorizontal) {
                            add(
                                Crossing(
                                    jumpEdgeIndex = firstIndex,
                                    segmentIndex = firstSegmentIndex,
                                    t = hit.firstT,
                                    point = hit.point,
                                ),
                            )
                        } else {
                            add(
                                Crossing(
                                    jumpEdgeIndex = secondIndex,
                                    segmentIndex = secondSegmentIndex,
                                    t = hit.secondT,
                                    point = hit.point,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun segmentIntersection(
        first: Segment,
        second: Segment,
    ): Intersection? {
        val firstDx = first.end.x - first.start.x
        val firstDy = first.end.y - first.start.y
        val secondDx = second.end.x - second.start.x
        val secondDy = second.end.y - second.start.y
        val denominator = firstDx * secondDy - firstDy * secondDx
        if (denominator == 0f) {
            return null
        }
        val dx = second.start.x - first.start.x
        val dy = second.start.y - first.start.y
        val firstT = (dx * secondDy - dy * secondDx) / denominator
        val secondT = (dx * firstDy - dy * firstDx) / denominator
        if (
            firstT <= ENDPOINT_EPSILON ||
            firstT >= 1f - ENDPOINT_EPSILON ||
            secondT <= ENDPOINT_EPSILON ||
            secondT >= 1f - ENDPOINT_EPSILON
        ) {
            return null
        }
        return Intersection(
            point = ScenePoint(
                x = first.start.x + firstT * firstDx,
                y = first.start.y + firstT * firstDy,
            ),
            firstT = firstT,
            secondT = secondT,
        )
    }

    private fun crossingSitsInRoundedCorner(
        edge: ScenePath,
        segmentIndex: Int,
        t: Float,
    ): Boolean {
        if (edge.curve != "rounded") {
            return false
        }
        val start = edge.points.getOrNull(segmentIndex) ?: return false
        val end = edge.points.getOrNull(segmentIndex + 1) ?: return false
        val segmentLength = start.distanceTo(end)
        val distance = t * segmentLength
        val entering = if (segmentIndex > 0) {
            computeRoundedCorner(
                edge.points[segmentIndex - 1],
                start,
                end,
                ROUNDED_CORNER_RADIUS,
            )
        } else {
            null
        }
        if (entering != null && distance < entering.cutLength) {
            return true
        }
        val leaving = if (segmentIndex + 2 < edge.points.size) {
            computeRoundedCorner(
                start,
                end,
                edge.points[segmentIndex + 2],
                ROUNDED_CORNER_RADIUS,
            )
        } else {
            null
        }
        return leaving != null && segmentLength - distance < leaving.cutLength
    }

    private fun rewriteEdgePath(
        edge: ScenePath,
        jumps: List<Crossing>,
        style: MermaidElkLineHops,
        jumpRadius: Float,
    ): List<ScenePathCommand> {
        val points = applyMarkerOffsets(edge.points, edge)
        val segments = buildSegmentList(points)
        val jumpsBySegment = linkedMapOf<Int, MutableList<Jump>>()
        jumps.forEach { crossing ->
            val segment = segments.getOrNull(crossing.segmentIndex)
                ?: return@forEach
            jumpsBySegment.getOrPut(crossing.segmentIndex, ::mutableListOf) += Jump(
                t = crossing.t,
                point = crossing.point,
                distance = crossing.t * segment.length,
                radius = jumpRadius,
            )
        }

        val commands = mutableListOf<ScenePathCommand>(
            ScenePathCommand.MoveTo(points.first()),
        )
        segments.forEachIndexed { index, segment ->
            val unitX = if (segment.length == 0f) {
                0f
            } else {
                (segment.end.x - segment.start.x) / segment.length
            }
            val unitY = if (segment.length == 0f) {
                0f
            } else {
                (segment.end.y - segment.start.y) / segment.length
            }
            val clockwise = segment.arcSweepsClockwise()
            var segmentStartConsumed = 0f
            if (edge.curve == "rounded" && index > 0) {
                computeRoundedCorner(
                    points[index - 1],
                    points[index],
                    points.getOrElse(index + 1) { points[index] },
                    ROUNDED_CORNER_RADIUS,
                )?.let { corner ->
                    segmentStartConsumed = corner.cutLength
                }
            }
            var segmentEndStop = segment.length
            val upcomingCorner = if (
                edge.curve == "rounded" &&
                index < segments.lastIndex
            ) {
                computeRoundedCorner(
                    points[index],
                    points[index + 1],
                    points.getOrElse(index + 2) { points[index + 1] },
                    ROUNDED_CORNER_RADIUS,
                )
            } else {
                null
            }
            upcomingCorner?.let { corner ->
                segmentEndStop = segment.length - corner.cutLength
            }

            val minimumRadius = jumpRadius * MIN_USEFUL_RADIUS_RATIO
            val segmentJumps = jumpsBySegment[index]
                .orEmpty()
                .sortedBy(Jump::t)
                .mapNotNull { jump ->
                    val room = min(
                        jump.distance - segmentStartConsumed,
                        segmentEndStop - jump.distance,
                    ) - CORNER_JUMP_CLEARANCE
                    jump.copy(radius = min(jump.radius, room))
                        .takeIf { adjusted -> adjusted.radius >= minimumRadius }
                }
                .toMutableList()
            for (jumpIndex in 0 until segmentJumps.lastIndex) {
                val first = segmentJumps[jumpIndex]
                val second = segmentJumps[jumpIndex + 1]
                val gap = second.distance - first.distance
                if (first.radius + second.radius > gap) {
                    val halfGap = gap / 2f
                    segmentJumps[jumpIndex] =
                        first.copy(radius = min(first.radius, halfGap))
                    segmentJumps[jumpIndex + 1] =
                        second.copy(radius = min(second.radius, halfGap))
                }
            }
            segmentJumps
                .filter { jump -> jump.radius >= minimumRadius }
                .forEach { jump ->
                    val before = ScenePoint(
                        x = jump.point.x - unitX * jump.radius,
                        y = jump.point.y - unitY * jump.radius,
                    )
                    val after = ScenePoint(
                        x = jump.point.x + unitX * jump.radius,
                        y = jump.point.y + unitY * jump.radius,
                    )
                    commands += ScenePathCommand.LineTo(before)
                    commands += when (style) {
                        MermaidElkLineHops.Arc -> ScenePathCommand.ArcTo(
                            radius = jump.radius,
                            end = after,
                            clockwise = clockwise,
                        )
                        MermaidElkLineHops.Gap -> ScenePathCommand.MoveTo(after)
                        MermaidElkLineHops.Disabled ->
                            ScenePathCommand.LineTo(after)
                    }
                }
            if (upcomingCorner == null) {
                commands += ScenePathCommand.LineTo(segment.end)
            } else {
                commands += ScenePathCommand.LineTo(upcomingCorner.start)
                commands += ScenePathCommand.QuadraticTo(
                    control = upcomingCorner.control,
                    end = upcomingCorner.end,
                )
            }
        }
        return commands
    }

    private fun applyMarkerOffsets(
        points: List<ScenePoint>,
        edge: ScenePath,
    ): List<ScenePoint> {
        if (points.size < 2) {
            return points
        }
        val adjusted = points.toMutableList()
        markerOffset(edge.arrowStart).takeIf { offset -> offset > 0f }?.let { offset ->
            val start = points.first()
            val next = points[1]
            val angle = kotlin.math.atan2(next.y - start.y, next.x - start.x)
            adjusted[0] = ScenePoint(
                x = start.x + offset * cos(angle),
                y = start.y + offset * sin(angle),
            )
        }
        markerOffset(edge.arrowEnd).takeIf { offset -> offset > 0f }?.let { offset ->
            val previous = points[points.lastIndex - 1]
            val end = points.last()
            val angle = kotlin.math.atan2(end.y - previous.y, end.x - previous.x)
            adjusted[adjusted.lastIndex] = ScenePoint(
                x = end.x - offset * cos(angle),
                y = end.y - offset * sin(angle),
            )
        }
        return adjusted
    }

    private fun computeRoundedCorner(
        previous: ScenePoint,
        current: ScenePoint,
        next: ScenePoint,
        radius: Float,
    ): RoundedCorner? {
        val firstDx = current.x - previous.x
        val firstDy = current.y - previous.y
        val secondDx = next.x - current.x
        val secondDy = next.y - current.y
        val firstLength = hypot(firstDx, firstDy)
        val secondLength = hypot(secondDx, secondDy)
        if (firstLength < CORNER_EPSILON || secondLength < CORNER_EPSILON) {
            return null
        }
        val firstNormalX = firstDx / firstLength
        val firstNormalY = firstDy / firstLength
        val secondNormalX = secondDx / secondLength
        val secondNormalY = secondDy / secondLength
        val angle = acos(
            (firstNormalX * secondNormalX + firstNormalY * secondNormalY)
                .coerceIn(-1f, 1f),
        )
        if (angle < CORNER_EPSILON || abs(PI.toFloat() - angle) < CORNER_EPSILON) {
            return null
        }
        val cutLength = min(
            radius / sin(angle / 2f),
            min(firstLength / 2f, secondLength / 2f),
        )
        return RoundedCorner(
            start = ScenePoint(
                x = current.x - firstNormalX * cutLength,
                y = current.y - firstNormalY * cutLength,
            ),
            control = current,
            end = ScenePoint(
                x = current.x + secondNormalX * cutLength,
                y = current.y + secondNormalY * cutLength,
            ),
            cutLength = cutLength,
        )
    }

    private fun buildSegmentList(points: List<ScenePoint>): List<Segment> =
        points.zipWithNext(::Segment)

    private fun Segment.isHorizontal(): Boolean =
        abs(end.x - start.x) >= abs(end.y - start.y)

    private fun Segment.arcSweepsClockwise(): Boolean {
        val dx = end.x - start.x
        val dy = end.y - start.y
        return if (abs(dx) >= abs(dy)) dx >= 0f else dy >= 0f
    }

    private fun markerOffset(arrow: SceneArrowHead): Float =
        MermaidMarkerPort.pathOffset(arrow)

    private fun curveSupportsLineHops(curve: String): Boolean =
        curve in SUPPORTED_CURVES

    private fun ScenePoint.distanceTo(other: ScenePoint): Float =
        hypot(x - other.x, y - other.y)

    private data class Segment(
        val start: ScenePoint,
        val end: ScenePoint,
    ) {
        val length: Float = start.distanceTo(end)
    }

    private data class Intersection(
        val point: ScenePoint,
        val firstT: Float,
        val secondT: Float,
    )

    private data class Crossing(
        val jumpEdgeIndex: Int,
        val segmentIndex: Int,
        val t: Float,
        val point: ScenePoint,
    )

    private data class Jump(
        val t: Float,
        val point: ScenePoint,
        val distance: Float,
        val radius: Float,
    )

    private data class RoundedCorner(
        val start: ScenePoint,
        val control: ScenePoint,
        val end: ScenePoint,
        val cutLength: Float,
    )

    private const val JUMP_RADIUS = 6f
    private const val ROUNDED_CORNER_RADIUS = 5f
    private const val CORNER_EPSILON = 1e-5f
    private const val CORNER_JUMP_CLEARANCE = 2f
    private const val MIN_USEFUL_RADIUS_RATIO = 0.6f
    private const val ENDPOINT_EPSILON = 1e-6f
    private val SUPPORTED_CURVES = setOf(
        "linear",
        "rounded",
        "step",
        "stepBefore",
        "stepAfter",
    )
}
