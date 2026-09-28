package com.swithun.cmpmermaid.core.pie

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PieLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = metrics(),
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersSlicesPercentagesAndLegendInInputOrder() {
        val scene = render(
            """
            pie showData
                title Distribution
                "A" : 10
                "B" : 100
                "C" : 0.1
                "D" : 50
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val slices = shapes.filter { shape -> shape.id.startsWith("pie-slice-") }
        val texts = scene.elements.filterIsInstance<SceneText>()

        assertEquals(
            listOf("pie-slice-0", "pie-slice-1", "pie-slice-3"),
            slices.map(SceneShape::id),
        )
        assertEquals(SceneColor(0xB3ECECFF), slices[0].fill)
        assertEquals(SceneColor(0xB3B9B9FF), slices[2].fill)
        assertEquals(listOf("6%", "62%", "31%"), texts.take(3).map(SceneText::text))
        assertTrue(texts.any { text -> text.text == "C [0.1]" })
        assertTrue(shapes.any { shape -> shape.id == "pie-legend-swatch-2" })
        assertTrue(scene.width > 490f)
        assertEquals(474f, scene.height)
    }

    @Test
    fun rendersDonutGeometryAndStaticHighlight() {
        val scene = render(
            """
            ---
            config:
              pie:
                donutHole: 0.4
                highlightSlice: B
            ---
            pie
                "A" : 30
                "B" : 70
            """.trimIndent(),
        )
        val slices = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id.startsWith("pie-slice-") }
            .associateBy(SceneShape::id)
        val normal = slices.getValue("pie-slice-0")
        val highlighted = slices.getValue("pie-slice-1")
        val innerRadius = 185f * 0.4f

        assertEquals(370f, normal.bounds.width)
        assertEquals(388.5f, highlighted.bounds.width)
        assertEquals(0xB3, normal.fill.argb ushr 24)
        assertEquals(0xFF, highlighted.fill.argb ushr 24)
        assertTrue(
            normal.geometry.orEmptyPoints().all { point ->
                hypot(point.x.toDouble(), point.y.toDouble()) >= innerRadius - 0.01
            },
        )
    }

    @Test
    fun preservesMermaidLegendPositionTransforms() {
        val source = """
            pie
                "Alpha" : 40
                "Beta" : 60
        """.trimIndent()
        val right = render(source)
        val top = render(
            source.withPieConfig("legendPosition: top"),
        )
        val bottom = render(
            source.withPieConfig("legendPosition: bottom"),
        )
        val left = render(
            source.withPieConfig("legendPosition: left"),
        )
        val center = render(
            source.withPieConfig("legendPosition: center"),
        )

        assertEquals(518f, top.height)
        assertEquals(518f, bottom.height)
        assertEquals(474f, left.height)
        assertEquals(474f, center.height)
        assertTrue(left.outerCircle().bounds.center.x > right.outerCircle().bounds.center.x)
        assertTrue(top.outerCircle().bounds.center.y > right.outerCircle().bounds.center.y)
        assertEquals(right.outerCircle().bounds.center, bottom.outerCircle().bounds.center)
        assertEquals(right.outerCircle().bounds.center, center.outerCircle().bounds.center)
        assertEquals(
            40f,
            center.elements.filterIsInstance<SceneText>()
                .single { text -> text.text == "Alpha" }
                .bounds.width,
        )
    }

    @Test
    fun normalizesLongCenterAndBottomLegendsIntoTheSceneViewport() {
        val source = """
            pie
                "A very long legend label that extends beyond the renderer viewBox" : 40
                "Another long legend label that must remain fully visible" : 60
        """.trimIndent()

        assertElementsInsideViewport(render(source.withPieConfig("legendPosition: center")))
        assertElementsInsideViewport(render(source.withPieConfig("legendPosition: bottom")))
    }

    @Test
    fun appliesPieConfigThemeVariablesAndMetadata() {
        val scene = render(
            """
            ---
            title: Frontmatter title
            config:
              pie:
                textPosition: 0.5
                legendPosition: bottom
              themeVariables:
                pie1: "#123456"
                pieStrokeColor: "#abcdef"
                pieStrokeWidth: 4px
                pieOpacity: 0.5
                pieTitleTextColor: "#654321"
            ---
            pie
                accTitle: Accessible title
                accDescr: Accessible description
                "A" : 1
                "B" : 1
            """.trimIndent(),
        )
        val first = scene.elements.filterIsInstance<SceneShape>()
            .single { shape -> shape.id == "pie-slice-0" }
        val title = scene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text == "Frontmatter title" }

        assertEquals(SceneColor(0x80123456), first.fill)
        assertEquals(SceneColor(0x80ABCDEF), first.stroke)
        assertEquals(4f, first.strokeWidth)
        assertEquals(SceneColor(0xFF654321), title.color)
        assertEquals("Accessible title", scene.accessibilityTitle)
        assertEquals("Accessible description", scene.accessibilityDescription)
    }

    @Test
    fun returnsStructuredErrorForHoverHighlight() {
        val result = engine.render(
            """
            ---
            config:
              pie:
                highlightSlice: hover
            ---
            pie
                "A" : 1
            """.trimIndent(),
            context,
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.UnsupportedFeature>(error)
    }

    @Test
    fun rendersZeroOnlyDataAsOuterCircleAndLegend() {
        val scene = render(
            """
            pie
                "A" : 0
                "B" : 0
            """.trimIndent(),
        )

        assertTrue(
            scene.elements.filterIsInstance<SceneShape>()
                .none { shape -> shape.id.startsWith("pie-slice-") },
        )
        assertEquals(
            2,
            scene.elements.filterIsInstance<SceneShape>()
                .count { shape -> shape.id.startsWith("pie-legend-swatch-") },
        )
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any {
            shape -> shape.id == "pie-outer-circle"
        })
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected Pie render success:\n$source\n$result",
        ).value
    }

    private fun String.withPieConfig(configLine: String): String =
        """
        ---
        config:
          pie:
            $configLine
        ---
        $this
        """.trimIndent()

    private fun MermaidScene.outerCircle(): SceneShape =
        elements.filterIsInstance<SceneShape>()
            .single { shape -> shape.id == "pie-outer-circle" }

    private fun assertElementsInsideViewport(scene: MermaidScene) {
        val bounds = scene.elements.mapNotNull { element ->
            when (element) {
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                else -> null
            }
        }
        bounds.forEach { rect ->
            assertTrue(rect.left >= 12f, "$rect extends past the left viewport edge")
            assertTrue(rect.top >= 12f, "$rect extends past the top viewport edge")
            assertTrue(rect.right <= scene.width - 12f, "$rect extends past the right viewport edge")
            assertTrue(rect.bottom <= scene.height - 12f, "$rect extends past the bottom viewport edge")
        }
    }

    private fun com.swithun.cmpmermaid.core.SceneShapeGeometry?.orEmptyPoints() =
        this?.paths?.firstOrNull()?.points.orEmpty()

    private fun metrics(): TextMetricProvider = TextMetricProvider { request ->
        TextMetrics(
            width = request.text.length * 8f,
            height = request.fontSize * request.lineHeight,
        )
    }
}
