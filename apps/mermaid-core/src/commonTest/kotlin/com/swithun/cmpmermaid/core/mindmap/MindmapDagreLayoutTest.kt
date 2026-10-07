package com.swithun.cmpmermaid.core.mindmap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.mindmap.upstream.dagre.MindmapDagreLayout
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDb
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDocument
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapJisonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MindmapDagreLayoutTest {
    @Test
    fun matchesMermaid1200DagreRelativeNodePositions() {
        val document = parse(
            """
            mindmap
              Root
                left[Left]
                  leftChild)Left child(
                right((Right))
                  rightChild))Right child((
            """.trimIndent(),
        )
        val sizes = mapOf(
            0 to SceneSize(72.1875f, 34f),
            1 to SceneSize(69.09375f, 44f),
            2 to SceneSize(101.79352f, 71.85897f),
            3 to SceneSize(64.10216f, 64.10216f),
            4 to SceneSize(158.33984f, 80f),
        )
        val result = assertIs<GMResult.Ok<MindmapPlacement>>(
            MindmapDagreLayout.layout(
                document = document,
                shapeLayouts = sizes.mapValues { (_, size) -> rectangle(size) },
            ),
        ).value
        val root = result.nodeCenters.getValue(0)
        val expectedDeltas = mapOf(
            1 to (-90.03334f to 99.05108f),
            2 to (-90.03334f to 221.10216f),
            3 to (90.03334f to 99.05108f),
            4 to (90.03334f to 221.10216f),
        )

        expectedDeltas.forEach { (id, expected) ->
            val point = result.nodeCenters.getValue(id)
            assertEquals(expected.first, point.x - root.x, 0.02f, "$id x")
            assertEquals(expected.second, point.y - root.y, 0.02f, "$id y")
        }
    }

    private fun parse(source: String): MindmapDocument {
        val db = assertIs<GMResult.Ok<MindmapDb>>(
            MindmapJisonParser(
                padding = 10f,
                maxNodeWidth = 200f,
            ).parse(source + "\n"),
        ).value
        return assertIs<GMResult.Ok<MindmapDocument>>(db.getData()).value
    }

    private fun rectangle(size: SceneSize): MermaidShapeLayout {
        val halfWidth = size.width / 2f
        val halfHeight = size.height / 2f
        return MermaidShapeLayout(
            size = size,
            labelOffset = ScenePoint(0f, 0f),
            geometry = SceneShapeGeometry(
                paths = emptyList(),
                outline = listOf(
                    ScenePoint(-halfWidth, -halfHeight),
                    ScenePoint(halfWidth, -halfHeight),
                    ScenePoint(halfWidth, halfHeight),
                    ScenePoint(-halfWidth, halfHeight),
                ),
            ),
            showsLabel = true,
        )
    }
}
