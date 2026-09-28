package com.swithun.cmpmermaid.core.quadrant

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuadrantStressTest {
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
    fun renders256DeterministicRandomizedQuadrantCharts() {
        val random = Random(12_00_13)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val pointCount = random.nextInt(from = 1, until = 21)
            val source = randomQuadrant(random, caseIndex, pointCount)
            val first = render(source)
            val second = render(source)

            assertEquals(
                first,
                second,
                "Quadrant stress case $caseIndex was not deterministic",
            )
            validateScene(first, pointCount, source)
        }
    }

    private fun randomQuadrant(
        random: Random,
        caseIndex: Int,
        pointCount: Int,
    ): String = buildString {
        if (caseIndex % 3 == 0) {
            appendLine("---")
            appendLine("config:")
            appendLine("  quadrantChart:")
            appendLine("    chartWidth: ${360 + caseIndex % 141}")
            appendLine("    chartHeight: ${340 + caseIndex % 161}")
            appendLine("    xAxisPosition: ${if (caseIndex % 2 == 0) "top" else "bottom"}")
            appendLine("    yAxisPosition: ${if (caseIndex % 4 < 2) "left" else "right"}")
            appendLine("    pointRadius: ${4 + caseIndex % 8}")
            appendLine("  themeVariables:")
            appendLine("    quadrantPointFill: \"#2563eb\"")
            appendLine("---")
        }
        appendLine("quadrantChart")
        appendLine("  title Random Quadrant $caseIndex")
        appendLine("  accTitle: Random accessible title $caseIndex")
        appendLine("  x-axis \"Low reach $caseIndex\" --> \"High reach $caseIndex\"")
        appendLine("  y-axis \"Low impact $caseIndex\" --> \"High impact $caseIndex\"")
        appendLine("  quadrant-1 Expand $caseIndex")
        appendLine("  quadrant-2 Promote $caseIndex")
        appendLine("  quadrant-3 Review $caseIndex")
        appendLine("  quadrant-4 Maintain $caseIndex")
        appendLine(
            "  classDef priority color: #109060, radius: 10, " +
                "stroke-color: #064e3b, stroke-width: 3px",
        )
        repeat(pointCount) { pointIndex ->
            val x = when (pointIndex) {
                0 -> 0
                1 -> 100
                else -> random.nextInt(from = 0, until = 101)
            }
            val y = when (pointIndex) {
                0 -> 100
                1 -> 0
                else -> random.nextInt(from = 0, until = 101)
            }
            append("  \"Point $caseIndex-$pointIndex")
            if (pointIndex % 7 == 0) append(" 東京")
            append("\"")
            if (pointIndex % 2 == 0) append(":::priority")
            append(": [${coordinate(x)}, ${coordinate(y)}]")
            if (pointIndex % 5 == 0) {
                append(
                    " color: #ff3300, radius: ${6 + pointIndex % 9}, " +
                        "stroke-color: #7f1d1d, stroke-width: 2px",
                )
            }
            appendLine()
        }
    }

    private fun coordinate(hundredths: Int): String = when (hundredths) {
        0 -> "0"
        100 -> "1"
        else -> "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected randomized Quadrant render success:\n$source\n$result",
        ).value
    }

    private fun validateScene(
        scene: MermaidScene,
        pointCount: Int,
        source: String,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, source)
        assertEquals(
            pointCount,
            scene.elements.filterIsInstance<SceneShape>()
                .count { shape -> shape.kind == SceneShapeKind.Circle },
            source,
        )
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
    }
}
