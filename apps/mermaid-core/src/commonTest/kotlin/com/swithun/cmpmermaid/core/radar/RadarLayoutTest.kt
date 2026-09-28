package com.swithun.cmpmermaid.core.radar

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRadarOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RadarLayoutTest {
    private val engine = MermaidEngine()
    private val textMetrics = TextMetricProvider { request ->
        TextMetrics(
            width = request.text.length * request.fontSize * 0.55f,
            height = request.fontSize,
        )
    }

    @Test
    fun rendersDefaultGeometryPaintOrderAndCubicCurves() {
        val scene = render(
            """
            radar-beta
              title Comparison
              axis A, B, C
              curve first { 1, 2, 3 }
              curve second { C: 1, A: 3, B: 2 }
            """.trimIndent(),
        )

        assertEquals(724f, scene.width)
        assertEquals(727f, scene.height)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
        assertEquals(18, scene.elements.size)
        assertTrue(scene.elements.take(5).all { element ->
            element is SceneShape && element.kind == SceneShapeKind.Circle
        })
        assertIs<ScenePath>(scene.elements[5])
        assertIs<SceneText>(scene.elements[6])
        val firstCurve = assertIs<ScenePath>(scene.elements[11])
        assertEquals(4, firstCurve.commands.size)
        assertTrue(firstCurve.commands.drop(1).all { command ->
            command is ScenePathCommand.CubicTo
        })
        assertEquals(true, firstCurve.closed)
        assertEquals(SceneColor(0x808686FF), firstCurve.fillColor)
        assertIs<SceneShape>(scene.elements[13])
        assertIs<SceneText>(scene.elements[14])
        assertEquals("Comparison", assertIs<SceneText>(scene.elements.last()).text)
    }

    @Test
    fun expandsAndTranslatesViewportToKeepLongLabelsVisible() {
        val scene = render(
            """
            radar-beta
              title Comparison
              axis A, long["Extremely long label extending beyond the viewport"], C
              curve first { 1, 2, 3 }
            """.trimIndent(),
        )

        assertTrue(scene.width > 724f)
        scene.elements.filterIsInstance<SceneText>().forEach { text ->
            assertTrue(text.bounds.left >= 12f, text.text)
            assertTrue(text.bounds.top >= 12f, text.text)
            assertTrue(text.bounds.right <= scene.width - 12f, text.text)
            assertTrue(text.bounds.bottom <= scene.height - 12f, text.text)
        }
    }

    @Test
    fun rendersPolygonGraticulesAndCurvesAsClosedLinearPaths() {
        val scene = render(
            """
            radar-beta
              axis A, B, C, D
              curve square { 1, 2, 3, 4 }
              ticks 2
              graticule polygon
              showLegend false
            """.trimIndent(),
        )
        val paths = scene.elements.filterIsInstance<ScenePath>()
        val graticules = paths.take(2)
        val curve = paths.last()

        assertTrue(graticules.all(ScenePath::closed))
        assertEquals(4, graticules.first().points.size)
        assertEquals(4, curve.commands.size)
        assertTrue(curve.commands.drop(1).all { command ->
            command is ScenePathCommand.LineTo
        })
        assertEquals(true, curve.closed)
    }

    @Test
    fun appliesRadarConfigAndNestedThemeVariables() {
        val scene = render(
            """
            ---
            config:
              radar:
                width: 200
                height: 100
                marginTop: 10
                marginRight: 20
                marginBottom: 30
                marginLeft: 40
                axisScaleFactor: 0.8
                axisLabelFactor: 0.9
                curveTension: 0.25
                useMaxWidth: false
              themeVariables:
                textColor: "#102030"
                cScale0: "#123456"
                radar:
                  axisColor: "#abcdef"
                  axisStrokeWidth: 3
                  curveOpacity: 0.25
                  graticuleColor: "#fedcba"
            ---
            radar-beta
              axis A, B
              curve c { 1, 2 }
            """.trimIndent(),
        )

        assertEquals(284f, scene.width)
        assertEquals(164f, scene.height)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
        val graticule = assertIs<SceneShape>(scene.elements.first())
        assertEquals(SceneColor(0x4DFEDCBA), graticule.fill)
        val axis = scene.elements.filterIsInstance<ScenePath>()
            .first { path -> path.id == "radar-axis-0" }
        assertEquals(SceneColor(0xFFABCDEF), axis.color)
        assertEquals(3f, axis.strokeWidth)
        val curve = scene.elements.filterIsInstance<ScenePath>()
            .first { path -> path.id == "radar-curve-0" }
        assertEquals(SceneColor(0xFF123456), curve.color)
        assertEquals(SceneColor(0x40123456), curve.fillColor)
        assertEquals(SceneColor(0xFF102030), scene.elements.filterIsInstance<SceneText>().first().color)
    }

    @Test
    fun skipsCurvesWhoseEntryCountDoesNotMatchAxesButKeepsLegend() {
        val scene = render(
            """
            radar-beta
              axis A, B, C
              curve incomplete { 1, 2 }
            """.trimIndent(),
        )

        assertTrue(scene.elements.filterIsInstance<ScenePath>().none { path ->
            path.id.startsWith("radar-curve-")
        })
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any { shape ->
            shape.id == "radar-legend-box-0"
        })
    }

    @Test
    fun rejectsInvalidDirectConfigAndZeroValueRange() {
        val invalidConfigs = listOf(
            MermaidRadarOptions(width = 0f),
            MermaidRadarOptions(height = Float.NaN),
            MermaidRadarOptions(marginLeft = -1f),
            MermaidRadarOptions(axisScaleFactor = Float.POSITIVE_INFINITY),
            MermaidRadarOptions(curveTension = 1.1f),
        )
        invalidConfigs.forEach { radar ->
            val error = assertIs<GMResult.Err<MermaidError>>(
                engine.render(
                    "radar-beta\naxis A\ncurve c{1}",
                    context(radar),
                ),
            ).error
            assertIs<MermaidError.Configuration>(error)
        }

        val rangeError = assertIs<GMResult.Err<MermaidError>>(
            engine.render(
                "radar-beta\naxis A\ncurve c{1}\nmin 1\nmax 1",
                context(),
            ),
        ).error
        assertIs<MermaidError.Layout>(rangeError)
    }

    @Test
    fun portsRelativeRadiusAndClosedRoundCurve() {
        assertEquals(50f, radarRelativeRadius(5.0, 0.0, 10.0, 100f))
        assertEquals(0f, radarRelativeRadius(-5.0, 0.0, 10.0, 100f))
        assertEquals(100f, radarRelativeRadius(15.0, 0.0, 10.0, 100f))
        assertEquals(75f, radarRelativeRadius(5.0, -10.0, 10.0, 100f))

        val commands = radarClosedRoundCurve(
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(100f, 0f),
                ScenePoint(100f, 100f),
                ScenePoint(0f, 100f),
            ),
            tension = 0.5f,
        )
        assertEquals(ScenePathCommand.MoveTo(ScenePoint(0f, 0f)), commands.first())
        assertEquals(
            ScenePathCommand.CubicTo(
                control1 = ScenePoint(50f, -50f),
                control2 = ScenePoint(50f, -50f),
                end = ScenePoint(100f, 0f),
            ),
            commands[1],
        )
        assertEquals(
            ScenePathCommand.CubicTo(
                control1 = ScenePoint(-50f, 50f),
                control2 = ScenePoint(-50f, 50f),
                end = ScenePoint(0f, 0f),
            ),
            commands.last(),
        )
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context()),
            source,
        ).value

    private fun context(
        radar: MermaidRadarOptions = MermaidRadarOptions(),
    ): MermaidRenderContext = MermaidRenderContext(
        textMetrics = textMetrics,
        theme = MermaidTheme.MermaidDefault,
        options = MermaidRenderOptions(radar = radar),
    )
}
