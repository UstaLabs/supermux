package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Line-only Kotlin source port of d3-shape 3.2.0 curve implementations used by
 * Mermaid 12.0.0. Area and closed-curve branches are intentionally absent
 * because Mermaid's edge renderer only calls d3.line().
 */
internal object D3CurvePort {
    fun generate(
        points: List<ScenePoint>,
        curve: String,
    ): List<ScenePathCommand> {
        val sink = PathSink()
        when (curve) {
            "linear" -> linear(points, sink)
            "basis" -> basis(points, sink)
            "cardinal" -> cardinal(points, sink)
            "bumpX" -> bump(points, sink, horizontal = true)
            "bumpY" -> bump(points, sink, horizontal = false)
            "catmullRom" -> catmullRom(points, sink)
            "monotoneX" -> monotone(points, sink, reflect = false)
            "monotoneY" -> monotone(points, sink, reflect = true)
            "natural" -> natural(points, sink)
            "step" -> step(points, sink, step = 0.5)
            "stepBefore" -> step(points, sink, step = 0.0)
            "stepAfter" -> step(points, sink, step = 1.0)
        }
        return sink.commands
    }

    private fun linear(
        points: List<ScenePoint>,
        sink: PathSink,
    ) {
        points.forEachIndexed { index, point ->
            if (index == 0) {
                sink.moveTo(point.x, point.y)
            } else {
                sink.lineTo(point.x, point.y)
            }
        }
    }

    private fun basis(
        points: List<ScenePoint>,
        sink: PathSink,
    ) {
        var x0 = Double.NaN
        var x1 = Double.NaN
        var y0 = Double.NaN
        var y1 = Double.NaN
        var pointState = 0

        fun curvePoint(x: Double, y: Double) {
            sink.cubicTo(
                (2 * x0 + x1) / 3,
                (2 * y0 + y1) / 3,
                (x0 + 2 * x1) / 3,
                (y0 + 2 * y1) / 3,
                (x0 + 4 * x1 + x) / 6,
                (y0 + 4 * y1 + y) / 6,
            )
        }

        points.forEach { scenePoint ->
            val x = scenePoint.x.toDouble()
            val y = scenePoint.y.toDouble()
            when (pointState) {
                0 -> {
                    pointState = 1
                    sink.moveTo(x, y)
                }
                1 -> pointState = 2
                2 -> {
                    pointState = 3
                    sink.lineTo((5 * x0 + x1) / 6, (5 * y0 + y1) / 6)
                    curvePoint(x, y)
                }
                else -> curvePoint(x, y)
            }
            x0 = x1
            x1 = x
            y0 = y1
            y1 = y
        }

        when (pointState) {
            2 -> sink.lineTo(x1, y1)
            3 -> {
                curvePoint(x1, y1)
                sink.lineTo(x1, y1)
            }
        }
    }

    private fun cardinal(
        points: List<ScenePoint>,
        sink: PathSink,
    ) {
        val tensionFactor = 1.0 / 6.0
        var x0 = Double.NaN
        var x1 = Double.NaN
        var x2 = Double.NaN
        var y0 = Double.NaN
        var y1 = Double.NaN
        var y2 = Double.NaN
        var pointState = 0

        fun curvePoint(x: Double, y: Double) {
            sink.cubicTo(
                x1 + tensionFactor * (x2 - x0),
                y1 + tensionFactor * (y2 - y0),
                x2 + tensionFactor * (x1 - x),
                y2 + tensionFactor * (y1 - y),
                x2,
                y2,
            )
        }

        points.forEach { scenePoint ->
            val x = scenePoint.x.toDouble()
            val y = scenePoint.y.toDouble()
            when (pointState) {
                0 -> {
                    pointState = 1
                    sink.moveTo(x, y)
                }
                1 -> {
                    pointState = 2
                    x1 = x
                    y1 = y
                }
                2 -> {
                    pointState = 3
                    curvePoint(x, y)
                }
                else -> curvePoint(x, y)
            }
            x0 = x1
            x1 = x2
            x2 = x
            y0 = y1
            y1 = y2
            y2 = y
        }

        when (pointState) {
            2 -> sink.lineTo(x2, y2)
            3 -> curvePoint(x1, y1)
        }
    }

    private fun bump(
        points: List<ScenePoint>,
        sink: PathSink,
        horizontal: Boolean,
    ) {
        var previousX = Double.NaN
        var previousY = Double.NaN
        points.forEachIndexed { index, point ->
            val x = point.x.toDouble()
            val y = point.y.toDouble()
            if (index == 0) {
                sink.moveTo(x, y)
            } else if (horizontal) {
                val middleX = (previousX + x) / 2
                sink.cubicTo(middleX, previousY, middleX, y, x, y)
            } else {
                val middleY = (previousY + y) / 2
                sink.cubicTo(previousX, middleY, x, middleY, x, y)
            }
            previousX = x
            previousY = y
        }
    }

    private fun step(
        points: List<ScenePoint>,
        sink: PathSink,
        step: Double,
    ) {
        var previousX = Double.NaN
        var previousY = Double.NaN
        var pointState = 0
        points.forEach { point ->
            val x = point.x.toDouble()
            val y = point.y.toDouble()
            when (pointState) {
                0 -> {
                    pointState = 1
                    sink.moveTo(x, y)
                }
                else -> {
                    pointState = 2
                    if (step <= 0) {
                        sink.lineTo(previousX, y)
                        sink.lineTo(x, y)
                    } else {
                        val stepX = previousX * (1 - step) + x * step
                        sink.lineTo(stepX, previousY)
                        sink.lineTo(stepX, y)
                    }
                }
            }
            previousX = x
            previousY = y
        }
        if (step > 0 && step < 1 && pointState == 2) {
            sink.lineTo(previousX, previousY)
        }
    }

    private fun catmullRom(
        points: List<ScenePoint>,
        sink: PathSink,
    ) {
        val alpha = 0.5
        var x0 = Double.NaN
        var x1 = Double.NaN
        var x2 = Double.NaN
        var y0 = Double.NaN
        var y1 = Double.NaN
        var y2 = Double.NaN
        var length01A = 0.0
        var length12A = 0.0
        var length23A = 0.0
        var length01TwoA = 0.0
        var length12TwoA = 0.0
        var length23TwoA = 0.0
        var pointState = 0

        fun curvePoint(x: Double, y: Double) {
            var control1X = x1
            var control1Y = y1
            var control2X = x2
            var control2Y = y2

            if (length01A > D3_EPSILON) {
                val factor =
                    2 * length01TwoA +
                        3 * length01A * length12A +
                        length12TwoA
                val denominator = 3 * length01A * (length01A + length12A)
                control1X =
                    (control1X * factor - x0 * length12TwoA + x2 * length01TwoA) /
                    denominator
                control1Y =
                    (control1Y * factor - y0 * length12TwoA + y2 * length01TwoA) /
                    denominator
            }

            if (length23A > D3_EPSILON) {
                val factor =
                    2 * length23TwoA +
                        3 * length23A * length12A +
                        length12TwoA
                val denominator = 3 * length23A * (length23A + length12A)
                control2X =
                    (control2X * factor + x1 * length23TwoA - x * length12TwoA) /
                    denominator
                control2Y =
                    (control2Y * factor + y1 * length23TwoA - y * length12TwoA) /
                    denominator
            }
            sink.cubicTo(control1X, control1Y, control2X, control2Y, x2, y2)
        }

        points.forEach { scenePoint ->
            val x = scenePoint.x.toDouble()
            val y = scenePoint.y.toDouble()
            if (pointState != 0) {
                val x23 = x2 - x
                val y23 = y2 - y
                length23TwoA = (x23 * x23 + y23 * y23).pow(alpha)
                length23A = sqrt(length23TwoA)
            }

            when (pointState) {
                0 -> {
                    pointState = 1
                    sink.moveTo(x, y)
                }
                1 -> pointState = 2
                2 -> {
                    pointState = 3
                    curvePoint(x, y)
                }
                else -> curvePoint(x, y)
            }

            length01A = length12A
            length12A = length23A
            length01TwoA = length12TwoA
            length12TwoA = length23TwoA
            x0 = x1
            x1 = x2
            x2 = x
            y0 = y1
            y1 = y2
            y2 = y
        }

        when (pointState) {
            2 -> sink.lineTo(x2, y2)
            3 -> {
                length23A = 0.0
                length23TwoA = 0.0
                curvePoint(x2, y2)
            }
        }
    }

    private fun monotone(
        points: List<ScenePoint>,
        sink: PathSink,
        reflect: Boolean,
    ) {
        val output = if (reflect) ReflectingPathSink(sink) else sink
        var x0 = Double.NaN
        var x1 = Double.NaN
        var y0 = Double.NaN
        var y1 = Double.NaN
        var tangent0 = Double.NaN
        var pointState = 0

        fun slope3(x2: Double, y2: Double): Double {
            val h0 = x1 - x0
            val h1 = x2 - x1
            val denominator0 = if (h0 != 0.0) h0 else if (h1 < 0.0) -0.0 else 0.0
            val denominator1 = if (h1 != 0.0) h1 else if (h0 < 0.0) -0.0 else 0.0
            val slope0 = (y1 - y0) / denominator0
            val slope1 = (y2 - y1) / denominator1
            val weighted = (slope0 * h1 + slope1 * h0) / (h0 + h1)
            val result = (sign(slope0) + sign(slope1)) *
                min(min(abs(slope0), abs(slope1)), 0.5 * abs(weighted))
            return if (result == 0.0 || result.isNaN()) 0.0 else result
        }

        fun slope2(tangent: Double): Double {
            val h = x1 - x0
            return if (h != 0.0) {
                (3 * (y1 - y0) / h - tangent) / 2
            } else {
                tangent
            }
        }

        fun curvePoint(firstTangent: Double, secondTangent: Double) {
            val deltaX = (x1 - x0) / 3
            output.cubicTo(
                x0 + deltaX,
                y0 + deltaX * firstTangent,
                x1 - deltaX,
                y1 - deltaX * secondTangent,
                x1,
                y1,
            )
        }

        points.forEach { scenePoint ->
            val x = if (reflect) scenePoint.y.toDouble() else scenePoint.x.toDouble()
            val y = if (reflect) scenePoint.x.toDouble() else scenePoint.y.toDouble()
            if (x == x1 && y == y1) {
                return@forEach
            }

            var tangent1 = Double.NaN
            when (pointState) {
                0 -> {
                    pointState = 1
                    output.moveTo(x, y)
                }
                1 -> pointState = 2
                2 -> {
                    pointState = 3
                    tangent1 = slope3(x, y)
                    curvePoint(slope2(tangent1), tangent1)
                }
                else -> {
                    tangent1 = slope3(x, y)
                    curvePoint(tangent0, tangent1)
                }
            }

            x0 = x1
            x1 = x
            y0 = y1
            y1 = y
            tangent0 = tangent1
        }

        when (pointState) {
            2 -> output.lineTo(x1, y1)
            3 -> curvePoint(tangent0, slope2(tangent0))
        }
    }

    private fun natural(
        points: List<ScenePoint>,
        sink: PathSink,
    ) {
        if (points.isEmpty()) {
            return
        }
        sink.moveTo(points.first().x, points.first().y)
        if (points.size == 1) {
            return
        }
        if (points.size == 2) {
            sink.lineTo(points[1].x, points[1].y)
            return
        }

        val xControls = naturalControlPoints(points.map { it.x.toDouble() })
        val yControls = naturalControlPoints(points.map { it.y.toDouble() })
        for (index in 0 until points.lastIndex) {
            sink.cubicTo(
                xControls.first[index],
                yControls.first[index],
                xControls.second[index],
                yControls.second[index],
                points[index + 1].x.toDouble(),
                points[index + 1].y.toDouble(),
            )
        }
    }

    private fun naturalControlPoints(values: List<Double>): Pair<DoubleArray, DoubleArray> {
        val size = values.size - 1
        val first = DoubleArray(size)
        val second = DoubleArray(size)
        val diagonal = DoubleArray(size)
        val right = DoubleArray(size)

        diagonal[0] = 2.0
        right[0] = values[0] + 2 * values[1]
        for (index in 1 until size - 1) {
            first[index] = 1.0
            diagonal[index] = 4.0
            right[index] = 4 * values[index] + 2 * values[index + 1]
        }
        first[size - 1] = 2.0
        diagonal[size - 1] = 7.0
        right[size - 1] = 8 * values[size - 1] + values[size]
        for (index in 1 until size) {
            val factor = first[index] / diagonal[index - 1]
            diagonal[index] -= factor
            right[index] -= factor * right[index - 1]
        }
        first[size - 1] = right[size - 1] / diagonal[size - 1]
        for (index in size - 2 downTo 0) {
            first[index] = (right[index] - first[index + 1]) / diagonal[index]
        }
        second[size - 1] = (values[size] + first[size - 1]) / 2
        for (index in 0 until size - 1) {
            second[index] = 2 * values[index + 1] - first[index + 1]
        }
        return first to second
    }

    private fun sign(value: Double): Double = if (value < 0.0) -1.0 else 1.0

    private open class PathSink {
        val commands = mutableListOf<ScenePathCommand>()

        open fun moveTo(x: Double, y: Double) {
            commands += ScenePathCommand.MoveTo(ScenePoint(x.toFloat(), y.toFloat()))
        }

        fun moveTo(x: Float, y: Float) {
            moveTo(x.toDouble(), y.toDouble())
        }

        open fun lineTo(x: Double, y: Double) {
            commands += ScenePathCommand.LineTo(ScenePoint(x.toFloat(), y.toFloat()))
        }

        fun lineTo(x: Float, y: Float) {
            lineTo(x.toDouble(), y.toDouble())
        }

        open fun cubicTo(
            control1X: Double,
            control1Y: Double,
            control2X: Double,
            control2Y: Double,
            endX: Double,
            endY: Double,
        ) {
            commands += ScenePathCommand.CubicTo(
                control1 = ScenePoint(control1X.toFloat(), control1Y.toFloat()),
                control2 = ScenePoint(control2X.toFloat(), control2Y.toFloat()),
                end = ScenePoint(endX.toFloat(), endY.toFloat()),
            )
        }
    }

    private class ReflectingPathSink(
        private val target: PathSink,
    ) : PathSink() {
        override fun moveTo(x: Double, y: Double) {
            target.moveTo(y, x)
        }

        override fun lineTo(x: Double, y: Double) {
            target.lineTo(y, x)
        }

        override fun cubicTo(
            control1X: Double,
            control1Y: Double,
            control2X: Double,
            control2Y: Double,
            endX: Double,
            endY: Double,
        ) {
            target.cubicTo(
                control1Y,
                control1X,
                control2Y,
                control2X,
                endY,
                endX,
            )
        }
    }

    private const val D3_EPSILON = 1e-12
}
