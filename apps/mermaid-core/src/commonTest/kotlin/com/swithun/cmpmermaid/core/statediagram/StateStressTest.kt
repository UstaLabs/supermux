package com.swithun.cmpmermaid.core.statediagram

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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StateStressTest {
    private val engine = MermaidEngine()
    private val textMetrics = TextMetricProvider { request ->
        val charactersPerLine = (request.maxWidth / 8f).toInt().coerceAtLeast(1)
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
                request.text.lineSequence().maxOfOrNull(String::length).orZero() * 8f,
            ),
            height = lines * request.fontSize * request.lineHeight,
        )
    }

    @Test
    fun rendersDeterministicRandomizedCorpusWithDagre() {
        val random = Random(12_00_00)
        repeat(RANDOM_CASE_COUNT) { index ->
            val source = randomStateDiagram(random, index)
            validateScene(
                caseName = "random-$index-dagre",
                source = source,
                scene = render(source, "dagre"),
            )
        }
    }

    private fun render(
        source: String,
        layout: String,
    ): MermaidScene {
        val result = engine.render(
            source = source,
            context = MermaidRenderContext(
                textMetrics = textMetrics,
                options = MermaidRenderOptions(
                    look = "neo",
                    stateLayout = layout,
                ),
            ),
        )
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected State render success for $layout:\n$source\n$result",
        ).value
    }

    private fun validateScene(
        caseName: String,
        source: String,
        scene: MermaidScene,
    ) {
        val context = "$caseName:\n$source"
        assertTrue(scene.width.isFinite() && scene.width > 0f, context)
        assertTrue(scene.height.isFinite() && scene.height > 0f, context)
        assertTrue(scene.elements.filterIsInstance<SceneShape>().size >= 4, context)
        assertTrue(scene.elements.filterIsInstance<ScenePath>().size >= 3, context)
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null) {
                assertValid(bounds, context)
            }
            if (element is ScenePath) {
                assertTrue(element.points.size >= 2, context)
                assertTrue(
                    element.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                    context,
                )
            }
        }
    }

    private fun randomStateDiagram(
        random: Random,
        caseIndex: Int,
    ): String {
        val stateCount = random.nextInt(from = 3, until = 10)
        val states = List(stateCount) { index -> "Case${caseIndex}State$index" }
        return buildString {
            appendLine("stateDiagram-v2")
            appendLine("    direction ${DIRECTIONS.random(random)}")
            appendLine("    [*] --> ${states.first()}")
            states.forEachIndexed { index, state ->
                if (random.nextBoolean()) {
                    appendLine("    $state : State $caseIndex.$index")
                }
                if (random.nextInt(5) == 0) {
                    appendLine("    classDef style$index fill:#dbeafe,stroke:#2563eb")
                    appendLine("    class $state style$index")
                }
            }
            states.zipWithNext().forEachIndexed { index, (start, end) ->
                appendLine("    $start --> $end : event $index")
            }
            repeat(random.nextInt(1, stateCount)) { relationIndex ->
                val start = states.random(random)
                val end = states.random(random)
                appendLine("    $start --> $end : retry $relationIndex")
            }
            if (caseIndex % 4 == 0) {
                val composite = "Composite$caseIndex"
                appendLine("    ${states.last()} --> $composite")
                appendLine("    state $composite {")
                appendLine("        [*] --> InnerA")
                appendLine("        InnerA --> InnerB")
                if (caseIndex % 8 == 0) {
                    appendLine("        --")
                    appendLine("        [*] --> InnerC")
                    appendLine("        InnerC --> [*]")
                } else {
                    appendLine("        InnerB --> [*]")
                }
                appendLine("    }")
                appendLine("    $composite --> [*]")
            } else {
                appendLine("    ${states.last()} --> [*]")
            }
        }
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

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        val DIRECTIONS = listOf("TB", "BT", "LR", "RL")
    }
}
