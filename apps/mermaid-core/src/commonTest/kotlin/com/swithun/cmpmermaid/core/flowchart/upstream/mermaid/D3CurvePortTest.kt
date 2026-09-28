package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class D3CurvePortTest {
    @Test
    fun matchesD3Shape320LineCurves() {
        val points = listOf(
            ScenePoint(0f, 0f),
            ScenePoint(5f, 20f),
            ScenePoint(30f, 10f),
            ScenePoint(40f, 40f),
        )
        val fixtures = mapOf(
            "basis" to listOf(
                expected("M", 0f, 0f),
                expected("L", 0.833333f, 3.333333f),
                expected("C", 1.666667f, 6.666667f, 3.333333f, 13.333333f, 8.333333f, 15f),
                expected("C", 13.333333f, 16.666667f, 21.666667f, 13.333333f, 27.5f, 16.666667f),
                expected("C", 33.333333f, 20f, 36.666667f, 30f, 38.333333f, 35f),
                expected("L", 40f, 40f),
            ),
            "cardinal" to listOf(
                expected("M", 0f, 0f),
                expected("C", 0f, 0f, 0f, 18.333333f, 5f, 20f),
                expected("C", 10f, 21.666667f, 24.166667f, 6.666667f, 30f, 10f),
                expected("C", 35.833333f, 13.333333f, 40f, 40f, 40f, 40f),
            ),
            "bumpX" to listOf(
                expected("M", 0f, 0f),
                expected("C", 2.5f, 0f, 2.5f, 20f, 5f, 20f),
                expected("C", 17.5f, 20f, 17.5f, 10f, 30f, 10f),
                expected("C", 35f, 10f, 35f, 40f, 40f, 40f),
            ),
            "bumpY" to listOf(
                expected("M", 0f, 0f),
                expected("C", 0f, 10f, 5f, 10f, 5f, 20f),
                expected("C", 5f, 15f, 30f, 15f, 30f, 10f),
                expected("C", 30f, 25f, 40f, 25f, 40f, 40f),
            ),
            "catmullRom" to listOf(
                expected("M", 0f, 0f),
                expected("C", 0f, 0f, 0.708282f, 17.805596f, 5f, 20f),
                expected("C", 9.904768f, 22.507864f, 24.189802f, 7.305236f, 30f, 10f),
                expected("C", 36.296604f, 12.920359f, 40f, 40f, 40f, 40f),
            ),
            "monotoneX" to listOf(
                expected("M", 0f, 0f),
                expected("C", 1.666667f, 10f, 3.333333f, 20f, 5f, 20f),
                expected("C", 13.333333f, 20f, 21.666667f, 10f, 30f, 10f),
                expected("C", 33.333333f, 10f, 36.666667f, 25f, 40f, 40f),
            ),
            "monotoneY" to listOf(
                expected("M", 0f, 0f),
                expected("C", 2.5f, 6.666667f, 5f, 13.333333f, 5f, 20f),
                expected("C", 5f, 16.666667f, 30f, 13.333333f, 30f, 10f),
                expected("C", 30f, 20f, 35f, 30f, 40f, 40f),
            ),
            "natural" to listOf(
                expected("M", 0f, 0f),
                expected("C", -0.444444f, 10.222222f, -0.888889f, 20.444445f, 5f, 20f),
                expected("C", 10.888889f, 19.555555f, 23.111111f, 8.444445f, 30f, 10f),
                expected("C", 36.888889f, 11.555555f, 38.444443f, 25.777779f, 40f, 40f),
            ),
        )

        fixtures.forEach { (curve, expected) ->
            val actual = D3CurvePort.generate(points, curve).map(::actual)
            assertEquals(expected.map(ExpectedCommand::kind), actual.map(ExpectedCommand::kind), curve)
            expected.zip(actual).forEach { (expectedCommand, actualCommand) ->
                assertEquals(expectedCommand.values.size, actualCommand.values.size, curve)
                expectedCommand.values.zip(actualCommand.values).forEach { (expectedValue, actualValue) ->
                    assertTrue(
                        actualValue in (expectedValue - 0.0001f)..(expectedValue + 0.0001f),
                        "$curve expected $expectedValue, got $actualValue",
                    )
                }
            }
        }
    }

    private fun actual(command: ScenePathCommand): ExpectedCommand = when (command) {
        is ScenePathCommand.MoveTo -> expected("M", command.point.x, command.point.y)
        is ScenePathCommand.LineTo -> expected("L", command.point.x, command.point.y)
        is ScenePathCommand.QuadraticTo -> expected(
            "Q",
            command.control.x,
            command.control.y,
            command.end.x,
            command.end.y,
        )
        is ScenePathCommand.CubicTo -> expected(
            "C",
            command.control1.x,
            command.control1.y,
            command.control2.x,
            command.control2.y,
            command.end.x,
            command.end.y,
        )
        is ScenePathCommand.ArcTo -> expected(
            "A",
            command.radius,
            command.end.x,
            command.end.y,
            if (command.clockwise) 1f else 0f,
        )
    }

    private fun expected(kind: String, vararg values: Float): ExpectedCommand =
        ExpectedCommand(kind, values.toList())

    private data class ExpectedCommand(
        val kind: String,
        val values: List<Float>,
    )
}
