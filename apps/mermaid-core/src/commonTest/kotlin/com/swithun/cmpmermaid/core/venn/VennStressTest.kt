package com.swithun.cmpmermaid.core.venn

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
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

class VennStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lineCount = request.text.count { character -> character == '\n' } + 1
            TextMetrics(
                width = request.text
                    .lineSequence()
                    .maxOfOrNull { line -> line.length * request.fontSize * 0.55f }
                    ?: 0f,
                height = request.fontSize * request.lineHeight * lineCount,
                lineCount = lineCount,
            )
        },
    )

    @Test
    fun renders256DeterministicRandomizedVennDiagrams() {
        val random = Random(12_00_20)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val source = randomVenn(random, caseIndex)
            val first = render(source)
            val second = render(source)

            assertEquals(first, second, "Venn stress case $caseIndex was not deterministic")
            validateScene(first, source)
        }
    }

    private fun randomVenn(
        random: Random,
        caseIndex: Int,
    ): String = buildString {
        appendLine("---")
        appendLine("config:")
        appendLine("  theme: ${THEMES[caseIndex.mod(THEMES.size)]}")
        appendLine("  venn:")
        appendLine("    width: ${560 + caseIndex % 321}")
        appendLine("    height: ${360 + caseIndex % 181}")
        appendLine("    padding: ${caseIndex % 25}")
        appendLine("    useDebugLayout: ${caseIndex % 17 == 0}")
        appendLine("    useMaxWidth: ${caseIndex % 2 == 0}")
        appendLine("---")
        appendLine("venn-beta")
        appendLine("title Capability overlap $caseIndex")
        val setCount = 2 + caseIndex % 3
        val setIds = (0 until setCount).map { index -> ('A'.code + index).toChar().toString() }
        setIds.forEachIndexed { index, id ->
            val size = 16 + random.nextInt(20)
            val label = if (index == 1 && caseIndex % 4 == 0) {
                "Domain $id 東京"
            } else {
                "Domain $id"
            }
            appendLine("set $id[\"$label\"]:$size")
            appendLine("  text ${id}1[\"Skill $id-${caseIndex.mod(7)}\"]")
            if ((caseIndex + index) % 3 == 0) {
                appendLine("  text ${id}2[\"Long capability label $index\"]")
            }
        }
        for (left in 0 until setCount) {
            for (right in left + 1 until setCount) {
                val overlap = 2 + random.nextInt(6)
                appendLine(
                    "union ${setIds[left]},${setIds[right]}" +
                        "[\"${setIds[left]}${setIds[right]}\"]:$overlap",
                )
                if ((left + right + caseIndex) % 4 == 0) {
                    appendLine(
                        "  text ${setIds[left]}${setIds[right]}1" +
                            "[\"Shared ${left + right}\"]",
                    )
                }
            }
        }
        if (setCount >= 3) {
            appendLine(
                "union ${setIds.take(3).joinToString(",")}" +
                    "[\"Core\"]:${1 + caseIndex % 2}",
            )
        }
        if (setCount == 4 && caseIndex % 3 == 0) {
            appendLine("union ${setIds.joinToString(",")}[\"All\"]:0.5")
        }
        appendLine("style ${setIds.first()} fill:#2563eb,stroke:#1e3a8a,fill-opacity:0.18")
        appendLine(
            "style ${setIds[0]},${setIds[1]} " +
                "color:#111827,fill:rgba(250, 204, 21, 0.25)",
        )
        appendLine("style ${setIds.first()}1 color:#dc2626")
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            "Expected randomized Venn render success:\n$source",
        ).value

    private fun validateScene(
        scene: MermaidScene,
        source: String,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, source)
        val paths = scene.elements.filterIsInstance<ScenePath>()
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertTrue(paths.isNotEmpty(), source)
        assertTrue(texts.isNotEmpty(), source)
        assertEquals(paths.size, paths.map(ScenePath::id).distinct().size, source)
        paths.forEach { path ->
            assertTrue(path.points.all { point -> point.x.isFinite() && point.y.isFinite() }, source)
        }
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
