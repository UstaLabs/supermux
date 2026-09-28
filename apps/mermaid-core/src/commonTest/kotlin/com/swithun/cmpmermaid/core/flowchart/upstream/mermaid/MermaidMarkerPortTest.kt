package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MermaidMarkerPortTest {
    @Test
    fun portsMermaidMarkerPathOffsets() {
        assertEquals(0f, MermaidMarkerPort.pathOffset(SceneArrowHead.None))
        assertEquals(4f, MermaidMarkerPort.pathOffset(SceneArrowHead.Triangle))
        assertEquals(0f, MermaidMarkerPort.pathOffset(SceneArrowHead.Circle))
        assertEquals(0f, MermaidMarkerPort.pathOffset(SceneArrowHead.Cross))
        assertEquals(17.25f, MermaidMarkerPort.pathOffset(SceneArrowHead.ClassAggregation))
        assertEquals(17.25f, MermaidMarkerPort.pathOffset(SceneArrowHead.ClassExtension))
        assertEquals(17.25f, MermaidMarkerPort.pathOffset(SceneArrowHead.ClassComposition))
        assertEquals(6f, MermaidMarkerPort.pathOffset(SceneArrowHead.ClassDependency))
        assertEquals(13.5f, MermaidMarkerPort.pathOffset(SceneArrowHead.ClassLollipop))
        assertEquals(0f, MermaidMarkerPort.pathOffset(SceneArrowHead.ErOnlyOne))
        assertEquals(0f, MermaidMarkerPort.pathOffset(SceneArrowHead.ErZeroOrOne))
        assertEquals(0f, MermaidMarkerPort.pathOffset(SceneArrowHead.ErOneOrMore))
        assertEquals(0f, MermaidMarkerPort.pathOffset(SceneArrowHead.ErZeroOrMore))
    }

    @Test
    fun portsMermaidNeoGapAndTerminalOffsets() {
        assertEquals(0f, MermaidMarkerPort.neoGapOffset(SceneArrowHead.None))
        assertEquals(4f, MermaidMarkerPort.neoGapOffset(SceneArrowHead.Triangle))
        assertEquals(12.5f, MermaidMarkerPort.neoGapOffset(SceneArrowHead.Circle))
        assertEquals(12.5f, MermaidMarkerPort.neoGapOffset(SceneArrowHead.Cross))
        assertEquals(12.5f, MermaidMarkerPort.terminalSegmentOffset(SceneArrowHead.Circle))
    }

    @Test
    fun leavesCircleMarkerPathEndpointUnchanged() {
        val result = MermaidEdgePathPort.generate(
            points = listOf(ScenePoint(0f, 0f), ScenePoint(50f, 0f)),
            curve = "linear",
            arrowStart = SceneArrowHead.None,
            arrowEnd = SceneArrowHead.Circle,
        )

        val commands = assertIs<GMResult.Ok<List<ScenePathCommand>>>(result).value
        assertEquals(
            ScenePathCommand.LineTo(ScenePoint(50f, 0f)),
            commands.last(),
        )
    }

    @Test
    fun reservesPointMarkerLengthAtPathEnd() {
        val result = MermaidEdgePathPort.generate(
            points = listOf(ScenePoint(0f, 0f), ScenePoint(50f, 0f)),
            curve = "linear",
            arrowStart = SceneArrowHead.None,
            arrowEnd = SceneArrowHead.Triangle,
        )

        val commands = assertIs<GMResult.Ok<List<ScenePathCommand>>>(result).value
        assertEquals(
            ScenePathCommand.LineTo(ScenePoint(46f, 0f)),
            commands.last(),
        )
    }

    @Test
    fun reservesClassMarkerLengthsAtBothPathEnds() {
        val result = MermaidEdgePathPort.generate(
            points = listOf(ScenePoint(0f, 0f), ScenePoint(100f, 0f)),
            curve = "linear",
            arrowStart = SceneArrowHead.ClassAggregation,
            arrowEnd = SceneArrowHead.ClassDependency,
        )

        val commands = assertIs<GMResult.Ok<List<ScenePathCommand>>>(result).value
        assertEquals(
            ScenePathCommand.MoveTo(ScenePoint(17.25f, 0f)),
            commands.first(),
        )
        assertEquals(
            ScenePathCommand.LineTo(ScenePoint(94f, 0f)),
            commands.last(),
        )
    }
}
