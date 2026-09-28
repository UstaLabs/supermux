package com.swithun.cmpmermaid.core.venn.upstream.vennjs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class VennPoint(
    var x: Double,
    var y: Double,
    var angle: Double = 0.0,
    var parentIndices: List<Int> = emptyList(),
)

internal data class VennCircle(
    var x: Double,
    var y: Double,
    var radius: Double,
    val setId: String,
    var size: Double = 0.0,
)

internal data class VennArc(
    val circle: VennCircle,
    val width: Double,
    val p1: VennPoint,
    val p2: VennPoint,
    val large: Boolean = width > circle.radius,
    val sweep: Boolean = true,
)

internal data class VennAreaStats(
    val area: Double,
    val arcArea: Double,
    val polygonArea: Double,
    val arcs: List<VennArc>,
    val innerPoints: List<VennPoint>,
    val intersectionPoints: List<VennPoint>,
)

/**
 * Kotlin translation of @upsetjs/venn.js 2.0.0:
 * src/circleintersection.js.
 */
internal object VennGeometry {
    fun intersectionArea(circles: List<VennCircle>): VennAreaStats {
        if (circles.isEmpty()) {
            return VennAreaStats(
                area = 0.0,
                arcArea = 0.0,
                polygonArea = 0.0,
                arcs = emptyList(),
                innerPoints = emptyList(),
                intersectionPoints = emptyList(),
            )
        }
        val intersectionPoints = getIntersectionPoints(circles)
        val innerPoints = intersectionPoints
            .filter { point -> containedInCircles(point, circles) }
            .toMutableList()
        var arcArea = 0.0
        var polygonArea = 0.0
        val arcs = mutableListOf<VennArc>()

        if (innerPoints.size > 1) {
            val center = getCenter(innerPoints)
            innerPoints.forEach { point ->
                point.angle = atan2(point.x - center.x, point.y - center.y)
            }
            innerPoints.sortByDescending(VennPoint::angle)
            var previous = innerPoints.last()
            innerPoints.forEach { point ->
                polygonArea += (previous.x + point.x) * (point.y - previous.y)
                val middle = VennPoint(
                    x = (point.x + previous.x) / 2.0,
                    y = (point.y + previous.y) / 2.0,
                )
                var selected: VennArc? = null
                point.parentIndices.forEach { parentIndex ->
                    if (parentIndex in previous.parentIndices) {
                        val circle = circles[parentIndex]
                        val firstAngle = atan2(
                            point.x - circle.x,
                            point.y - circle.y,
                        )
                        val secondAngle = atan2(
                            previous.x - circle.x,
                            previous.y - circle.y,
                        )
                        var difference = secondAngle - firstAngle
                        if (difference < 0.0) {
                            difference += 2.0 * PI
                        }
                        val middleAngle = secondAngle - difference / 2.0
                        var width = distance(
                            middle,
                            VennPoint(
                                x = circle.x + circle.radius * sin(middleAngle),
                                y = circle.y + circle.radius * cos(middleAngle),
                            ),
                        )
                        if (width > circle.radius * 2.0) {
                            width = circle.radius * 2.0
                        }
                        if (selected?.width?.let { current -> current > width } != false) {
                            selected = VennArc(
                                circle = circle,
                                width = width,
                                p1 = point,
                                p2 = previous,
                            )
                        }
                    }
                }
                selected?.let { arc ->
                    arcs += arc
                    arcArea += circleArea(arc.circle.radius, arc.width)
                    previous = point
                }
            }
        } else {
            var smallest = circles.first()
            circles.drop(1).forEach { circle ->
                if (circle.radius < smallest.radius) {
                    smallest = circle
                }
            }
            val disjoint = circles.any { circle ->
                distance(circle, smallest) > abs(smallest.radius - circle.radius)
            }
            if (!disjoint) {
                arcArea = smallest.radius * smallest.radius * PI
                arcs += VennArc(
                    circle = smallest,
                    p1 = VennPoint(smallest.x, smallest.y + smallest.radius),
                    p2 = VennPoint(
                        smallest.x - SMALL,
                        smallest.y + smallest.radius,
                    ),
                    width = smallest.radius * 2.0,
                    large = true,
                    sweep = true,
                )
            }
        }
        polygonArea /= 2.0
        return VennAreaStats(
            area = arcArea + polygonArea,
            arcArea = arcArea,
            polygonArea = polygonArea,
            arcs = arcs,
            innerPoints = innerPoints,
            intersectionPoints = intersectionPoints,
        )
    }

    fun containedInCircles(
        point: VennPoint,
        circles: List<VennCircle>,
    ): Boolean = circles.all { circle -> distance(circle, point) < circle.radius + SMALL }

    fun circleArea(
        radius: Double,
        width: Double,
    ): Double = radius * radius * acos(1.0 - width / radius) -
        (radius - width) * sqrt(width * (2.0 * radius - width))

    fun distance(
        first: VennPoint,
        second: VennPoint,
    ): Double = sqrt(
        (first.x - second.x) * (first.x - second.x) +
            (first.y - second.y) * (first.y - second.y),
    )

    fun distance(
        first: VennCircle,
        second: VennCircle,
    ): Double = sqrt(
        (first.x - second.x) * (first.x - second.x) +
            (first.y - second.y) * (first.y - second.y),
    )

    fun distance(
        first: VennCircle,
        second: VennPoint,
    ): Double = sqrt(
        (first.x - second.x) * (first.x - second.x) +
            (first.y - second.y) * (first.y - second.y),
    )

    fun circleOverlap(
        firstRadius: Double,
        secondRadius: Double,
        distance: Double,
    ): Double {
        if (distance >= firstRadius + secondRadius) {
            return 0.0
        }
        if (distance <= abs(firstRadius - secondRadius)) {
            return PI * min(firstRadius, secondRadius) * min(firstRadius, secondRadius)
        }
        val firstWidth = firstRadius -
            (
                distance * distance -
                    secondRadius * secondRadius +
                    firstRadius * firstRadius
                ) / (2.0 * distance)
        val secondWidth = secondRadius -
            (
                distance * distance -
                    firstRadius * firstRadius +
                    secondRadius * secondRadius
                ) / (2.0 * distance)
        return circleArea(firstRadius, firstWidth) + circleArea(secondRadius, secondWidth)
    }

    fun circleCircleIntersection(
        first: VennCircle,
        second: VennCircle,
    ): List<VennPoint> {
        val distance = distance(first, second)
        val firstRadius = first.radius
        val secondRadius = second.radius
        if (
            distance >= firstRadius + secondRadius ||
            distance <= abs(firstRadius - secondRadius)
        ) {
            return emptyList()
        }
        val along = (
            firstRadius * firstRadius -
                secondRadius * secondRadius +
                distance * distance
            ) / (2.0 * distance)
        val perpendicular = sqrt(firstRadius * firstRadius - along * along)
        val centerX = first.x + along * (second.x - first.x) / distance
        val centerY = first.y + along * (second.y - first.y) / distance
        val offsetX = -(second.y - first.y) * perpendicular / distance
        val offsetY = -(second.x - first.x) * perpendicular / distance
        return listOf(
            VennPoint(x = centerX + offsetX, y = centerY - offsetY),
            VennPoint(x = centerX - offsetX, y = centerY + offsetY),
        )
    }

    fun getCenter(points: List<VennPoint>): VennPoint {
        var x = 0.0
        var y = 0.0
        points.forEach { point ->
            x += point.x
            y += point.y
        }
        return VennPoint(x = x / points.size, y = y / points.size)
    }

    private fun getIntersectionPoints(circles: List<VennCircle>): List<VennPoint> = buildList {
        for (first in 0 until circles.lastIndex) {
            for (second in first + 1 until circles.size) {
                circleCircleIntersection(circles[first], circles[second]).forEach { point ->
                    add(point.copy(parentIndices = listOf(first, second)))
                }
            }
        }
    }

    private const val SMALL = 1e-10
}
