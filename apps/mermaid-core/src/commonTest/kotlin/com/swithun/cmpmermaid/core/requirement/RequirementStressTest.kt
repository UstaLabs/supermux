package com.swithun.cmpmermaid.core.requirement

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneArrowHead
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

class RequirementStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * request.fontSize * 0.52f),
                height = request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersDeterministicRandomizedCorpus() {
        val random = Random(12_00_10)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val generated = randomRequirementDiagram(random, caseIndex)
            val scene = render(generated.source)
            validateScene(generated, scene)
        }
    }

    private fun randomRequirementDiagram(
        random: Random,
        caseIndex: Int,
    ): GeneratedRequirementCase {
        val requirementCount = random.nextInt(from = 2, until = 10)
        val elementCount = random.nextInt(from = 1, until = 6)
        val requirementNames = List(requirementCount) { index -> "req_${caseIndex}_$index" }
        val elementNames = List(elementCount) { index -> "element_${caseIndex}_$index" }
        val nodeNames = requirementNames + elementNames
        val relationCount = random.nextInt(
            from = 1,
            until = minOf(MAX_RELATION_COUNT, nodeNames.size * 2) + 1,
        )
        var containsCount = 0
        val source = buildString {
            if (caseIndex % 5 == 0) {
                appendLine("---")
                appendLine("title: Random requirement model $caseIndex")
                appendLine("config:")
                appendLine("  layout: dagre")
                appendLine("---")
            }
            appendLine("requirementDiagram")
            appendLine("  direction ${DIRECTIONS[caseIndex.mod(DIRECTIONS.size)]}")
            appendLine("  accTitle: Requirement stress case $caseIndex")
            appendLine("  accDescr: Deterministic randomized requirement diagram")
            requirementNames.forEachIndexed { index, name ->
                val type = REQUIREMENT_TYPES[(caseIndex + index).mod(REQUIREMENT_TYPES.size)]
                appendLine("  $type $name {")
                appendLine("    id: \"REQ-$caseIndex-$index\"")
                appendLine("    text: \"Capability $caseIndex.$index with traceable evidence\"")
                appendLine("    risk: ${RISKS[(caseIndex + index).mod(RISKS.size)]}")
                appendLine(
                    "    verifyMethod: " +
                        VERIFY_METHODS[(caseIndex * 3 + index).mod(VERIFY_METHODS.size)],
                )
                appendLine("  }")
            }
            elementNames.forEachIndexed { index, name ->
                appendLine("  element $name {")
                appendLine("    type: \"Component ${index + 1}\"")
                appendLine("    docRef: \"docs/case-$caseIndex/component-${index + 1}\"")
                appendLine("  }")
            }
            if (caseIndex % 3 == 0) {
                appendLine(
                    "  classDef critical fill:#fee2e2,stroke:#b91c1c," +
                        "color:#7f1d1d,stroke-width:2px",
                )
                appendLine("  class ${requirementNames.first()},${elementNames.first()} critical")
            }
            if (caseIndex % 4 == 0) {
                appendLine(
                    "  style ${requirementNames.last()} " +
                        "fill:#dcfce7,stroke:#15803d,color:#14532d",
                )
            }
            repeat(relationCount) { relationIndex ->
                val sourceIndex = random.nextInt(nodeNames.size)
                var destinationIndex = random.nextInt(nodeNames.size)
                if (destinationIndex == sourceIndex) {
                    destinationIndex = (destinationIndex + 1).mod(nodeNames.size)
                }
                val sourceName = nodeNames[sourceIndex]
                val destinationName = nodeNames[destinationIndex]
                val relationship =
                    RELATIONSHIPS[(caseIndex + relationIndex).mod(RELATIONSHIPS.size)]
                if (relationship == "contains") {
                    containsCount += 1
                }
                if ((caseIndex + relationIndex) % 2 == 0) {
                    appendLine("  $sourceName - $relationship -> $destinationName")
                } else {
                    appendLine("  $destinationName <- $relationship - $sourceName")
                }
            }
        }
        return GeneratedRequirementCase(
            source = source,
            nodeNames = nodeNames.toSet(),
            relationCount = relationCount,
            containsCount = containsCount,
        )
    }

    private fun validateScene(
        generated: GeneratedRequirementCase,
        scene: MermaidScene,
    ) {
        val contextMessage = generated.source
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val paths = scene.elements.filterIsInstance<ScenePath>()
        val nodeShapes = shapes.filter { shape -> shape.id in generated.nodeNames }
        val dividers = paths.filter { path -> path.id.endsWith("-divider") }
        val relationships = paths.filterNot { path -> path.id.endsWith("-divider") }

        assertTrue(scene.width.isFinite() && scene.width > 0f, contextMessage)
        assertTrue(scene.height.isFinite() && scene.height > 0f, contextMessage)
        assertEquals(generated.nodeNames.size, nodeShapes.size, contextMessage)
        assertEquals(generated.nodeNames.size, dividers.size, contextMessage)
        assertEquals(generated.relationCount, relationships.size, contextMessage)
        assertEquals(
            generated.containsCount,
            relationships.count { path ->
                path.arrowStart == SceneArrowHead.RequirementContains
            },
            contextMessage,
        )
        assertEquals(
            generated.relationCount - generated.containsCount,
            relationships.count { path ->
                path.arrowEnd == SceneArrowHead.RequirementArrow
            },
            contextMessage,
        )
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null) {
                assertValid(bounds, contextMessage)
            }
            if (element is ScenePath) {
                assertTrue(
                    element.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                    contextMessage,
                )
            }
        }
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected randomized Requirement render success:\n$source\n$result",
        ).value
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

    private data class GeneratedRequirementCase(
        val source: String,
        val nodeNames: Set<String>,
        val relationCount: Int,
        val containsCount: Int,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        const val MAX_RELATION_COUNT = 20

        val DIRECTIONS = listOf("TB", "BT", "LR", "RL")
        val REQUIREMENT_TYPES = listOf(
            "requirement",
            "functionalRequirement",
            "interfaceRequirement",
            "performanceRequirement",
            "physicalRequirement",
            "designConstraint",
        )
        val RISKS = listOf("low", "medium", "high")
        val VERIFY_METHODS = listOf("analysis", "demonstration", "inspection", "test")
        val RELATIONSHIPS = listOf(
            "contains",
            "copies",
            "derives",
            "satisfies",
            "verifies",
            "refines",
            "traces",
        )
    }
}
