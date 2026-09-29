package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidEdgePathPortTest {
    @Test
    fun portsMermaidRoundedCornerGeometry() {
        val commands = generate(
            curve = "rounded",
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(10f, 0f),
                ScenePoint(10f, 10f),
            ),
        )

        assertEquals(
            listOf(
                ScenePathCommand.MoveTo(ScenePoint(0f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(5f, 0f)),
                ScenePathCommand.QuadraticTo(
                    control = ScenePoint(10f, 0f),
                    end = ScenePoint(10f, 5f),
                ),
                ScenePathCommand.LineTo(ScenePoint(10f, 10f)),
            ),
            commands,
        )
    }

    @Test
    fun portsMermaidPointMarkerOffset() {
        val commands = generate(
            curve = "rounded",
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(10f, 0f),
            ),
            arrowEnd = SceneArrowHead.Triangle,
        )

        assertEquals(
            listOf(
                ScenePathCommand.MoveTo(ScenePoint(0f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(6f, 0f)),
            ),
            commands,
        )
    }

    @Test
    fun portsD3StepCurves() {
        val points = listOf(
            ScenePoint(0f, 0f),
            ScenePoint(10f, 10f),
            ScenePoint(20f, 0f),
        )

        assertEquals(
            listOf(
                ScenePathCommand.MoveTo(ScenePoint(0f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(5f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(5f, 10f)),
                ScenePathCommand.LineTo(ScenePoint(15f, 10f)),
                ScenePathCommand.LineTo(ScenePoint(15f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(20f, 0f)),
            ),
            generate("step", points),
        )
        assertEquals(
            listOf(
                ScenePathCommand.MoveTo(ScenePoint(0f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(0f, 10f)),
                ScenePathCommand.LineTo(ScenePoint(10f, 10f)),
                ScenePathCommand.LineTo(ScenePoint(10f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(20f, 0f)),
            ),
            generate("stepBefore", points),
        )
        assertEquals(
            listOf(
                ScenePathCommand.MoveTo(ScenePoint(0f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(10f, 0f)),
                ScenePathCommand.LineTo(ScenePoint(10f, 10f)),
                ScenePathCommand.LineTo(ScenePoint(20f, 10f)),
                ScenePathCommand.LineTo(ScenePoint(20f, 0f)),
            ),
            generate("stepAfter", points),
        )
    }

    @Test
    fun portsMermaidFixCornersBeforeLinearCurve() {
        val commands = generate(
            curve = "linear",
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(0f, 20f),
                ScenePoint(20f, 20f),
            ),
        )

        assertEquals(ScenePathCommand.MoveTo(ScenePoint(0f, 0f)), commands[0])
        assertEquals(ScenePathCommand.LineTo(ScenePoint(0f, 15f)), commands[1])
        val corner = assertIs<ScenePathCommand.LineTo>(commands[2]).point
        val arcOffset = sqrt(2f) * 2f
        assertClose(5f - arcOffset, corner.x)
        assertClose(15f + arcOffset, corner.y)
        assertEquals(ScenePathCommand.LineTo(ScenePoint(5f, 20f)), commands[3])
        assertEquals(ScenePathCommand.LineTo(ScenePoint(20f, 20f)), commands[4])
    }

    @Test
    fun unknownCurveFallsBackToMermaidBasisDefault() {
        val points = listOf(
            ScenePoint(0f, 0f),
            ScenePoint(10f, 10f),
            ScenePoint(20f, 0f),
        )

        assertEquals(
            generate(curve = "basis", points = points),
            generate(curve = "bundle", points = points),
        )
    }

    private fun generate(
        curve: String,
        points: List<ScenePoint>,
        arrowStart: SceneArrowHead = SceneArrowHead.None,
        arrowEnd: SceneArrowHead = SceneArrowHead.None,
    ): List<ScenePathCommand> {
        val result = MermaidEdgePathPort.generate(
            points = points,
            curve = curve,
            arrowStart = arrowStart,
            arrowEnd = arrowEnd,
        )
        return assertIs<GMResult.Ok<List<ScenePathCommand>>>(result).value
    }

    private fun assertClose(
        expected: Float,
        actual: Float,
    ) {
        assertTrue(
            actual in (expected - 0.0001f)..(expected + 0.0001f),
            "Expected $expected, got $actual",
        )
    }
}
