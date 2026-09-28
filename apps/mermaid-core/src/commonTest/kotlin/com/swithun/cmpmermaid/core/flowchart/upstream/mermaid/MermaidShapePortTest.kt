package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.flowchart.FlowDirection
import com.swithun.cmpmermaid.core.flowchart.FlowNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MermaidShapePortTest {
    @Test
    fun usesActualBoundsForBangAndCloudRectIntersections() {
        listOf(SceneShapeKind.Bang, SceneShapeKind.Cloud).forEach { kind ->
            val result = MermaidShapePort.layout(
                node = node(kind),
                measuredLabel = SceneSize(width = 80f, height = 20f),
                direction = FlowDirection.LeftToRight,
                defaultNodeStroke = SceneColor(0xFF333333),
            )
            val layout = assertIs<GMResult.Ok<MermaidShapeLayout>>(result).value
            val pathPoints = layout.geometry.paths.single().points
            val outline = layout.geometry.outline

            assertEquals(pathPoints.width(), outline.width(), 0.001f, "$kind width")
            assertEquals(pathPoints.height(), outline.height(), 0.001f, "$kind height")
            assertEquals(layout.size.width, outline.width(), 0.001f, "$kind layout width")
            assertEquals(layout.size.height, outline.height(), 0.001f, "$kind layout height")
        }
    }

    private fun node(kind: SceneShapeKind) = FlowNode(
        id = kind.name,
        label = kind.name,
        labelSpans = emptyList(),
        labelType = FlowLabelType.Text,
        shape = kind,
        padding = 15f,
        minWidth = null,
        look = "classic",
    )

    private fun List<ScenePoint>.width(): Float =
        maxOf(ScenePoint::x) - minOf(ScenePoint::x)

    private fun List<ScenePoint>.height(): Float =
        maxOf(ScenePoint::y) - minOf(ScenePoint::y)
}
