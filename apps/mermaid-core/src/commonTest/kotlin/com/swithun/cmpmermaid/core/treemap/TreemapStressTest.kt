package com.swithun.cmpmermaid.core.treemap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
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

class TreemapStressTest {
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
    fun renders256DeterministicRandomizedTreemaps() {
        val random = Random(12_00_19)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val source = randomTreemap(random, caseIndex)
            val first = render(source)
            val second = render(source)

            assertEquals(first, second, "Treemap stress case $caseIndex was not deterministic")
            validateScene(first, source)
        }
    }

    private fun randomTreemap(
        random: Random,
        caseIndex: Int,
    ): String = buildString {
        appendLine("---")
        appendLine("config:")
        appendLine("  theme: ${THEMES[caseIndex.mod(THEMES.size)]}")
        appendLine("  treemap:")
        appendLine("    useMaxWidth: ${caseIndex % 2 == 0}")
        appendLine("    padding: ${caseIndex % 16}")
        appendLine("    diagramPadding: ${4 + caseIndex % 21}")
        appendLine("    showValues: ${caseIndex % 4 != 0}")
        appendLine("    nodeWidth: ${60 + caseIndex % 61}")
        appendLine("    nodeHeight: ${25 + caseIndex % 36}")
        appendLine("    valueFormat: '${VALUE_FORMATS[caseIndex.mod(VALUE_FORMATS.size)]}'")
        appendLine("---")
        appendLine(if (caseIndex % 3 == 0) "treemap" else "treemap-beta")
        appendLine("title Capacity $caseIndex")
        appendLine("\"Portfolio $caseIndex\"")
        val sectionCount = random.nextInt(from = 2, until = 7)
        repeat(sectionCount) { sectionIndex ->
            val styledSection = sectionIndex == 0 && caseIndex % 3 == 0
            append("    \"Section $sectionIndex")
            if (sectionIndex == 1) {
                append(" 東京")
            }
            append("\"")
            if (styledSection) {
                append(":::accent")
            }
            appendLine()
            repeat(random.nextInt(from = 2, until = 9)) { leafIndex ->
                val separator = if ((sectionIndex + leafIndex) % 2 == 0) ":" else ","
                val value = random.nextInt(from = 1, until = 50_001)
                append("        \"Leaf $sectionIndex-$leafIndex")
                if (leafIndex == 1) {
                    append(" long descriptive label")
                }
                append("\"$separator ")
                append(value)
                if (leafIndex == 0 && sectionIndex == 0 && caseIndex % 5 == 0) {
                    append(":::accent")
                }
                appendLine()
            }
        }
        appendLine("classDef accent fill:#2563eb,stroke:#0f172a,color:#ffffff;")
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            "Expected randomized Treemap render success:\n$source",
        ).value

    private fun validateScene(
        scene: MermaidScene,
        source: String,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, source)
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertTrue(shapes.isNotEmpty(), source)
        assertTrue(texts.isNotEmpty(), source)
        assertEquals(shapes.size, shapes.map(SceneShape::id).distinct().size, source)
        shapes.forEach { shape -> assertValid(shape.bounds, source) }
        texts.forEach { text -> assertValid(text.bounds, source) }
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
        val VALUE_FORMATS = listOf(",", "$", ".1f", ".1%", "$0,0", "$.2f", "$,.2f")
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
