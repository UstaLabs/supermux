package com.swithun.cmpmermaid.core.xychart

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XyChartLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * request.fontSize * 0.55f,
                height = request.fontSize,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersVerticalBarsLineAxesTitleAndLegend() {
        val scene = render(
            """
            xychart
                title "Sales Revenue"
                x-axis "Month" [Jan, Feb, Mar, Apr]
                y-axis "Revenue" 0 --> 100
                bar "Actual" [20, 45, 60, 85]
                line "Target" [25, 40, 65, 80]
            """.trimIndent(),
        )

        assertEquals(700f, scene.width)
        assertEquals(500f, scene.height)
        assertEquals(4, scene.elements.filterIsInstance<SceneShape>().count {
            it.id.startsWith("xy-bar-")
        })
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any { it.id == "xy-line-1" })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any {
            it.text == "Sales Revenue"
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any {
            it.text == "Actual"
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any {
            it.text == "Revenue" && it.rotationDegrees == 270f
        })
    }

    @Test
    fun keepsLongChartTitleInsideSceneViewport() {
        val scene = render(
            """
            ---
            config:
              xyChart:
                width: 520
                height: 360
            ---
            xychart
                title "Monthly retention cohort - Accessibility Metadata And Text Measurement Evidence Label 246"
                x-axis ["January", "February", "March", "April", "May", "June"]
                y-axis "Percent" 0 --> 100
                line [82, 79, 76, 74, 72, 70]
            """.trimIndent(),
        )
        val title = scene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text.startsWith("Monthly retention cohort") }

        assertTrue(title.bounds.width > 520f)
        assertTrue(
            title.bounds.right <= scene.width,
            "Title ${title.bounds} exceeds scene width ${scene.width}",
        )
    }

    @Test
    fun rendersHorizontalChartAndDataLabels() {
        val scene = render(
            """
            ---
            config:
              xyChart:
                showDataLabel: true
                showDataLabelOutsideBar: true
            ---
            xychart horizontal
                x-axis [A, B, C]
                y-axis 0 --> 30
                bar [10, 20, 30]
            """.trimIndent(),
        )

        val bars = scene.elements.filterIsInstance<SceneShape>().filter {
            it.id.startsWith("xy-bar-")
        }
        val labels = scene.elements.filterIsInstance<SceneText>().filter {
            it.text in setOf("10", "20", "30") && it.zIndex == 18
        }

        assertEquals(3, bars.size)
        assertEquals(3, labels.size)
        assertTrue(bars.all { it.bounds.width > 0f && it.bounds.height > 0f })
        assertTrue(labels.zip(bars).all { (label, bar) -> label.bounds.left >= bar.bounds.right })
    }

    @Test
    fun usesFirstPlotValuesForEveryBarLabelLikeMermaid12Renderer() {
        val scene = render(
            """
            ---
            config:
              xyChart:
                showDataLabel: true
            ---
            xychart
                x-axis [A, B]
                y-axis 0 --> 100
                bar "First" [10, 20]
                bar "Second" [70, 80]
            """.trimIndent(),
        )

        val labels = scene.elements.filterIsInstance<SceneText>()
            .filter { text -> text.zIndex == 18 }

        assertEquals(listOf("10", "20", "10", "20"), labels.map(SceneText::text))
    }

    @Test
    fun anchorsBarsToPlotBoundaryLikeMermaid12BarPlot() {
        val scene = render(
            """
            xychart
                x-axis [A, B]
                y-axis -10 --> 10
                bar [5, 10]
            """.trimIndent(),
        )

        val bars = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id.startsWith("xy-bar-") }
        val plotBottom = bars.maxOf { bar -> bar.bounds.bottom }

        assertEquals(2, bars.size)
        assertTrue(bars.all { bar -> bar.bounds.bottom == plotBottom })
        assertTrue(bars.first().bounds.height < bars.last().bounds.height)
    }

    @Test
    fun appliesNestedConfigAndThemeVariables() {
        val scene = render(
            """
            ---
            config:
              xyChart:
                width: 540
                height: 360
                titleFontSize: 24
                xAxis:
                  labelRotation: -45
              themeVariables:
                xyChart:
                  backgroundColor: "#f0fdf4"
                  titleColor: "#166534"
                  plotColorPalette: "#dc2626, #2563eb"
            ---
            xychart
                title "Configured"
                x-axis [One, Two]
                bar [2, 4]
                line [1, 3]
            """.trimIndent(),
        )

        assertEquals(540f, scene.width)
        assertEquals(360f, scene.height)
        assertEquals(SceneColor(0xFFF0FDF4), scene.background)
        assertEquals(
            SceneColor(0xFF166534),
            scene.elements.filterIsInstance<SceneText>()
                .single { it.text == "Configured" }
                .color,
        )
        assertEquals(
            SceneColor(0xFFDC2626),
            scene.elements.filterIsInstance<SceneShape>()
                .first { it.id.startsWith("xy-bar-") }
                .fill,
        )
        assertTrue(scene.elements.filterIsInstance<SceneText>().any {
            it.text == "One" && it.rotationDegrees == -45f
        })
    }

    @Test
    fun keepsEqualLinearDomainAtRangeMidpoint() {
        val scene = render(
            """
            xychart
                x-axis 5 --> 5
                y-axis 10 --> 10
                line [10]
            """.trimIndent(),
        )
        val path = scene.elements.filterIsInstance<ScenePath>()
            .single { it.id == "xy-line-0" }

        assertEquals(1, path.points.size)
        assertTrue(path.points.single().x in 300f..400f)
        assertTrue(path.points.single().y in 200f..300f)
    }

    @Test
    fun preservesMeasuredFontFamilyOnRenderedText() {
        val customFont = "Test Sans"
        val result = engine.render(
            """
            xychart
                title "Compact monthly view"
                x-axis "Month" [January, February]
                y-axis "Revenue" 0 --> 100
                bar "Actual" [40, 80]
            """.trimIndent(),
            context.copy(options = MermaidRenderOptions(fontFamily = customFont)),
        )
        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val texts = scene.elements.filterIsInstance<SceneText>()

        assertTrue(texts.isNotEmpty())
        assertTrue(texts.all { text -> text.fontFamily == customFont })
        assertTrue(texts.none(SceneText::softWrap))
    }

    @Test
    fun keepsRotatedBottomAxisLabelsInsideChartBounds() {
        val result = engine.render(
            """
            ---
            config:
              xyChart:
                width: 420
                height: 340
                plotReservedSpacePercent: 65
                xAxis:
                  labelRotation: -60
            ---
            xychart
                title "Compact monthly view"
                x-axis [January, February, March, April, May, June]
                y-axis 0 --> 80
                bar [22, 35, 31, 48, 59, 72]
            """.trimIndent(),
            context,
        )
        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val expectedLabels = setOf("January", "February", "March", "April", "May", "June")
        val labels = scene.elements.filterIsInstance<SceneText>()
            .filter { text -> text.text in expectedLabels }

        assertEquals(6, labels.size)
        labels.forEach { label ->
            val transformedBounds = label.rotatedBounds()
            assertTrue(
                transformedBounds.left >= 0f &&
                    transformedBounds.top >= 0f &&
                    transformedBounds.right <= scene.width &&
                    transformedBounds.bottom <= scene.height,
                "${label.text} is outside the chart: $transformedBounds in " +
                    "${scene.width}x${scene.height}",
            )
        }
    }

    @Test
    fun keepsRotatedLabelsAndTicksWhenMetricsIncludeComposeLineHeight() {
        val composeLikeContext = context.copy(
            textMetrics = TextMetricProvider { request ->
                TextMetrics(
                    width = request.text.length * request.fontSize * 0.5f,
                    height = request.fontSize * 1.2f,
                )
            },
        )
        val result = engine.render(
            """
            ---
            config:
              xyChart:
                width: 420
                height: 340
                plotReservedSpacePercent: 65
                xAxis:
                  labelRotation: -60
            ---
            xychart
                title "Compact monthly view"
                x-axis [January, February, March, April, May, June]
                y-axis 0 --> 80
                bar [22, 35, 31, 48, 59, 72]
            """.trimIndent(),
            composeLikeContext,
        )
        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val expectedLabels = setOf("January", "February", "March", "April", "May", "June")

        assertEquals(
            expectedLabels,
            scene.elements.filterIsInstance<SceneText>()
                .map(SceneText::text)
                .filterTo(mutableSetOf()) { text -> text in expectedLabels },
        )
        assertEquals(
            6,
            scene.elements.filterIsInstance<ScenePath>()
                .count { path -> path.id.startsWith("xy-bottom-axis-tick-") },
        )
    }

    @Test
    fun preservesMeasuredBoundsAndOutsideLabelsForHorizontalCharts() {
        val scene = render(
            """
            ---
            config:
              xyChart:
                showDataLabel: true
                showDataLabelOutsideBar: true
            ---
            xychart horizontal
                title "Review queue age"
                x-axis ["Security", "Privacy", "Legal", "Finance", "Operations"]
                bar "Minutes" [14, 9, 21, 7, 12]
            """.trimIndent(),
        )
        val texts = scene.elements.filterIsInstance<SceneText>()
        val category = texts.single { text -> text.text == "Security" }
        val legend = texts.single { text -> text.text == "Minutes" }
        val labels = texts.filter { text ->
            text.zIndex == 18 && text.text in setOf("14", "9", "21", "7", "12")
        }

        assertTrue(
            abs(category.bounds.width - category.text.length * category.fontSize * 0.55f) < 0.01f,
            "Horizontal category bounds must preserve the measured text width",
        )
        assertTrue(
            abs(legend.bounds.width - legend.text.length * legend.fontSize * 0.55f) < 0.01f,
            "Legend bounds must preserve the measured text width",
        )
        assertEquals(setOf("14", "9", "21", "7", "12"), labels.mapTo(mutableSetOf(), SceneText::text))
        assertTrue(
            labels.all { label -> label.fontSize == 16f },
            "Invalid negative SVG font sizes must match the browser's inherited 16px fallback",
        )
        assertTrue(labels.all { label -> label.bounds.right <= scene.width })
    }

    @Test
    fun preservesOfficialPlotLegendAndAxisPaintOrder() {
        val scene = render(
            """
            ---
            config:
              xyChart:
                showDataLabel: true
                showDataLabelOutsideBar: true
            ---
            xychart horizontal
                title "Review queue age"
                x-axis ["Security", "Privacy", "Legal", "Finance", "Operations"]
                bar "Minutes" [14, 9, 21, 7, 12]
            """.trimIndent(),
        )
        val label = scene.elements.single { element ->
            element is SceneText &&
                element.zIndex == 18 &&
                element.text == "21"
        }
        val legendMarker = scene.elements.single { element ->
            element is SceneShape && element.id == "xy-legend-bar-0"
        }
        val legendText = scene.elements.single { element ->
            element is SceneText &&
                element.text == "Minutes"
        }
        val axisLine = scene.elements.single { element ->
            element is ScenePath && element.id == "xy-left-axis-line"
        }

        assertTrue(scene.elements.indexOf(label) < scene.elements.indexOf(legendMarker))
        assertTrue(scene.elements.indexOf(legendMarker) < scene.elements.indexOf(legendText))
        assertTrue(scene.elements.indexOf(legendText) < scene.elements.indexOf(axisLine))
    }

    @Test
    fun reportsInvalidConfigurationWithoutThrowing() {
        val result = engine.render(
            """
            ---
            config:
              xyChart:
                width: 0
            ---
            xychart
                line [1, 2, 3]
            """.trimIndent(),
            context,
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.Configuration>(error)
        assertTrue(error.message.contains("xyChart.width"))
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected XY Chart render success:\n$source\n$result",
        ).value
    }

    private fun SceneText.rotatedBounds(): com.swithun.cmpmermaid.core.SceneRect {
        val pivot = rotationPivot ?: bounds.center
        val radians = rotationDegrees * PI.toFloat() / 180f
        val corners = listOf(
            bounds.left to bounds.top,
            bounds.right to bounds.top,
            bounds.right to bounds.bottom,
            bounds.left to bounds.bottom,
        ).map { (x, y) ->
            val offsetX = x - pivot.x
            val offsetY = y - pivot.y
            (pivot.x + offsetX * cos(radians) - offsetY * sin(radians)) to
                (pivot.y + offsetX * sin(radians) + offsetY * cos(radians))
        }
        return com.swithun.cmpmermaid.core.SceneRect(
            left = corners.minOf { point -> point.first },
            top = corners.minOf { point -> point.second },
            right = corners.maxOf { point -> point.first },
            bottom = corners.maxOf { point -> point.second },
        )
    }

}
