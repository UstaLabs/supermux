package com.swithun.cmpmermaid.core.pie

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

class PieStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * 8f,
                height = request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersDeterministicRandomizedCorpus() {
        val random = Random(12_00_00)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val generated = randomPie(random, caseIndex)
            val scene = render(generated.source)
            validateScene(generated, scene)
        }
    }

    private fun randomPie(
        random: Random,
        caseIndex: Int,
    ): GeneratedPieCase {
        val count = random.nextInt(from = 1, until = 21)
        val values = List(count) { index ->
            when {
                index == 0 -> random.nextInt(from = 1, until = 100).toDouble()
                index % 9 == 0 -> 0.0
                index % 7 == 0 -> 0.01
                else -> random.nextInt(from = 1, until = 500) / 10.0
            }
        }
        val total = values.sum()
        val visibleCount = values.count { value -> value / total * 100.0 >= 1.0 }
        val legendPosition = LEGEND_POSITIONS[caseIndex.mod(LEGEND_POSITIONS.size)]
        val highlightedIndex = caseIndex.mod(count)
        val showData = caseIndex % 2 == 0
        return GeneratedPieCase(
            source = buildString {
                appendLine("---")
                appendLine("title: Random pie $caseIndex")
                appendLine("config:")
                appendLine("  pie:")
                appendLine("    textPosition: ${0.2 + caseIndex.mod(7) * 0.1}")
                appendLine("    donutHole: ${caseIndex.mod(5) * 0.15}")
                appendLine("    legendPosition: $legendPosition")
                if (caseIndex % 3 == 0) {
                    appendLine("    highlightSlice: Slice $highlightedIndex")
                }
                appendLine("---")
                append("pie")
                if (showData) append(" showData")
                appendLine()
                appendLine("    accTitle: Random chart $caseIndex")
                appendLine("    accDescr: Deterministic randomized Pie chart")
                values.forEachIndexed { index, value ->
                    appendLine("""    "Slice $index" : $value""")
                }
            },
            sectionCount = count,
            visibleCount = visibleCount,
            showData = showData,
        )
    }

    private fun validateScene(
        generated: GeneratedPieCase,
        scene: MermaidScene,
    ) {
        val contextMessage = generated.source
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val slices = shapes.filter { shape -> shape.id.startsWith("pie-slice-") }
        val swatches = shapes.filter { shape -> shape.id.startsWith("pie-legend-swatch-") }
        val texts = scene.elements.filterIsInstance<SceneText>()

        assertTrue(scene.width.isFinite() && scene.width > 0f, contextMessage)
        assertTrue(scene.height.isFinite() && scene.height > 0f, contextMessage)
        assertTrue(slices.size == generated.visibleCount, contextMessage)
        assertTrue(swatches.size == generated.sectionCount, contextMessage)
        assertTrue(
            texts.count { text -> text.text.endsWith('%') } == generated.visibleCount,
            contextMessage,
        )
        assertTrue(
            texts.count { text -> text.text.startsWith("Slice ") } == generated.sectionCount,
            contextMessage,
        )
        if (generated.showData) {
            assertTrue(
                texts.filter { text -> text.text.startsWith("Slice ") }
                    .all { text -> '[' in text.text && ']' in text.text },
                contextMessage,
            )
        }
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
            if (element is SceneShape) {
                assertTrue(
                    element.geometry
                        ?.paths
                        .orEmpty()
                        .flatMap { path -> path.points }
                        .all { point -> point.x.isFinite() && point.y.isFinite() },
                    contextMessage,
                )
            }
        }
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected randomized Pie render success:\n$source\n$result",
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

    private data class GeneratedPieCase(
        val source: String,
        val sectionCount: Int,
        val visibleCount: Int,
        val showData: Boolean,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        val LEGEND_POSITIONS = listOf("top", "bottom", "left", "right", "center")
    }
}
