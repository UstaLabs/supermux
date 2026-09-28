package com.swithun.cmpmermaid.core.quadrant

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuadrantLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * request.fontSize * 0.5f,
                height = request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersOfficialCampaignExample() {
        val scene = render(
            """
            quadrantChart
                title Reach and engagement of campaigns
                x-axis Low Reach --> High Reach
                y-axis Low Engagement --> High Engagement
                quadrant-1 We should expand
                quadrant-2 Need to promote
                quadrant-3 Re-evaluate
                quadrant-4 May be improved
                Campaign A: [0.3, 0.6]
                Campaign B: [0.45, 0.23]
                Campaign C: [0.57, 0.69]
                Campaign D: [0.78, 0.34]
                Campaign E: [0.40, 0.34]
                Campaign F: [0.35, 0.78]
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val circles = shapes.filter { shape -> shape.kind == SceneShapeKind.Circle }
        val texts = scene.elements.filterIsInstance<SceneText>().map(SceneText::text)

        assertEquals(524f, scene.width)
        assertEquals(524f, scene.height)
        assertEquals(4, shapes.count { shape -> shape.kind == SceneShapeKind.Rectangle })
        assertEquals(6, circles.size)
        assertEquals(6, scene.elements.filterIsInstance<ScenePath>().size)
        assertTrue(texts.containsAll(
            listOf(
                "Reach and engagement of campaigns",
                "Low Reach",
                "High Reach",
                "Low Engagement",
                "High Engagement",
                "We should expand",
                "Need to promote",
                "Re-evaluate",
                "May be improved",
                "Campaign A",
                "Campaign F",
            ),
        ))
        val campaignA = circles.single { shape ->
            shape.bounds.center.x in 182.19f..182.21f &&
                shape.bounds.center.y in 226.59f..226.61f
        }
        assertEquals(10f, campaignA.bounds.width)
    }

    @Test
    fun appliesClassStylesBeforeInlinePointStyles() {
        val scene = render(
            """
            quadrantChart
                Styled:::priority: [0.2, 0.2]
                Inline:::priority: [0.8, 0.8] color: #ff3300, radius: 12
                classDef priority color: #109060, radius: 10, stroke-color: #310085, stroke-width: 4px
            """.trimIndent(),
        )
        val circles = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.kind == SceneShapeKind.Circle }
        val styled = circles.single { shape -> shape.bounds.center.x < 250f }
        val inline = circles.single { shape -> shape.bounds.center.x > 250f }

        assertEquals(SceneColor(0xFF109060), styled.fill)
        assertEquals(SceneColor(0xFF310085), styled.stroke)
        assertEquals(4f, styled.strokeWidth)
        assertEquals(20f, styled.bounds.width)
        assertEquals(SceneColor(0xFFFF3300), inline.fill)
        assertEquals(24f, inline.bounds.width)
    }

    @Test
    fun preservesOfficialPointGroupPaintOrder() {
        val scene = render(
            """
            quadrantChart
                First: [0.2, 0.2]
                Second: [0.8, 0.8]
            """.trimIndent(),
        )

        val pointElements = scene.elements.mapNotNull { element ->
            when {
                element is SceneShape && element.kind == SceneShapeKind.Circle ->
                    "shape:${element.id}"
                element is SceneText && element.text in setOf("First", "Second") ->
                    "text:${element.text}"
                else -> null
            }
        }

        // Mermaid's DB prepends parsed points and its renderer appends circle/text
        // inside each data-point group.
        assertEquals(
            listOf(
                "shape:quadrant-point-0",
                "text:Second",
                "shape:quadrant-point-1",
                "text:First",
            ),
            pointElements,
        )
    }

    @Test
    fun usesSvgMetricsForPointLabelsOnly() {
        val scene = render(
            """
            quadrantChart
                title Point label metrics
                Point label: [0.5, 0.5]
            """.trimIndent(),
        )
        val texts = scene.elements.filterIsInstance<SceneText>()
        val pointLabel = texts.single { text -> text.text == "Point label" }
        val title = texts.single { text -> text.text == "Point label metrics" }

        assertEquals(7f / 6f, pointLabel.lineHeight)
        assertEquals(1f, pointLabel.horizontalScale)
        assertEquals(66f / 1.06f, pointLabel.bounds.width, 0.001f)
        assertEquals(14f, pointLabel.bounds.height)
        assertEquals(1f, title.lineHeight)
        assertEquals(null, title.horizontalScale)
    }

    @Test
    fun expandsAndTranslatesViewportForEveryOverflowDirection() {
        val scene = render(
            """
            quadrantChart
                y-axis Bottom Axis Label With Vertical Overflow --> Top Axis Label With Vertical Overflow
                Left Point Label With Horizontal Overflow: [0, 0]
                Right Point Label With Horizontal Overflow: [1, 0]
            """.trimIndent(),
        )
        val contentBounds = scene.elements
            .mapNotNull(::renderedBounds)
            .reduce(SceneRect::union)

        assertTrue(scene.width > 524f, "Expected left and right overflow in ${scene.width}")
        assertTrue(scene.height > 524f, "Expected top and bottom overflow in ${scene.height}")
        assertTrue(contentBounds.left >= 12f - 0.01f, "$contentBounds")
        assertTrue(contentBounds.top >= 12f - 0.01f, "$contentBounds")
        assertTrue(contentBounds.right <= scene.width - 12f + 0.01f, "$contentBounds")
        assertTrue(contentBounds.bottom <= scene.height - 12f + 0.01f, "$contentBounds")
        assertEquals(0f, scene.viewportPadding)
    }

    @Test
    fun returnsStructuredErrorsForInvalidPointsAndStyles() {
        val invalidSources = listOf(
            """
            quadrantChart
                Outside: [1.2, 0.4]
            """.trimIndent(),
            """
            quadrantChart
                Invalid: [0.2, 0.4] radius: large
            """.trimIndent(),
        )

        invalidSources.forEach { source ->
            val result = engine.render(source, context)
            val error = assertIs<GMResult.Err<MermaidError>>(result).error
            assertIs<MermaidError.Parse>(error)
        }
    }

    @Test
    fun appliesFrontmatterConfigThemeAndMetadataWithoutPoints() {
        val scene = render(
            """
            ---
            title: Portfolio map
            config:
              quadrantChart:
                chartWidth: 400
                chartHeight: 360
                titleFontSize: 20
                titlePadding: 10
                quadrantPadding: 5
                xAxisLabelPadding: 5
                yAxisLabelPadding: 5
                xAxisLabelFontSize: 16
                yAxisLabelFontSize: 16
                quadrantLabelFontSize: 16
                quadrantTextTopPadding: 7
                pointTextPadding: 6
                pointLabelFontSize: 13
                pointRadius: 8
                xAxisPosition: top
                yAxisPosition: right
                quadrantInternalBorderStrokeWidth: 3
                quadrantExternalBorderStrokeWidth: 4
                useMaxWidth: false
              themeVariables:
                quadrant1Fill: "#123456"
                quadrant1TextFill: "#abcdef"
                quadrantExternalBorderStrokeFill: "#654321"
            ---
            quadrantChart
                accTitle: Accessible portfolio
                accDescr: Portfolio priorities
                x-axis Low impact --> High impact
                y-axis Low effort --> High effort
                quadrant-1 Invest
                quadrant-2 Explore
                quadrant-3 Avoid
                quadrant-4 Maintain
            """.trimIndent(),
        )
        val first = scene.elements.filterIsInstance<SceneShape>()
            .single { shape -> shape.id == "quadrant-area-1" }
        val externalBorders = scene.elements.filterIsInstance<ScenePath>()
            .filter { path -> path.strokeWidth == 4f }
        val rightBorder = externalBorders.single { path -> path.id == "quadrant-border-1" }
        val invest = scene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text == "Invest" }

        assertEquals(424f, scene.width)
        assertEquals(384f, scene.height)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
        assertEquals(SceneColor(0xFF123456), first.fill)
        assertEquals(182f, first.bounds.width)
        assertEquals(142f, first.bounds.height)
        assertTrue(rightBorder.points.all { point -> point.x == 381f })
        assertEquals(SceneColor(0xFFABCDEF), invest.color)
        assertTrue(invest.bounds.center.y in 153.99f..154.01f)
        assertEquals(4, externalBorders.size)
        assertTrue(externalBorders.all { path -> path.color == SceneColor(0xFF654321) })
        assertEquals("Portfolio map", scene.title)
        assertEquals("Accessible portfolio", scene.accessibilityTitle)
        assertEquals("Portfolio priorities", scene.accessibilityDescription)
    }

    @Test
    fun enforcesPointResourceLimit() {
        val result = engine.render(
            """
            quadrantChart
                A: [0.1, 0.1]
                B: [0.2, 0.2]
            """.trimIndent(),
            context.copy(options = MermaidRenderOptions(maxEdges = 1)),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        val resource = assertIs<MermaidError.ResourceLimit>(error)
        assertEquals("Quadrant points", resource.resource)
        assertEquals(2, resource.actual)
        assertEquals(1, resource.maximum)
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected Quadrant render success:\n$source\n$result",
        ).value
    }

    private fun renderedBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneShape -> element.bounds
        is SceneText -> {
            val pivot = element.rotationPivot ?: element.bounds.center
            val radians = element.rotationDegrees * PI.toFloat() / 180f
            val corners = listOf(
                element.bounds.left to element.bounds.top,
                element.bounds.right to element.bounds.top,
                element.bounds.right to element.bounds.bottom,
                element.bounds.left to element.bounds.bottom,
            ).map { (x, y) ->
                val offsetX = x - pivot.x
                val offsetY = y - pivot.y
                (pivot.x + offsetX * cos(radians) - offsetY * sin(radians)) to
                    (pivot.y + offsetX * sin(radians) + offsetY * cos(radians))
            }
            SceneRect(
                left = corners.minOf { point -> point.first },
                top = corners.minOf { point -> point.second },
                right = corners.maxOf { point -> point.first },
                bottom = corners.maxOf { point -> point.second },
            )
        }
        is ScenePath -> {
            val points = element.commands.flatMap { command ->
                when (command) {
                    is ScenePathCommand.MoveTo -> listOf(command.point)
                    is ScenePathCommand.LineTo -> listOf(command.point)
                    is ScenePathCommand.QuadraticTo -> listOf(command.control, command.end)
                    is ScenePathCommand.CubicTo ->
                        listOf(command.control1, command.control2, command.end)
                    is ScenePathCommand.ArcTo -> listOf(command.end)
                }
            }
            points.firstOrNull()?.let { first ->
                points.drop(1).fold(
                    SceneRect(first.x, first.y, first.x, first.y),
                ) { bounds, point ->
                    bounds.union(SceneRect(point.x, point.y, point.x, point.y))
                }
            }
        }
        else -> null
    }
}
