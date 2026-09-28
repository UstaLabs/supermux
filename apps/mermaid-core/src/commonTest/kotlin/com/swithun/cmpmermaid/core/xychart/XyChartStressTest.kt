package com.swithun.cmpmermaid.core.xychart

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

class XyChartStressTest {
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
    fun rendersDeterministicRandomizedCorpus() {
        val random = Random(12_00_00)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val source = randomChart(random, caseIndex)
            validateScene(render(source), source)
        }
    }

    private fun randomChart(
        random: Random,
        caseIndex: Int,
    ): String {
        val pointCount = random.nextInt(from = 2, until = 13)
        val seriesCount = random.nextInt(from = 1, until = 5)
        val horizontal = caseIndex % 5 == 0
        val categorical = caseIndex % 3 != 0
        val showLabels = caseIndex % 4 == 0
        return buildString {
            if (showLabels || caseIndex % 7 == 0) {
                appendLine("---")
                appendLine("config:")
                appendLine("  xyChart:")
                appendLine("    showDataLabel: $showLabels")
                appendLine("    showDataLabelOutsideBar: ${caseIndex % 8 == 0}")
                appendLine("    showLegend: ${caseIndex % 7 != 0}")
                appendLine("    plotReservedSpacePercent: ${50 + caseIndex % 31}")
                appendLine("---")
            }
            append("xychart")
            if (horizontal) append(" horizontal")
            appendLine()
            appendLine("    title \"Random XY $caseIndex\"")
            if (categorical) {
                appendLine(
                    "    x-axis \"Category\" [" +
                        List(pointCount) { index -> "C$index" }.joinToString(", ") +
                        "]",
                )
            } else {
                appendLine("    x-axis \"Input\" -10 --> 30")
            }
            appendLine("    y-axis \"Value\" -25 --> 125")
            repeat(seriesCount) { seriesIndex ->
                val type = if ((caseIndex + seriesIndex) % 2 == 0) "bar" else "line"
                append("    $type \"Series $seriesIndex\" [")
                append(
                    List(pointCount) {
                        random.nextInt(from = -20, until = 121).toString()
                    }.joinToString(", "),
                )
                appendLine("]")
            }
        }
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected randomized XY Chart render success:\n$source\n$result",
        ).value
    }

    private fun validateScene(
        scene: MermaidScene,
        source: String,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, source)
        assertTrue(scene.elements.any { it is ScenePath || it is SceneShape }, source)
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null) {
                assertValid(bounds, source)
            }
            if (element is ScenePath) {
                assertTrue(
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
