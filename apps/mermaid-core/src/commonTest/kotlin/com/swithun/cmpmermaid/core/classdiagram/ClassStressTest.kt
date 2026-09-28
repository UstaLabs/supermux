package com.swithun.cmpmermaid.core.classdiagram

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

class ClassStressTest {
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
    fun rendersMermaid12OfficialRelationshipMatrix() {
        val source = """
            classDiagram
                Class01 "1" <|--|> "*" AveryLongClass : Cool
                <<interface>> Class01
                Class03 "1" *-- "*" Class04
                Class05 "1" o-- "many" Class06
                Class07 "1" .. "*" Class08
                Class09 "1" --> "*" C2 : Where am i?
                Class09 "*" --* "*" C3
                Class09 "1" --|> "1" Class07
                Class07 : equals()
                Class07 : Object[] elementData
                Class01 : size()
                Class01 : int chimp
                Class01 : int gorilla
                Class08 "1" <--> "*" C2 : Cool label
                class Class10 {
                    <<service>>
                    int id
                    test()
                }
        """.trimIndent()

        validateScene(
            caseName = "official-relationship-matrix-dagre",
            source = source,
            scene = render(source, "dagre"),
            minimumClasses = 12,
            minimumRelations = 8,
        )
    }

    @Test
    fun rendersDeterministicRandomizedCorpusWithDagre() {
        val random = Random(12_00_00)
        repeat(RANDOM_CASE_COUNT) { index ->
            val source = randomClassDiagram(random, index)
            validateScene(
                caseName = "random-$index-dagre",
                source = source,
                scene = render(source, "dagre"),
                minimumClasses = 2,
                minimumRelations = 1,
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
                    classLayout = layout,
                ),
            ),
        )
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected Class render success for $layout:\n$source\n$result",
        ).value
    }

    private fun validateScene(
        caseName: String,
        source: String,
        scene: MermaidScene,
        minimumClasses: Int,
        minimumRelations: Int,
    ) {
        val failureContext = "$caseName:\n$source"
        assertTrue(scene.width.isFinite() && scene.width > 0f, failureContext)
        assertTrue(scene.height.isFinite() && scene.height > 0f, failureContext)
        assertTrue(
            scene.elements.filterIsInstance<SceneShape>()
                .count { shape -> !shape.id.startsWith("namespace-") } >= minimumClasses,
            failureContext,
        )
        assertTrue(
            scene.elements.filterIsInstance<ScenePath>().size >= minimumRelations,
            failureContext,
        )
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null) {
                assertValid(bounds, failureContext)
            }
            if (element is ScenePath) {
                assertTrue(element.points.size >= 2, failureContext)
                assertTrue(
                    element.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                    failureContext,
                )
            }
        }
    }

    private fun assertValid(
        bounds: SceneRect,
        failureContext: String,
    ) {
        assertTrue(bounds.left.isFinite(), failureContext)
        assertTrue(bounds.top.isFinite(), failureContext)
        assertTrue(bounds.right.isFinite(), failureContext)
        assertTrue(bounds.bottom.isFinite(), failureContext)
        assertTrue(bounds.width >= 0f, failureContext)
        assertTrue(bounds.height >= 0f, failureContext)
    }

    private fun randomClassDiagram(
        random: Random,
        caseIndex: Int,
    ): String {
        val classCount = random.nextInt(from = 2, until = 11)
        val relationCount = random.nextInt(from = classCount - 1, until = classCount * 2)
        val classNames = List(classCount) { index -> "Case${caseIndex}Node$index" }
        return buildString {
            appendLine("classDiagram")
            appendLine("    direction ${DIRECTIONS.random(random)}")
            classNames.forEachIndexed { index, className ->
                when (random.nextInt(4)) {
                    0 -> appendLine("    class $className")
                    1 -> appendLine("    class $className~T$index~")
                    2 -> {
                        appendLine("    class $className {")
                        appendLine("        +String value$index")
                        appendLine("        -load$index(id) Result~T$index~")
                        appendLine("    }")
                    }
                    else -> appendLine("""    class $className["Node $caseIndex.$index"]""")
                }
            }
            repeat(relationCount) { relationIndex ->
                val start = classNames.random(random)
                val end = if (relationIndex % 7 == 0) {
                    start
                } else {
                    classNames.filterNot(start::equals).random(random)
                }
                val leftCardinality = CARDINALITIES.random(random)
                val rightCardinality = CARDINALITIES.random(random)
                append("    ")
                append(start)
                if (leftCardinality != null) {
                    append(" \"")
                    append(leftCardinality)
                    append('"')
                }
                append(' ')
                append(RELATIONS.random(random))
                if (rightCardinality != null) {
                    append(" \"")
                    append(rightCardinality)
                    append('"')
                }
                append(' ')
                append(end)
                if (relationIndex % 3 != 0) {
                    append(" : relation")
                    append(relationIndex)
                }
                appendLine()
            }
            if (caseIndex % 4 == 0) {
                appendLine("""    note for ${classNames.first()} "case $caseIndex<br/>note"""")
            }
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        const val RANDOM_CASE_COUNT = 256

        val DIRECTIONS = listOf("TB", "BT", "LR", "RL")
        val CARDINALITIES = listOf(null, "1", "0..1", "0..*", "1..*", "many")
        val RELATIONS = listOf(
            "--",
            "..",
            "-->",
            "<--",
            "<-->",
            "<|--",
            "--|>",
            "<|--|>",
            "<|..",
            "..|>",
            "o--",
            "--o",
            "o--o",
            "*--",
            "--*",
            "*--*",
            "o..",
            "..o",
            "*..",
            "..*",
        )
    }
}
