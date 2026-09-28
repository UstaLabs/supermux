package com.swithun.cmpmermaid.core.flowchart

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
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlowchartStressTest {
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
            width = min(
                request.maxWidth,
                request.text.lineSequence().maxOfOrNull(String::length).orZero() * 8f,
            ),
            height = lines * request.fontSize * request.lineHeight,
        )
    }

    @Test
    fun drawsParentSubgraphsBehindNestedSubgraphs() {
        val generated = GeneratedFlowchart(
            source = """
                flowchart LR
                  subgraph outer [Outer LR]
                    direction LR
                    subgraph top [Top TB]
                      direction TB
                      A --> B --> C
                    end
                    subgraph bottom [Bottom RL]
                      direction RL
                      D --> E --> F
                    end
                    C --> D
                  end
                  Start --> A
                  F --> Finish
            """.trimIndent(),
            nodeIds = setOf("A", "B", "C", "D", "E", "F", "Start", "Finish"),
            edges = listOf(
                GeneratedEdge("A", "B", "-->"),
                GeneratedEdge("B", "C", "-->"),
                GeneratedEdge("D", "E", "-->"),
                GeneratedEdge("E", "F", "-->"),
                GeneratedEdge("C", "D", "-->"),
                GeneratedEdge("Start", "A", "-->"),
                GeneratedEdge("F", "Finish", "-->"),
            ),
            expectedSubgraphs = 3,
        )
        val scene = render(generated, layout = "dagre", curve = "basis")
        val subgraphs = scene.elements
            .filterIsInstance<SceneShape>()
            .filter { shape -> shape.id.startsWith("subgraph_") }
            .associateBy(SceneShape::id)
        val outer = subgraphs.getValue("subgraph_outer")

        assertTrue(outer.zIndex < subgraphs.getValue("subgraph_top").zIndex)
        assertTrue(outer.zIndex < subgraphs.getValue("subgraph_bottom").zIndex)
    }

    @Test
    fun rendersDeterministicRandomizedCorpusWithDagre() {
        val random = Random(12_00_00)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val generated = randomFlowchart(random, caseIndex)
            LAYOUTS.forEach { layout ->
                val scene = render(
                    generated = generated,
                    layout = layout,
                    curve = CURVES[(caseIndex + layout.length) % CURVES.size],
                )
                validateScene(
                    caseName = "random-$caseIndex-$layout",
                    generated = generated,
                    scene = scene,
                )
            }
        }
    }

    private fun randomFlowchart(
        random: Random,
        caseIndex: Int,
    ): GeneratedFlowchart {
        val nodeCount = random.nextInt(from = 3, until = 11)
        val nodeIds = List(nodeCount) { index -> "C${caseIndex}N$index" }
        val edges = buildList {
            nodeIds.zipWithNext().forEachIndexed { index, (from, to) ->
                add(GeneratedEdge(from, to, linkFor(caseIndex + index)))
            }
            repeat(random.nextInt(from = 1, until = nodeCount + 1)) { edgeIndex ->
                val from = nodeIds.random(random)
                val to = when {
                    edgeIndex == 0 && caseIndex % 6 == 0 -> from
                    edgeIndex == 1 && caseIndex % 5 == 0 -> nodeIds.first()
                    else -> nodeIds.random(random)
                }
                add(GeneratedEdge(from, to, linkFor(caseIndex + nodeCount + edgeIndex)))
            }
        }
        val groupedNodeCount = if (caseIndex % 4 == 0) {
            nodeCount / 2
        } else {
            0
        }
        val source = buildString {
            appendLine("flowchart ${DIRECTIONS[caseIndex % DIRECTIONS.size]}")
            if (groupedNodeCount > 0) {
                appendLine("  subgraph G$caseIndex [Group $caseIndex]")
                appendLine("    direction ${DIRECTIONS[(caseIndex + 1) % DIRECTIONS.size]}")
            }
            nodeIds.forEachIndexed { index, id ->
                if (groupedNodeCount > 0 && index == groupedNodeCount) {
                    appendLine("  end")
                }
                val indent = if (groupedNodeCount > 0 && index < groupedNodeCount) {
                    "    "
                } else {
                    "  "
                }
                append(indent)
                append(id)
                append("@{ shape: ")
                append(SHAPES[(caseIndex + index) % SHAPES.size])
                append(", label: \"Case ")
                append(caseIndex)
                append(" node ")
                append(index)
                appendLine("\" }")
            }
            edges.forEachIndexed { index, edge ->
                append("  ")
                append(edge.from)
                append(' ')
                if (index % 4 == 0 && edge.link == "-->") {
                    append("-->|route ")
                    append(index)
                    append("|")
                } else {
                    append(edge.link)
                }
                append(' ')
                appendLine(edge.to)
            }
            if (caseIndex % 5 == 0) {
                appendLine(
                    "  classDef accent fill:#dbeafe,stroke:#2563eb,color:#172554," +
                        "stroke-width:2px",
                )
                appendLine("  class ${nodeIds.first()},${nodeIds.last()} accent")
            }
            if (caseIndex % 7 == 0) {
                appendLine("  linkStyle 0 stroke:#dc2626,stroke-width:3px,color:#991b1b")
            }
        }
        return GeneratedFlowchart(
            source = source,
            nodeIds = nodeIds.toSet(),
            edges = edges,
            expectedSubgraphs = if (groupedNodeCount > 0) 1 else 0,
        )
    }

    private fun render(
        generated: GeneratedFlowchart,
        layout: String,
        curve: String,
    ): MermaidScene {
        val result = engine.render(
            source = generated.source,
            context = MermaidRenderContext(
                textMetrics = textMetrics,
                options = MermaidRenderOptions(
                    layout = layout,
                    curve = curve,
                    look = "classic",
                ),
            ),
        )
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected Flowchart render success for $layout:\n${generated.source}\n$result",
        ).value
    }

    private fun validateScene(
        caseName: String,
        generated: GeneratedFlowchart,
        scene: MermaidScene,
    ) {
        val failureContext = "$caseName:\n${generated.source}"
        assertTrue(scene.width.isFinite() && scene.width > 0f, failureContext)
        assertTrue(scene.height.isFinite() && scene.height > 0f, failureContext)

        val nodes = scene.elements
            .filterIsInstance<SceneShape>()
            .filter { shape -> shape.id in generated.nodeIds }
        val paths = scene.elements.filterIsInstance<ScenePath>()
        assertEquals(generated.nodeIds, nodes.mapTo(linkedSetOf(), SceneShape::id), failureContext)
        assertEquals(generated.edges.size, paths.size, failureContext)
        assertEquals(
            generated.expectedSubgraphs,
            scene.elements.filterIsInstance<SceneShape>()
                .count { shape -> shape.id.startsWith("subgraph_") },
            failureContext,
        )

        scene.elements.forEach { element ->
            when (element) {
                is SceneAsset -> assertValid(element.bounds, scene, failureContext)
                is SceneShape -> assertValid(element.bounds, scene, failureContext)
                is SceneText -> assertValid(element.bounds, scene, failureContext)
                is ScenePath -> {
                    assertTrue(element.points.size >= 2, failureContext)
                    assertTrue(
                        element.points.all { point ->
                            point.x.isFinite() &&
                                point.y.isFinite() &&
                                point.x in 0f..scene.width &&
                                point.y in 0f..scene.height
                        },
                        "$failureContext\n${element.id}: ${element.points}",
                    )
                }
            }
        }

        nodes.forEachIndexed { index, first ->
            nodes.drop(index + 1).forEach { second ->
                assertTrue(
                    !first.bounds.overlapsInterior(second.bounds),
                    "$failureContext\n${first.id} overlaps ${second.id}: " +
                        "${first.bounds} vs ${second.bounds}",
                )
            }
        }
    }

    private fun assertValid(
        bounds: SceneRect,
        scene: MermaidScene,
        failureContext: String,
    ) {
        assertTrue(bounds.left.isFinite(), failureContext)
        assertTrue(bounds.top.isFinite(), failureContext)
        assertTrue(bounds.right.isFinite(), failureContext)
        assertTrue(bounds.bottom.isFinite(), failureContext)
        assertTrue(bounds.width >= 0f && bounds.height >= 0f, failureContext)
        assertTrue(bounds.left >= 0f && bounds.top >= 0f, failureContext)
        assertTrue(bounds.right <= scene.width && bounds.bottom <= scene.height, failureContext)
    }

    private fun SceneRect.overlapsInterior(other: SceneRect): Boolean =
        max(left, other.left) < min(right, other.right) - INTERSECTION_EPSILON &&
            max(top, other.top) < min(bottom, other.bottom) - INTERSECTION_EPSILON

    private fun linkFor(index: Int): String = LINKS[index % LINKS.size]

    private fun Int?.orZero(): Int = this ?: 0

    private data class GeneratedFlowchart(
        val source: String,
        val nodeIds: Set<String>,
        val edges: List<GeneratedEdge>,
        val expectedSubgraphs: Int,
    )

    private data class GeneratedEdge(
        val from: String,
        val to: String,
        val link: String,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        const val INTERSECTION_EPSILON = 0.5f

        val LAYOUTS = listOf("dagre")
        val DIRECTIONS = listOf("TB", "BT", "LR", "RL")
        val CURVES = listOf("basis", "linear", "step", "cardinal")
        val SHAPES = listOf(
            "rect",
            "rounded",
            "stadium",
            "subproc",
            "cyl",
            "circle",
            "dbl-circ",
            "diamond",
            "hex",
            "doc",
            "notch-rect",
            "lin-rect",
        )
        val LINKS = listOf(
            "-->",
            "---",
            "-.->",
            "==>",
            "--o",
            "--x",
            "<-->",
            "o--o",
            "x--x",
            "o==o",
            "x-.-x",
            "<-.->",
        )
    }
}
