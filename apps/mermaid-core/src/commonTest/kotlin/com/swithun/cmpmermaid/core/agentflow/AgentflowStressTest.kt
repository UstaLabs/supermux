package com.swithun.cmpmermaid.core.agentflow

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
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentflowStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / (request.fontSize * 0.55f))
                .toInt()
                .coerceAtLeast(1)
            val lines = request.text.lineSequence().sumOf { line ->
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
                height = lines * request.fontSize * request.lineHeight,
            )
        },
        options = MermaidRenderOptions(layout = "dagre"),
    )

    @Test
    fun renders256IndependentlyGeneratedSourcesDeterministically() {
        val random = Random(12_00_00)
        repeat(CASE_COUNT) { caseIndex ->
            val source = generateSource(random, caseIndex)
            val first = render(source, caseIndex)
            val second = render(source, caseIndex)

            assertEquals(
                expected = first,
                actual = second,
                message = "Agentflow case $caseIndex produced a non-deterministic SceneGraph",
            )
            validateScene(first, source, caseIndex)
        }
    }

    private fun generateSource(
        random: Random,
        caseIndex: Int,
    ): String {
        val nodeCount = random.nextInt(from = 4, until = 10)
        val nodeIds = List(nodeCount) { index -> "c${caseIndex}n$index" }
        val hasGlobal = caseIndex % 4 == 0
        val hasConnector = caseIndex % 5 == 0
        val hasNestedFlow = caseIndex % 3 == 0
        val collapseNestedFlow = hasNestedFlow && caseIndex % 6 == 0
        val outerFlowId = "flow$caseIndex"
        val innerFlowId = "inner$caseIndex"
        val globalId = "shared$caseIndex"
        val connectorId = "connector$caseIndex"
        val externalId = "result$caseIndex"

        return buildString {
            appendLine("---")
            appendLine("config:")
            appendLine("  layout: dagre")
            appendLine("  agentflow:")
            appendLine("    nodeSpacing: ${40 + caseIndex % 5 * 8}")
            appendLine("    rankSpacing: ${45 + caseIndex % 4 * 10}")
            appendLine("---")
            appendLine(
                "agentflow-beta ${DIRECTIONS[caseIndex % DIRECTIONS.size]}",
            )
            appendLine("  %% deterministic generated Agentflow case $caseIndex")
            if (hasGlobal) {
                appendLine("  global")
                appendLine(
                    "    $globalId[\"Shared context $caseIndex\"]@{ shape: refdoc }",
                )
                appendLine("  end")
            }
            if (hasConnector) {
                appendLine("  connector $connectorId[\"Service $caseIndex\"]")
                appendLine(
                    "  $connectorId@{ protocol: \"http\", " +
                        "endpoint: \"https://example.com/$caseIndex\" }",
                )
            }
            appendLine("  flow $outerFlowId[\"Agent $caseIndex\"]")
            appendLine(
                "    ${nodeIds[0]}[\"Input $caseIndex\"]@{ shape: input, value: \"$caseIndex\" }",
            )
            if (hasNestedFlow) {
                appendLine("    flow $innerFlowId[\"Worker $caseIndex\"]")
            }
            nodeIds.drop(1).forEachIndexed { offset, id ->
                val nodeIndex = offset + 1
                val indent = if (hasNestedFlow && nodeIndex <= 2) "      " else "    "
                append(indent)
                append(id)
                append("[\"Step ")
                append(caseIndex)
                append('.')
                append(nodeIndex)
                append("\"]@{ shape: ")
                append(SHAPES[(caseIndex + nodeIndex) % SHAPES.size])
                if (hasConnector && nodeIndex == 1) {
                    append(", connectorRef: \"")
                    append(connectorId)
                    append(".run\"")
                }
                if (nodeIndex == nodeCount - 1) {
                    append(", description: \"Terminal generated step\"")
                }
                appendLine(" }")
                if (hasNestedFlow && nodeIndex == 2) {
                    appendLine("    end")
                    if (collapseNestedFlow) {
                        appendLine("    $innerFlowId@{ view: \"collapsed\" }")
                    }
                }
            }
            nodeIds.zipWithNext().forEachIndexed { edgeIndex, (from, to) ->
                append("    ")
                append(from)
                append(' ')
                append(EDGES[(caseIndex + edgeIndex) % EDGES.size])
                append(' ')
                appendLine(to)
            }
            if (hasGlobal) {
                appendLine("    ${nodeIds[1]} -.- $globalId")
            }
            if (hasConnector) {
                appendLine("    ${nodeIds[1]} -.- $connectorId")
            }
            appendLine("  end")
            appendLine(
                "  $externalId[\"Output $caseIndex\"]@{ shape: action, " +
                    "example: \"generated\" }",
            )
            appendLine("  ${nodeIds.last()} --> $externalId")
            if (caseIndex % 7 == 0) {
                appendLine("  $externalId --x $outerFlowId")
            }
        }.trimEnd()
    }

    private fun render(
        source: String,
        caseIndex: Int,
    ): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Agentflow case $caseIndex failed with ${(result as? GMResult.Err)?.error}:\n$source",
        ).value
    }

    private fun validateScene(
        scene: MermaidScene,
        source: String,
        caseIndex: Int,
    ) {
        val message = "Agentflow case $caseIndex:\n$source"
        assertTrue(scene.width.isFinite() && scene.width > 0f, message)
        assertTrue(scene.height.isFinite() && scene.height > 0f, message)
        assertTrue(scene.elements.filterIsInstance<SceneShape>().isNotEmpty(), message)
        assertTrue(scene.elements.filterIsInstance<ScenePath>().isNotEmpty(), message)
        assertTrue(scene.elements.filterIsInstance<SceneText>().isNotEmpty(), message)
        scene.elements.forEach { element ->
            when (element) {
                is SceneAsset -> assertFinite(element.bounds, message)
                is SceneShape -> assertFinite(element.bounds, message)
                is SceneText -> assertFinite(element.bounds, message)
                is ScenePath -> {
                    assertTrue(element.points.size >= 2, message)
                    assertTrue(
                        element.points.all { point ->
                            point.x.isFinite() && point.y.isFinite()
                        },
                        "$message\n${element.id}: ${element.points}",
                    )
                }
            }
        }
    }

    private fun assertFinite(
        bounds: SceneRect,
        message: String,
    ) {
        assertTrue(bounds.left.isFinite(), message)
        assertTrue(bounds.top.isFinite(), message)
        assertTrue(bounds.right.isFinite(), message)
        assertTrue(bounds.bottom.isFinite(), message)
        assertTrue(bounds.width >= 0f && bounds.height >= 0f, message)
    }

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        private const val CASE_COUNT = 256

        private val DIRECTIONS = listOf("TB", "LR", "BT", "RL", "TD")
        private val SHAPES = listOf("task", "tool", "decision", "refdoc", "action")
        private val EDGES = listOf("-->", "-->", "-.-", "--x")
    }
}
