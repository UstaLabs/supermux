package com.swithun.cmpmermaid.core.gantt

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
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.officialGanttDocumentationCases
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GanttLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = metrics(),
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersTasksMilestonesVerticalMarkersSectionsAndGrid() {
        val scene = render(
            """
            gantt
                title Product delivery
                dateFormat YYYY-MM-DD
                axisFormat %b %d
                tickInterval 1day
                topAxis
                todayMarker off
                section Build
                Compile :done, crit, compile, 2025-01-01, 2d
                Package :active, package, after compile, 1d
                section Release
                Launch :milestone, launch, after package, 0d
                Deadline :vert, deadline, 2025-01-04, 1d
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()

        assertTrue(scene.width > 0f)
        assertTrue(scene.height > 0f)
        assertEquals(4, shapes.count { it.id.startsWith("gantt-task-") })
        assertEquals(
            SceneShapeKind.Diamond,
            shapes.single { it.id == "gantt-task-launch" }.kind,
        )
        val milestone = shapes.single { it.id == "gantt-task-launch" }
        val milestoneGeometry = assertNotNull(milestone.geometry)
        assertEquals(4, milestoneGeometry.outline.size)
        assertTrue(milestoneGeometry.outline[0].y < 0f)
        assertTrue(milestoneGeometry.outline[1].x > 0f)
        assertTrue(milestoneGeometry.outline[2].y > 0f)
        assertTrue(milestoneGeometry.outline[3].x < 0f)
        assertTrue(
            shapes.single { it.id == "gantt-task-deadline" }.bounds.height >
                shapes.single { it.id == "gantt-task-compile" }.bounds.height,
        )
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any {
            it.id.startsWith("gantt-grid-")
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any {
            it.text == "Product delivery"
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { it.text == "Build" })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { it.text == "Release" })
        val milestoneLabel = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == "Launch" }
        assertTrue(milestoneLabel.spans.single().italic)
        val verticalLabel = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == "Deadline" }
        assertEquals(SceneColor(0xFF000080), verticalLabel.color)
        assertEquals(15f, verticalLabel.fontSize)
    }

    @Test
    fun usesMermaidDefaultTaskPaletteAndDailyAutomaticTicks() {
        val scene = render(
            """
            gantt
                dateFormat YYYY-MM-DD
                axisFormat %b %d
                todayMarker off
                section Engineering
                Implementation :implementation, 2025-01-01, 13d
            """.trimIndent(),
        )
        val task = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "gantt-task-implementation" }
        val taskLabel = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == "Implementation" }
        val sectionLabel = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == "Engineering" }
        val gridLines = scene.elements.filterIsInstance<ScenePath>()
            .filter { it.id.startsWith("gantt-grid-") }

        assertEquals(SceneColor(0xFF534FBC), task.stroke)
        assertEquals(SceneColor(0xFFFFFFFF), taskLabel.color)
        assertTrue(
            gridLines.size >= 10,
            "A 13-day domain should use daily automatic ticks, but rendered ${gridLines.size}",
        )
        assertEquals(11 * 7f, sectionLabel.bounds.width)
        assertEquals(
            context.options.ganttSectionFontSize * 1.2f,
            sectionLabel.bounds.height,
            absoluteTolerance = 0.001f,
        )
    }

    @Test
    fun usesMeasuredD3AxisTextBounds() {
        val scene = render(
            """
            gantt
                dateFormat YYYY-MM-DD
                axisFormat %b %d
                tickInterval 1day
                topAxis
                todayMarker off
                Task :task, 2025-01-01, 2d
            """.trimIndent(),
        )
        val labels = scene.elements.filterIsInstance<SceneText>()
            .filter { it.text == "Jan 01" }
            .sortedBy { it.bounds.center.y }
        val grid = scene.elements.filterIsInstance<ScenePath>()
            .first { it.id == "gantt-grid-0" }

        assertEquals(2, labels.size)
        labels.forEach { label ->
            assertEquals(6 * 7f, label.bounds.width)
            assertEquals(10f, label.bounds.height)
            assertEquals(grid.points.first().x, label.bounds.center.x)
        }
        assertEquals(
            context.options.ganttTopPadding - 6.5f,
            labels.first().bounds.center.y,
        )
        assertEquals(
            scene.height - 50f + 9.5f,
            labels.last().bounds.center.y,
        )
    }

    @Test
    fun usesMermaid12BuiltInGanttPalette() {
        val scene = render(
            """
            ---
            config:
              theme: dark
            ---
            gantt
                dateFormat YYYY-MM-DD
                todayMarker off
                section Delivery
                Active work :active, work, 2025-01-01, 2d
            """.trimIndent(),
        )
        val task = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "gantt-task-work" }
        val section = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "gantt-row-0" }
        val taskText = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == "Active work" }

        assertEquals(SceneColor(0xFF333333), scene.background)
        assertEquals(SceneColor(0x33B4AC76), section.fill)
        assertEquals(SceneColor(0xFF81B1DB), task.fill)
        assertEquals(SceneColor(0xFFFFFFFF), task.stroke)
        assertEquals(SceneColor(0xFF2C2C2C), taskText.color)
    }

    @Test
    fun compactsNonOverlappingTasksAndDrawsExcludedRanges() {
        val scene = render(
            """
            ---
            displayMode: compact
            config:
              gantt:
                barHeight: 24
                barGap: 6
            ---
            gantt
                dateFormat YYYY-MM-DD
                excludes weekends
                todayMarker off
                section Delivery
                First :first, 2025-01-03, 4d
                Second :second, after first, 1d
                Overlap :third, 2025-01-03, 2d
            """.trimIndent(),
        )
        val first = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "gantt-task-first" }
        val second = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "gantt-task-second" }
        val overlap = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "gantt-task-third" }

        assertEquals(first.bounds.top, second.bounds.top)
        assertTrue(overlap.bounds.top > first.bounds.top)
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any {
            it.id.startsWith("gantt-exclude-")
        })
    }

    @Test
    fun exposesSanitizedTaskLinksAsSceneInteractions() {
        val scene = render(
            """
            gantt
                dateFormat YYYY-MM-DD
                Task :task, 2025-01-01, 1d
                click task href "https://example.com"
            """.trimIndent(),
        )

        val interaction = assertNotNull(scene.interactions.singleOrNull())
        assertEquals("task", interaction.nodeId)
        assertEquals("https://example.com", interaction.link)
        assertEquals("_self", interaction.linkTarget)
    }

    @Test
    fun rendersSupportedOfficialDocumentationExamples() {
        assertEquals(11, officialGanttDocumentationCases.size)
        val failures = officialGanttDocumentationCases
            .filterNot { case -> case.id.startsWith("011_") }
            .mapNotNull { case ->
                when (val result = engine.render(case.source, context)) {
                    is GMResult.Ok -> null
                    is GMResult.Err -> "${case.id}: ${result.error.message}"
                }
            }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Gantt render cases:\n",
                separator = "\n",
            ),
        )
        val cssCase = officialGanttDocumentationCases.single { case ->
            case.id.startsWith("011_")
        }
        val unsupported = assertIs<GMResult.Err<MermaidError>>(
            engine.render(cssCase.source, context),
        )
        assertIs<MermaidError.UnsupportedFeature>(unsupported.error)
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(
            source = source,
            context = context.copy(
                options = MermaidRenderOptions(
                    ganttUseWidth = 900f,
                ),
            ),
        )
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected Gantt render success:\n$source\n$result",
        ).value
    }

    private fun metrics(): TextMetricProvider = TextMetricProvider { request ->
        val charactersPerLine = (request.maxWidth / 7f).toInt().coerceAtLeast(1)
        val lines = request.text
            .split('\n')
            .sumOf { line ->
                ceil(line.length.toDouble() / charactersPerLine)
                    .toInt()
                    .coerceAtLeast(1)
            }
        TextMetrics(
            width = minOf(
                request.maxWidth,
                request.text.lineSequence().maxOfOrNull(String::length).orZero() * 7f,
            ),
            height = lines * request.fontSize * request.lineHeight,
        )
    }

    private fun Int?.orZero(): Int = this ?: 0
}
