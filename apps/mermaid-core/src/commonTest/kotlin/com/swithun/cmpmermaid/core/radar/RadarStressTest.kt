package com.swithun.cmpmermaid.core.radar

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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RadarStressTest {
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
    fun renders256DeterministicRandomizedRadarCharts() {
        val random = Random(12_00_17)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val source = randomRadar(random, caseIndex)
            val first = render(source)
            val second = render(source)

            assertEquals(first, second, "Radar stress case $caseIndex was not deterministic")
            validateScene(first, source)
        }
    }

    private fun randomRadar(
        random: Random,
        caseIndex: Int,
    ): String {
        val axisCount = random.nextInt(from = 3, until = 9)
        val curveCount = random.nextInt(from = 1, until = 6)
        val axes = List(axisCount) { index -> "axis$index" }
        return buildString {
            appendLine("---")
            appendLine("config:")
            appendLine("  theme: ${THEMES[caseIndex.mod(THEMES.size)]}")
            appendLine("  radar:")
            appendLine("    width: ${360 + caseIndex % 241}")
            appendLine("    height: ${320 + caseIndex % 281}")
            appendLine("    marginTop: ${20 + caseIndex % 41}")
            appendLine("    marginRight: ${20 + caseIndex % 31}")
            appendLine("    marginBottom: ${20 + caseIndex % 37}")
            appendLine("    marginLeft: ${20 + caseIndex % 29}")
            appendLine("    axisScaleFactor: ${0.8 + (caseIndex % 5) * 0.05}")
            appendLine("    axisLabelFactor: ${1.0 + (caseIndex % 4) * 0.05}")
            appendLine("    curveTension: ${(caseIndex % 6) * 0.1}")
            appendLine("    useMaxWidth: ${caseIndex % 2 == 0}")
            appendLine("---")
            appendLine(if (caseIndex % 3 == 0) "radar-beta:" else "radar-beta")
            appendLine("  title Radar matrix $caseIndex")
            appendLine("  accTitle: Accessible radar $caseIndex")
            appendLine(
                axes.mapIndexed { index, axis ->
                    "$axis[\"Axis $index ${if (index == 0) "東京" else ""}\"]"
                }.joinToString(prefix = "  axis ", separator = ", "),
            )
            repeat(curveCount) { curveIndex ->
                val values = List(axisCount) { random.nextInt(from = 1, until = 101) }
                if ((caseIndex + curveIndex) % 2 == 0) {
                    appendLine(
                        values.joinToString(
                            prefix = "  curve curve$curveIndex[\"Series $curveIndex\"] { ",
                            postfix = " }",
                        ),
                    )
                } else {
                    val ordered = axes.indices.shuffled(random)
                    appendLine(
                        ordered.joinToString(
                            prefix = "  curve curve$curveIndex[\"Series $curveIndex\"] { ",
                            postfix = " }",
                        ) { axisIndex -> "${axes[axisIndex]}: ${values[axisIndex]}" },
                    )
                }
            }
            appendLine("  min 0")
            appendLine("  max 100")
            appendLine("  ticks ${1 + caseIndex % 12}")
            appendLine(
                "  graticule ${if (caseIndex % 2 == 0) "circle" else "polygon"}",
            )
            appendLine("  showLegend ${caseIndex % 4 != 0}")
        }
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            "Expected randomized Radar render success:\n$source",
        ).value

    private fun validateScene(
        scene: MermaidScene,
        source: String,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, source)
        assertTrue(scene.elements.filterIsInstance<ScenePath>().isNotEmpty(), source)
        assertTrue(scene.elements.filterIsInstance<SceneText>().isNotEmpty(), source)
        scene.elements.forEach { element ->
            when (element) {
                is SceneAsset -> assertValid(element.bounds, source)
                is SceneShape -> assertValid(element.bounds, source)
                is SceneText -> assertValid(element.bounds, source)
                is ScenePath -> {
                    assertTrue(
                        element.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                        source,
                    )
                    assertTrue(
                        element.commands.all { command ->
                            when (command) {
                                is com.swithun.cmpmermaid.core.ScenePathCommand.MoveTo ->
                                    command.point.x.isFinite() && command.point.y.isFinite()
                                is com.swithun.cmpmermaid.core.ScenePathCommand.LineTo ->
                                    command.point.x.isFinite() && command.point.y.isFinite()
                                is com.swithun.cmpmermaid.core.ScenePathCommand.QuadraticTo ->
                                    command.control.x.isFinite() &&
                                        command.control.y.isFinite() &&
                                        command.end.x.isFinite() &&
                                        command.end.y.isFinite()
                                is com.swithun.cmpmermaid.core.ScenePathCommand.CubicTo ->
                                    command.control1.x.isFinite() &&
                                        command.control1.y.isFinite() &&
                                        command.control2.x.isFinite() &&
                                        command.control2.y.isFinite() &&
                                        command.end.x.isFinite() &&
                                        command.end.y.isFinite()
                                is com.swithun.cmpmermaid.core.ScenePathCommand.ArcTo ->
                                    command.radius.isFinite() &&
                                        command.end.x.isFinite() &&
                                        command.end.y.isFinite()
                            }
                        },
                        source,
                    )
                }
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
