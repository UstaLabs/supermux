package com.swithun.cmpmermaid.core.kanban

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
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KanbanStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val characterWidth = request.fontSize * 0.55f
            val lines = request.text.split('\n').sumOf { line ->
                maxOf(1, ceil(line.length * characterWidth / request.maxWidth).toInt())
            }
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * characterWidth),
                height = lines * request.fontSize * request.lineHeight,
                lineCount = lines,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun renders256DeterministicRandomizedKanbanBoards() {
        val random = Random(12_00_14)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val source = randomKanban(random, caseIndex)
            val first = render(source)
            val second = render(source)

            assertEquals(first, second, "Kanban stress case $caseIndex was not deterministic")
            validateScene(first, source)
        }
    }

    private fun randomKanban(
        random: Random,
        caseIndex: Int,
    ): String = buildString {
        if (caseIndex % 4 == 0) {
            appendLine("---")
            appendLine("config:")
            appendLine("  theme: ${THEMES[caseIndex.mod(THEMES.size)]}")
            appendLine("  look: ${if (caseIndex % 8 == 0) "neo" else "classic"}")
            appendLine("  kanban:")
            appendLine("    sectionWidth: ${170 + caseIndex % 91}")
            appendLine("    ticketBaseUrl: \"https://issues.example/#TICKET#\"")
            appendLine("---")
        }
        appendLine("kanban")
        repeat(random.nextInt(1, 6)) { sectionIndex ->
            appendLine("  section${caseIndex}_$sectionIndex[Stage $caseIndex-$sectionIndex]")
            repeat(random.nextInt(0, 7)) { itemIndex ->
                append("    item${caseIndex}_${sectionIndex}_$itemIndex")
                append("[Task $caseIndex-$sectionIndex-$itemIndex")
                if ((caseIndex + itemIndex) % 3 == 0) {
                    append(" with deterministic validation detail")
                }
                append("]")
                val metadata = mutableListOf<String>()
                if ((caseIndex + itemIndex) % 2 == 0) {
                    metadata += "ticket: KB-${caseIndex * 10 + itemIndex}"
                }
                if ((sectionIndex + itemIndex) % 3 == 0) {
                    metadata += "assigned: Owner-$sectionIndex"
                }
                if ((caseIndex + sectionIndex + itemIndex) % 4 == 0) {
                    metadata += "priority: '${PRIORITIES[
                        (caseIndex + itemIndex).mod(PRIORITIES.size)
                    ]}'"
                }
                if (metadata.isNotEmpty()) {
                    append("@{ ${metadata.joinToString()} }")
                }
                appendLine()
            }
        }
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            "Expected randomized Kanban render success:\n$source",
        ).value

    private fun validateScene(
        scene: MermaidScene,
        source: String,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, source)
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        assertTrue(shapes.isNotEmpty(), source)
        assertEquals(shapes.size, shapes.map(SceneShape::id).distinct().size, source)
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

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        val PRIORITIES = listOf("Very High", "High", "Medium", "Low", "Very Low")
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
