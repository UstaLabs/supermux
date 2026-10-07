package com.swithun.cmpmermaid.core.mindmap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidMindmapOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.ceil
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MindmapStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / (request.fontSize * 0.55f))
                .toInt()
                .coerceAtLeast(1)
            val lineCount = request.text.lineSequence().sumOf { line ->
                ceil(line.length.toDouble() / charactersPerLine)
                    .toInt()
                    .coerceAtLeast(1)
            }
            TextMetrics(
                width = min(
                    request.maxWidth,
                    request.text.lineSequence()
                        .maxOfOrNull(String::length)
                        .orZero() * request.fontSize * 0.55f,
                ),
                height = lineCount * request.fontSize * request.lineHeight,
            )
        },
    )

    @Test
    fun renders256DeterministicRandomMindmaps() {
        val random = Random(12_000)
        repeat(256) { caseIndex ->
            val generated = randomMindmap(random, caseIndex)
            val first = render(generated)
            val second = render(generated)

            assertEquals(
                first,
                second,
                "Mindmap stress case $caseIndex was not deterministic",
            )
            assertTrue(
                first.width.isFinite() && first.width > 0f,
                "Mindmap stress case $caseIndex had invalid width ${first.width}",
            )
            assertTrue(
                first.height.isFinite() && first.height > 0f,
                "Mindmap stress case $caseIndex had invalid height ${first.height}",
            )
            assertEquals(
                generated.nodeCount,
                first.elements.filterIsInstance<SceneShape>().size,
                "Mindmap stress case $caseIndex lost nodes",
            )
            assertEquals(
                generated.nodeCount - 1,
                first.elements.filterIsInstance<ScenePath>().size,
                "Mindmap stress case $caseIndex lost edges",
            )
            first.elements.forEach { element ->
                assertFinite(caseIndex, element)
            }
        }
    }

    private fun randomMindmap(
        random: Random,
        caseIndex: Int,
    ): GeneratedMindmap {
        val layout = LAYOUTS[caseIndex % LAYOUTS.size]
        val nodeCount = random.nextInt(from = 8, until = 29)
        var previousDepth = 1
        val source = buildString {
            appendLine("mindmap")
            appendLine("  root((Stress root $caseIndex))")
            repeat(nodeCount - 1) { nodeIndex ->
                val depth = when (random.nextInt(4)) {
                    0 -> (previousDepth + 1).coerceAtMost(6)
                    1 -> previousDepth
                    else -> random.nextInt(from = 1, until = previousDepth + 1)
                }
                previousDepth = depth
                val ordinal = nodeIndex + 1
                val label = when {
                    ordinal % 11 == 0 -> "品質確認 $caseIndex-$ordinal"
                    ordinal % 7 == 0 ->
                        "Long validation label for stress case $caseIndex node $ordinal"
                    else -> "Node $caseIndex-$ordinal"
                }
                append("  ".repeat(depth + 1))
                appendLine(shape(ordinal, label))
            }
        }.trimEnd()
        return GeneratedMindmap(
            source = source,
            layout = layout,
            nodeCount = nodeCount,
        )
    }

    private fun shape(
        ordinal: Int,
        label: String,
    ): String = when (ordinal % 7) {
        0 -> label
        1 -> "n$ordinal[$label]"
        2 -> "n$ordinal($label)"
        3 -> "n$ordinal(($label))"
        4 -> "n$ordinal)$label("
        5 -> "n$ordinal))$label(("
        else -> "n$ordinal{{$label}}"
    }

    private fun render(generated: GeneratedMindmap): MermaidScene {
        val result = engine.render(
            source = generated.source,
            context = context.copy(
                options = MermaidRenderOptions(
                    mindmap = MermaidMindmapOptions(
                        layoutAlgorithm = generated.layout,
                    ),
                ),
            ),
        )
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Mindmap ${generated.layout} failed with ${(result as? GMResult.Err)?.error}:\n" +
                generated.source,
        ).value
    }

    private fun assertFinite(
        caseIndex: Int,
        element: SceneElement,
    ) {
        when (element) {
            is SceneAsset -> assertRect(caseIndex, element.id, element.bounds.values())
            is SceneShape -> {
                assertRect(caseIndex, element.id, element.bounds.values())
                element.geometry?.paths.orEmpty().flatMap { path -> path.points }.forEach {
                    point -> assertPoint(caseIndex, element.id, point)
                }
                element.geometry?.outline.orEmpty().forEach { point ->
                    assertPoint(caseIndex, element.id, point)
                }
            }
            is SceneText -> assertRect(
                caseIndex,
                "text:${element.text}",
                element.bounds.values(),
            )
            is ScenePath -> {
                assertTrue(element.strokeWidth.isFinite() && element.strokeWidth >= 0f)
                element.points.forEach { point ->
                    assertPoint(caseIndex, element.id, point)
                }
            }
        }
    }

    private fun assertRect(
        caseIndex: Int,
        elementId: String,
        values: List<Float>,
    ) {
        assertTrue(
            values.all(Float::isFinite),
            "Mindmap stress case $caseIndex/$elementId had non-finite bounds $values",
        )
    }

    private fun assertPoint(
        caseIndex: Int,
        elementId: String,
        point: ScenePoint,
    ) {
        assertTrue(
            point.x.isFinite() && point.y.isFinite(),
            "Mindmap stress case $caseIndex/$elementId had non-finite point $point",
        )
    }

    private fun com.swithun.cmpmermaid.core.SceneRect.values(): List<Float> =
        listOf(left, top, right, bottom)

    private fun Int?.orZero(): Int = this ?: 0

    private data class GeneratedMindmap(
        val source: String,
        val layout: String,
        val nodeCount: Int,
    )

    private companion object {
        val LAYOUTS = listOf("cose-bilkent", "dagre", "tidy-tree")
    }
}
