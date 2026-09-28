package com.swithun.cmpmermaid.core.timeline

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val width = minOf(request.maxWidth, request.text.length * request.fontSize * 0.55f)
            TextMetrics(
                width = width,
                height = request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun renders256DeterministicRandomizedTimelines() {
        val random = Random(12_00_13)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val sectionCount = if (caseIndex % 3 == 0) 0 else random.nextInt(1, 4)
            val taskCount = random.nextInt(maxOf(1, sectionCount), 9)
            val generated = randomTimeline(random, caseIndex, sectionCount, taskCount)
            val first = render(generated.source)
            val second = render(generated.source)

            assertEquals(first, second, "Timeline stress case $caseIndex was not deterministic")
            validateScene(first, generated.nodeCount, generated.source)
        }
    }

    private fun randomTimeline(
        random: Random,
        caseIndex: Int,
        sectionCount: Int,
        taskCount: Int,
    ): GeneratedTimeline {
        var eventCount = 0
        val source = buildString {
            if (caseIndex % 4 == 0) {
                appendLine("---")
                appendLine("config:")
                appendLine("  theme: ${THEMES[caseIndex.mod(THEMES.size)]}")
                appendLine("  look: ${if (caseIndex % 8 == 0) "neo" else "classic"}")
                appendLine("  timeline:")
                appendLine("    leftMargin: ${80 + caseIndex % 121}")
                appendLine("    padding: ${12 + caseIndex % 39}")
                appendLine("    useMaxWidth: ${caseIndex % 2 == 0}")
                appendLine("    disableMulticolor: ${caseIndex % 5 == 0}")
                appendLine("---")
            }
            append("timeline")
            if (caseIndex % 2 == 1) append(" TD")
            appendLine()
            appendLine("  title Timeline $caseIndex")
            appendLine("  accTitle: Accessible timeline $caseIndex")
            var taskIndex = 0
            repeat(maxOf(sectionCount, 1)) { sectionIndex ->
                if (sectionCount > 0) {
                    appendLine("  section Phase $caseIndex-$sectionIndex")
                }
                val remainingTasks = taskCount - taskIndex
                val remainingSections = maxOf(sectionCount, 1) - sectionIndex
                val tasksHere = if (remainingSections == 1) {
                    remainingTasks
                } else {
                    random.nextInt(1, remainingTasks - remainingSections + 2)
                }
                repeat(tasksHere) {
                    val currentTask = taskIndex++
                    val events = random.nextInt(0, 4)
                    eventCount += events
                    append("    Period $caseIndex-$currentTask")
                    repeat(events) { eventIndex ->
                        append(" : Event $currentTask-$eventIndex")
                        if ((currentTask + eventIndex) % 5 == 0) append(" <br> detail")
                    }
                    appendLine()
                }
            }
        }
        return GeneratedTimeline(
            source = source,
            nodeCount = sectionCount + taskCount + eventCount,
        )
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            "Expected randomized Timeline render success:\n$source",
        ).value

    private fun validateScene(
        scene: MermaidScene,
        expectedNodes: Int,
        source: String,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, source)
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        assertEquals(expectedNodes, shapes.size, source)
        assertEquals(shapes.size, shapes.map(SceneShape::id).distinct().size, source)
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any {
            path -> path.id == "timeline-axis"
        }, source)
        scene.elements.forEach { element ->
            when (element) {
                is SceneAsset -> assertValid(element.bounds, source)
                is SceneShape -> assertValid(element.bounds, source)
                is SceneText -> assertValid(element.bounds, source)
                is ScenePath -> assertTrue(
                    element.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                    source,
                )
            }
        }
    }

    private fun assertValid(
        bounds: SceneRect,
        source: String,
    ) {
        assertTrue(bounds.left.isFinite(), source)
        assertTrue(bounds.top.isFinite(), source)
        assertTrue(bounds.right.isFinite(), source)
        assertTrue(bounds.bottom.isFinite(), source)
        assertTrue(bounds.width >= 0f, source)
        assertTrue(bounds.height >= 0f, source)
    }

    private data class GeneratedTimeline(
        val source: String,
        val nodeCount: Int,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        val THEMES = listOf(
            "default",
            "dark",
            "forest",
            "neutral",
            "base",
            "neo",
            "neo-dark",
            "redux",
            "redux-color",
            "redux-dark",
            "redux-dark-color",
        )
    }
}
