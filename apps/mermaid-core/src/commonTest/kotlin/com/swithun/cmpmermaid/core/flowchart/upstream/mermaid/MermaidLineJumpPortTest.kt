package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidElkLineHops
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidLineJumpPortTest {
    @Test
    fun putsArcOnHorizontalEdgeAtOrthogonalCrossing() {
        val horizontal = path("horizontal", point(0, 10), point(20, 10))
        val vertical = path("vertical", point(10, 0), point(10, 20))

        val result = MermaidLineJumpPort.apply(
            listOf(horizontal, vertical),
            MermaidElkLineHops.Arc,
        )

        assertEquals(
            listOf(
                ScenePathCommand.MoveTo(point(0, 10)),
                ScenePathCommand.LineTo(point(4, 10)),
                ScenePathCommand.ArcTo(
                    radius = 6f,
                    end = point(16, 10),
                    clockwise = true,
                ),
                ScenePathCommand.LineTo(point(20, 10)),
            ),
            result[0].commands,
        )
        assertEquals(vertical.commands, result[1].commands)
    }

    @Test
    fun rendersConfiguredGapInsteadOfArc() {
        val horizontal = path("horizontal", point(20, 10), point(0, 10))
        val vertical = path("vertical", point(10, 0), point(10, 20))

        val result = MermaidLineJumpPort.apply(
            listOf(horizontal, vertical),
            MermaidElkLineHops.Gap,
        )

        assertEquals(ScenePathCommand.LineTo(point(16, 10)), result[0].commands[1])
        assertEquals(ScenePathCommand.MoveTo(point(4, 10)), result[0].commands[2])
        assertEquals(ScenePathCommand.LineTo(point(0, 10)), result[0].commands[3])
    }

    @Test
    fun skipsCrossingInsideRoundedCorner() {
        val rounded = path(
            "rounded",
            point(0, 0),
            point(10, 0),
            point(10, 10),
            curve = "rounded",
        )
        val vertical = path("vertical", point(8, -5), point(8, 5))

        val result = MermaidLineJumpPort.apply(
            listOf(rounded, vertical),
            MermaidElkLineHops.Arc,
        )

        assertEquals(rounded.commands, result[0].commands)
        assertEquals(vertical.commands, result[1].commands)
    }

    @Test
    fun leavesCurvedJumpOwnerUnchanged() {
        val basis = path(
            "basis",
            point(0, 10),
            point(20, 10),
            curve = "basis",
        )
        val vertical = path("vertical", point(10, 0), point(10, 20))

        val result = MermaidLineJumpPort.apply(
            listOf(basis, vertical),
            MermaidElkLineHops.Arc,
        )

        assertEquals(basis.commands, result[0].commands)
        assertEquals(vertical.commands, result[1].commands)
    }

    private fun path(
        id: String,
        vararg points: ScenePoint,
        curve: String = "linear",
    ): ScenePath {
        val commands = points.mapIndexed { index, point ->
            if (index == 0) {
                ScenePathCommand.MoveTo(point)
            } else {
                ScenePathCommand.LineTo(point)
            }
        }
        return ScenePath(
            id = id,
            points = points.toList(),
            commands = commands,
            color = SceneColor(0xFF000000),
            strokeWidth = 1f,
            arrowStart = SceneArrowHead.None,
            arrowEnd = SceneArrowHead.None,
            curve = curve,
            look = "neo",
            animated = false,
        )
    }

    private fun point(
        x: Int,
        y: Int,
    ): ScenePoint = ScenePoint(x.toFloat(), y.toFloat())
}
