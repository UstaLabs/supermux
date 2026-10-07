package com.swithun.cmpmermaid.core.sankey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneBlendMode
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SankeyStressTest {
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
    fun renders256DeterministicRandomizedSankeyCharts() {
        val random = Random(12_00_18)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val generated = randomSankey(random, caseIndex)
            val first = render(generated.source)
            val second = render(generated.source)

            assertEquals(first, second, "Sankey stress case $caseIndex was not deterministic")
            validateScene(first, generated)
        }
    }

    private fun randomSankey(
        random: Random,
        caseIndex: Int,
    ): GeneratedSankey {
        val nodeCount = random.nextInt(from = 5, until = 15)
        val nodes = List(nodeCount) { index ->
            when (index) {
                1 -> "Node, $caseIndex-$index"
                2 -> "Review \"ready\" $caseIndex-$index"
                3 -> "東京 $caseIndex-$index"
                else -> "Node $caseIndex-$index"
            }
        }
        val links = linkedSetOf<Pair<Int, Int>>()
        for (target in 1 until nodeCount) {
            links += random.nextInt(until = target) to target
        }
        repeat(random.nextInt(from = nodeCount, until = nodeCount * 3)) {
            val source = random.nextInt(until = nodeCount - 1)
            val target = random.nextInt(from = source + 1, until = nodeCount)
            links += source to target
        }
        val linkColor = LINK_COLORS[caseIndex.mod(LINK_COLORS.size)]
        val outlined = caseIndex % 2 != 0
        val source = buildString {
            appendLine("---")
            appendLine("config:")
            appendLine("  theme: ${THEMES[caseIndex.mod(THEMES.size)]}")
            appendLine("  sankey:")
            appendLine("    width: ${420 + caseIndex % 181}")
            appendLine("    height: ${300 + caseIndex % 151}")
            appendLine("    linkColor: \"$linkColor\"")
            appendLine("    nodeAlignment: ${ALIGNMENTS[caseIndex.mod(ALIGNMENTS.size)]}")
            appendLine("    useMaxWidth: ${caseIndex % 2 == 0}")
            appendLine("    showValues: ${caseIndex % 3 != 0}")
            appendLine("    prefix: \"${if (caseIndex % 4 == 0) "$" else ""}\"")
            appendLine("    suffix: \"${if (caseIndex % 5 == 0) " units" else ""}\"")
            appendLine("    nodeWidth: ${6 + caseIndex % 15}")
            appendLine("    nodePadding: ${4 + caseIndex % 15}")
            appendLine("    labelStyle: ${if (outlined) "outlined" else "legacy"}")
            appendLine("---")
            appendLine(if (caseIndex % 3 == 0) "sankey-beta" else "sankey")
            links.forEachIndexed { linkIndex, (from, to) ->
                val value = random.nextInt(from = 1, until = 101)
                val suffix = if (linkIndex % 11 == 0) "kg" else ""
                appendLine(
                    "${csv(nodes[from])},${csv(nodes[to])},$value${if (linkIndex % 4 == 0) ".5" else ""}$suffix",
                )
            }
        }
        return GeneratedSankey(
            source = source,
            nodeCount = nodeCount,
            linkCount = links.size,
            outlined = outlined,
            gradientLinks = linkColor == "gradient",
        )
    }

    private fun csv(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            "Expected randomized Sankey render success:\n$source",
        ).value

    private fun validateScene(
        scene: MermaidScene,
        generated: GeneratedSankey,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, generated.source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, generated.source)
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val labels = scene.elements.filterIsInstance<SceneText>()
        val paths = scene.elements.filterIsInstance<ScenePath>()
        assertEquals(generated.nodeCount, shapes.size, generated.source)
        assertEquals(
            if (generated.outlined) generated.nodeCount * 2 else generated.nodeCount,
            labels.size,
            generated.source,
        )
        assertEquals(generated.linkCount, paths.size, generated.source)
        assertEquals(shapes.size, shapes.map(SceneShape::id).distinct().size, generated.source)
        assertEquals(paths.size, paths.map(ScenePath::id).distinct().size, generated.source)
        paths.forEach { path ->
            assertEquals(SceneBlendMode.Multiply, path.blendMode, generated.source)
            assertEquals(0.5f, path.opacity, generated.source)
            if (generated.gradientLinks) {
                assertNotNull(path.strokeGradient, generated.source)
            } else {
                assertNull(path.strokeGradient, generated.source)
            }
            assertTrue(path.strokeWidth.isFinite() && path.strokeWidth >= 1f, generated.source)
            assertTrue(
                path.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                generated.source,
            )
            assertTrue(
                path.commands.all { command ->
                    when (command) {
                        is ScenePathCommand.MoveTo ->
                            command.point.x.isFinite() && command.point.y.isFinite()
                        is ScenePathCommand.LineTo ->
                            command.point.x.isFinite() && command.point.y.isFinite()
                        is ScenePathCommand.QuadraticTo ->
                            command.control.x.isFinite() &&
                                command.control.y.isFinite() &&
                                command.end.x.isFinite() &&
                                command.end.y.isFinite()
                        is ScenePathCommand.CubicTo ->
                            command.control1.x.isFinite() &&
                                command.control1.y.isFinite() &&
                                command.control2.x.isFinite() &&
                                command.control2.y.isFinite() &&
                                command.end.x.isFinite() &&
                                command.end.y.isFinite()
                        is ScenePathCommand.ArcTo ->
                            command.radius.isFinite() &&
                                command.end.x.isFinite() &&
                                command.end.y.isFinite()
                    }
                },
                generated.source,
            )
        }
        scene.elements.forEach { element ->
            when (element) {
                is SceneAsset -> assertValid(element.bounds, generated.source)
                is SceneShape -> assertValid(element.bounds, generated.source)
                is SceneText -> assertValid(element.bounds, generated.source)
                is ScenePath -> Unit
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

    private data class GeneratedSankey(
        val source: String,
        val nodeCount: Int,
        val linkCount: Int,
        val outlined: Boolean,
        val gradientLinks: Boolean,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        val ALIGNMENTS = listOf("left", "right", "center", "justify")
        val LINK_COLORS = listOf("gradient", "source", "target", "#64748b")
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
