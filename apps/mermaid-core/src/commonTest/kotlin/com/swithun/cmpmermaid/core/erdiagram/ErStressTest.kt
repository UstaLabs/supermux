package com.swithun.cmpmermaid.core.erdiagram

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
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

class ErStressTest {
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
            val generated = randomErDiagram(random, index)
            validateScene(
                caseName = "random-$index-dagre",
                source = generated.source,
                expectedEntities = generated.entityCount,
                expectedRelationships = generated.relationshipCount,
                scene = render(generated.source, "dagre"),
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
                    layout = layout,
                ),
            ),
        )
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected ER render success for $layout:\n$source\n$result",
        ).value
    }

    private fun validateScene(
        caseName: String,
        source: String,
        expectedEntities: Int,
        expectedRelationships: Int,
        scene: MermaidScene,
    ) {
        val context = "$caseName:\n$source"
        val entityId = Regex("""entity-.*-\d+""")
        val entityShapes = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id.matches(entityId) }
        val relationshipPaths = scene.elements.filterIsInstance<ScenePath>()

        assertTrue(scene.width.isFinite() && scene.width > 0f, context)
        assertTrue(scene.height.isFinite() && scene.height > 0f, context)
        assertTrue(entityShapes.size >= expectedEntities, context)
        assertTrue(relationshipPaths.size == expectedRelationships, context)
        assertTrue(
            relationshipPaths.all { path ->
                path.arrowStart in ER_MARKERS && path.arrowEnd in ER_MARKERS
            },
            context,
        )
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

    private fun randomErDiagram(
        random: Random,
        caseIndex: Int,
    ): GeneratedErCase {
        val entityCount = random.nextInt(from = 3, until = 10)
        val relationshipCount = random.nextInt(
            from = entityCount - 1,
            until = entityCount * 2,
        )
        val entities = List(entityCount) { index -> "Case${caseIndex}Entity$index" }
        return GeneratedErCase(
            entityCount = entityCount,
            relationshipCount = relationshipCount,
            source = buildString {
                appendLine("erDiagram")
                appendLine("    direction ${DIRECTIONS.random(random)}")
                if (caseIndex % 4 == 0) {
                    appendLine("    classDef highlighted fill:#dbeafe,stroke:#2563eb")
                }
                entities.forEachIndexed { index, entity ->
                    val alias = if (index % 3 == 0) """["Entity $caseIndex.$index"]""" else ""
                    val style = if (caseIndex % 4 == 0 && index == 0) ":::highlighted" else ""
                    appendLine("    $entity$alias$style {")
                    appendLine("        int id PK")
                    appendLine("        string value$index")
                    if (random.nextBoolean()) {
                        appendLine("""        string? note$index UK "Optional note $index"""")
                    }
                    if (random.nextInt(3) == 0) {
                        appendLine("        decimal amount$index FK")
                    }
                    appendLine("    }")
                }
                repeat(relationshipCount) { relationshipIndex ->
                    val start = entities.random(random)
                    val end = if (relationshipIndex % 7 == 0) {
                        start
                    } else {
                        entities.filterNot(start::equals).random(random)
                    }
                    appendLine(
                        "    $start ${RELATIONSHIPS.random(random)} $end : " +
                            "\"relation $relationshipIndex\"",
                    )
                }
            },
        )
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

    private data class GeneratedErCase(
        val source: String,
        val entityCount: Int,
        val relationshipCount: Int,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256

        val DIRECTIONS = listOf("TB", "BT", "LR", "RL")
        val RELATIONSHIPS = listOf(
            "||--||",
            "||--o|",
            "||--|{",
            "||--o{",
            "o|..||",
            "|{..o|",
            "o{..||",
            "u--o{",
        )
        val ER_MARKERS = setOf(
            SceneArrowHead.None,
            SceneArrowHead.ErOnlyOne,
            SceneArrowHead.ErZeroOrOne,
            SceneArrowHead.ErOneOrMore,
            SceneArrowHead.ErZeroOrMore,
        )
    }
}
