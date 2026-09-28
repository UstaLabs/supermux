package com.swithun.cmpmermaid.core.gantt

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GanttStressTest {
    private val engine = MermaidEngine()
    private val textMetrics = TextMetricProvider { request ->
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

    @Test
    fun rendersDeterministicRandomizedCorpus() {
        val random = Random(12_00_00)
        repeat(RANDOM_CASE_COUNT) { index ->
            val generated = randomGanttDiagram(random, index)
            validateScene(
                caseName = "random-$index",
                source = generated.source,
                expectedTasks = generated.taskCount,
                scene = render(generated.source),
            )
        }
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(
            source = source,
            context = MermaidRenderContext(
                textMetrics = textMetrics,
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

    private fun validateScene(
        caseName: String,
        source: String,
        expectedTasks: Int,
        scene: MermaidScene,
    ) {
        val context = "$caseName:\n$source"
        val taskShapes = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id.startsWith("gantt-task-") }

        assertTrue(scene.width.isFinite() && scene.width > 0f, context)
        assertTrue(scene.height.isFinite() && scene.height > 0f, context)
        assertEquals(expectedTasks, taskShapes.size, context)
        assertEquals(expectedTasks, taskShapes.map(SceneShape::id).distinct().size, context)
        assertTrue(
            scene.elements.filterIsInstance<ScenePath>().any { path ->
                path.id.startsWith("gantt-grid-")
            },
            context,
        )
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null) assertValid(bounds, context)
            if (element is ScenePath) {
                assertTrue(element.points.size >= 2, context)
                assertTrue(
                    element.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                    context,
                )
            }
        }
    }

    private fun randomGanttDiagram(
        random: Random,
        caseIndex: Int,
    ): GeneratedGanttCase {
        val taskCount = random.nextInt(from = 3, until = 13)
        val sectionCount = random.nextInt(from = 1, until = 4)
        val compact = caseIndex % 3 == 0
        val excludesWeekends = caseIndex % 4 == 0
        val topAxis = caseIndex % 5 == 0
        val startDay = random.nextInt(from = 1, until = 15)
        return GeneratedGanttCase(
            taskCount = taskCount,
            source = buildString {
                if (compact) {
                    appendLine("---")
                    appendLine("displayMode: compact")
                    appendLine("---")
                }
                appendLine("gantt")
                appendLine("    title Random schedule $caseIndex")
                appendLine("    dateFormat YYYY-MM-DD")
                appendLine("    axisFormat %b %d")
                appendLine("    tickInterval ${TICK_INTERVALS.random(random)}")
                appendLine("    todayMarker off")
                if (excludesWeekends) appendLine("    excludes weekends")
                if (topAxis) appendLine("    topAxis")
                repeat(taskCount) { taskIndex ->
                    if (taskIndex % ceil(taskCount.toDouble() / sectionCount).toInt() == 0) {
                        appendLine("    section Team ${taskIndex % sectionCount + 1}")
                    }
                    val flags = taskFlags(taskIndex)
                    val id = "case${caseIndex}task$taskIndex"
                    val start = when {
                        taskIndex == 0 -> "2025-01-${startDay.toString().padStart(2, '0')}"
                        taskIndex >= 3 && taskIndex % 4 == 0 ->
                            "after case${caseIndex}task${taskIndex - 1} " +
                                "case${caseIndex}task${taskIndex - 3}"
                        else -> "after case${caseIndex}task${taskIndex - 1}"
                    }
                    val duration = when {
                        taskIndex % 13 == 0 -> "0d"
                        else -> "${random.nextInt(from = 1, until = 6)}d"
                    }
                    appendLine(
                        "    Task $caseIndex.$taskIndex :$flags$id, $start, $duration",
                    )
                }
            },
        )
    }

    private fun taskFlags(index: Int): String {
        val flags = buildList {
            when {
                index % 17 == 0 -> add("vert")
                index % 13 == 0 -> add("milestone")
                else -> {
                    if (index % 5 == 0) add("crit")
                    if (index % 7 == 0) add("done")
                    if (index % 11 == 0) add("active")
                }
            }
        }
        return if (flags.isEmpty()) "" else flags.joinToString(postfix = ", ")
    }

    private fun assertValid(
        bounds: SceneRect,
        context: String,
    ) {
        assertTrue(bounds.left.isFinite(), context)
        assertTrue(bounds.top.isFinite(), context)
        assertTrue(bounds.right.isFinite(), context)
        assertTrue(bounds.bottom.isFinite(), context)
        assertTrue(bounds.width >= 0f, context)
        assertTrue(bounds.height >= 0f, context)
    }

    private fun Int?.orZero(): Int = this ?: 0

    private data class GeneratedGanttCase(
        val source: String,
        val taskCount: Int,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        val TICK_INTERVALS = listOf("1day", "2day", "1week")
    }
}
