package com.swithun.cmpmermaid.core.journey

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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JourneyStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * 8f),
                height = request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersDeterministicRandomizedCorpus() {
        val random = Random(12_00_00)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val generated = randomJourney(random, caseIndex)
            val scene = render(generated.source)
            validateScene(generated, scene)
        }
    }

    private fun randomJourney(
        random: Random,
        caseIndex: Int,
    ): GeneratedJourneyCase {
        val sectionCount = random.nextInt(from = 1, until = 8)
        val actorCount = random.nextInt(from = 1, until = 10)
        val actors = List(actorCount) { index -> "Actor ${index + 1}" }
        var taskCount = 0
        val source = buildString {
            if (caseIndex % 4 == 0) {
                appendLine("---")
                appendLine("title: Random journey $caseIndex")
                appendLine("config:")
                appendLine("  journey:")
                appendLine("    width: ${130 + caseIndex.mod(5) * 10}")
                appendLine("    height: ${45 + caseIndex.mod(4) * 5}")
                appendLine("    taskMargin: ${35 + caseIndex.mod(6) * 5}")
                appendLine("---")
            }
            appendLine("journey")
            if (caseIndex % 4 != 0) {
                appendLine("    title Random journey $caseIndex")
            }
            appendLine("    accTitle: Journey case $caseIndex")
            appendLine("    accDescr: Deterministic randomized User Journey diagram")
            repeat(sectionCount) { sectionIndex ->
                appendLine("    section Stage ${sectionIndex + 1}")
                val tasksInSection = random.nextInt(from = 1, until = 9)
                repeat(tasksInSection) { taskIndex ->
                    val participants = List(
                        size = random.nextInt(from = 0, until = minOf(actorCount, 4) + 1),
                    ) {
                        actors[random.nextInt(actorCount)]
                    }.distinct()
                    val score = random.nextInt(from = 1, until = 6)
                    append("      Task ${sectionIndex + 1}.${taskIndex + 1}: $score")
                    if (participants.isNotEmpty()) {
                        append(": ")
                        append(participants.joinToString())
                    }
                    appendLine()
                    taskCount += 1
                }
            }
        }
        return GeneratedJourneyCase(
            source = source,
            sectionCount = sectionCount,
            taskCount = taskCount,
        )
    }

    private fun validateScene(
        generated: GeneratedJourneyCase,
        scene: MermaidScene,
    ) {
        val contextMessage = generated.source
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val paths = scene.elements.filterIsInstance<ScenePath>()

        assertTrue(scene.width.isFinite() && scene.width > 0f, contextMessage)
        assertTrue(scene.height.isFinite() && scene.height > 0f, contextMessage)
        assertTrue(
            shapes.count { shape -> shape.id.startsWith("journey-section-") } ==
                generated.sectionCount,
            contextMessage,
        )
        assertTrue(
            shapes.count { shape ->
                shape.id.startsWith("journey-task-") && "-actor-" !in shape.id
            } == generated.taskCount,
            contextMessage,
        )
        assertTrue(
            shapes.count { shape ->
                shape.id.startsWith("journey-face-") && "-eye-" !in shape.id
            } == generated.taskCount,
            contextMessage,
        )
        assertTrue(
            paths.count { path -> path.id.startsWith("journey-task-line-") } ==
                generated.taskCount,
            contextMessage,
        )
        assertTrue(
            paths.singleOrNull { path -> path.id == "journey-activity-line" } != null,
            contextMessage,
        )
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null) {
                assertValid(bounds, contextMessage)
            }
            if (element is ScenePath) {
                assertTrue(
                    element.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                    contextMessage,
                )
            }
        }
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected randomized User Journey render success:\n$source\n$result",
        ).value
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

    private data class GeneratedJourneyCase(
        val source: String,
        val sectionCount: Int,
        val taskCount: Int,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256
    }
}
