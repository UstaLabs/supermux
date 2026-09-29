package com.swithun.cmpmermaid.core.sankey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidSankeyOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneBlendMode
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SankeyLayoutTest {
    @Test
    fun rendersNodesLabelsAndD3HorizontalLinksInOfficialPaintOrder() {
        val scene = render(
            """
            sankey
            A,B,10
            A,C,5
            B,D,7
            C,D,5
            """.trimIndent(),
        )

        assertEquals(4, scene.elements.filterIsInstance<SceneShape>().size)
        assertEquals(4, scene.elements.filterIsInstance<SceneText>().size)
        val links = scene.elements.filterIsInstance<ScenePath>()
        assertEquals(4, links.size)
        assertTrue(scene.elements.take(4).all { it is SceneShape })
        assertTrue(scene.elements.takeLast(4).all { it is ScenePath })
        assertIs<ScenePathCommand.CubicTo>(links.first().commands.last())
        assertEquals(0.5f, links.first().opacity)
        assertEquals(SceneBlendMode.Multiply, links.first().blendMode)
        assertNotNull(links.first().strokeGradient)
        assertEquals(248.66667f, links.first().strokeWidth, absoluteTolerance = 0.0001f)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun appliesValueLabelColorAndAlignmentConfiguration() {
        val scene = render(
            """
            ---
            config:
              sankey:
                width: 420
                height: 240
                linkColor: target
                nodeAlignment: left
                useMaxWidth: true
                showValues: true
                prefix: "${'$'}"
                suffix: " kWh"
                nodeWidth: 18
                nodePadding: 6
                labelStyle: outlined
                nodeColors:
                  A: "#112233"
                  B: rgb(68, 85, 102)
            ---
            sankey
            A,B,12.345
            """.trimIndent(),
        )

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        assertEquals(SceneColor(0xFF112233), shapes[0].fill)
        assertEquals(SceneColor(0xFF445566), shapes[1].fill)
        val labels = scene.elements.filterIsInstance<SceneText>()
        assertEquals(4, labels.size)
        assertTrue(labels.all { it.text.endsWith("${'$'}12.35 kWh") })
        assertTrue(labels.take(2).all { it.outlineWidth == 4f })
        assertTrue(labels.takeLast(2).all { it.outlineWidth == 0f })
        assertTrue(labels.maxOf { it.bounds.right + 4f } <= scene.width)
        val link = scene.elements.filterIsInstance<ScenePath>().single()
        assertEquals(SceneColor(0xFF445566), link.color)
        assertEquals(null, link.strokeGradient)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
    }

    @Test
    fun placesLegacyLabelsInsideTheFlowLikeMermaid() {
        val scene = render(
            """
            sankey
            Source,Sink,10
            """.trimIndent(),
        )

        val nodes = scene.elements.filterIsInstance<SceneShape>()
        val labels = scene.elements.filterIsInstance<SceneText>()
        assertTrue(labels[0].bounds.left >= nodes[0].bounds.right)
        assertTrue(labels[1].bounds.right <= nodes[1].bounds.left)
    }

    @Test
    fun preservesFrontmatterTitleMetadata() {
        val scene = render(
            """
            ---
            title: Energy transfer
            ---
            sankey
            Generation,Consumption,10
            """.trimIndent(),
        )

        assertEquals("Energy transfer", scene.title)
    }

    @Test
    fun rejectsCyclesAndInvalidDirectOptionsWithoutThrowing() {
        val cycle = MermaidEngine().render(
            "sankey\nA,B,1\nB,A,1",
            context(),
        )
        assertIs<GMResult.Err<MermaidError>>(cycle)

        val invalid = MermaidEngine().render(
            "sankey\nA,B,1",
            context(MermaidSankeyOptions(nodeAlignment = "diagonal")),
        )
        assertIs<GMResult.Err<MermaidError>>(invalid)
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(MermaidEngine().render(source, context())).value

    private fun context(
        sankey: MermaidSankeyOptions = MermaidSankeyOptions(),
    ): MermaidRenderContext = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * request.fontSize * 0.58f,
                height = request.fontSize * request.lineHeight,
                lineCount = 1,
            )
        },
        options = MermaidRenderOptions(sankey = sankey),
    )
}
