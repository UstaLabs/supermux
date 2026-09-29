package com.swithun.cmpmermaid.core.gitgraph

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneElement
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
import kotlin.test.assertTrue

class GitGraphStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.lines()
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    lines.maxOfOrNull(String::length).orZero() * request.fontSize * 0.55f,
                ),
                height = lines.size * request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersDeterministicRandomizedCorpus() {
        val random = Random(12_00_11)
        val sources = buildSet {
            repeat(RANDOM_CASE_COUNT) { caseIndex ->
                val generated = randomGitGraph(random, caseIndex)
                assertTrue(add(generated.source), "Duplicate source for case $caseIndex")

                val first = render(generated.source)
                validateScene(generated, first)
                assertEquals(
                    first,
                    render(generated.source),
                    "Non-deterministic Git Graph scene:\n${generated.source}",
                )
            }
        }

        assertEquals(RANDOM_CASE_COUNT, sources.size)
    }

    private fun randomGitGraph(
        random: Random,
        caseIndex: Int,
    ): GeneratedGitGraphCase {
        val direction = DIRECTIONS[caseIndex.mod(DIRECTIONS.size)]
        val mainBranch = if (caseIndex % 4 == 0) "trunk-$caseIndex" else "main"
        val branchCount = random.nextInt(from = 1, until = 5)
        val showBranches = caseIndex % 5 != 0
        val showCommitLabels = caseIndex % 7 != 0
        val rotateCommitLabels = caseIndex % 2 == 0
        val parallelCommits = caseIndex % 3 != 0
        val branchNames = mutableListOf<String>()
        val commitIds = mutableListOf<String>()
        var expectedArrowCount = 0

        val source = buildString {
            appendLine("---")
            appendLine("title: Git graph stress $caseIndex")
            appendLine("config:")
            appendLine("  gitGraph:")
            appendLine("    mainBranchName: \"$mainBranch\"")
            appendLine("    mainBranchOrder: ${branchCount + 1}")
            appendLine("    showBranches: $showBranches")
            appendLine("    showCommitLabel: $showCommitLabels")
            appendLine("    rotateCommitLabel: $rotateCommitLabels")
            appendLine("    parallelCommits: $parallelCommits")
            appendLine("---")
            appendLine("gitGraph $direction:")
            appendLine("  accTitle: Git Graph stress case $caseIndex")
            appendLine("  accDescr: Deterministic randomized Git Graph diagram")
            appendLine("  %% Exercise public Git Graph statements with valid state transitions.")
            appendLine(
                "  commit id: \"root-$caseIndex\" msg: \"Initialize graph $caseIndex\" " +
                    "tag: \"baseline-$caseIndex\"",
            )
            commitIds += "root-$caseIndex"

            repeat(branchCount) { branchIndex ->
                val branchName = if ((caseIndex + branchIndex) % 2 == 0) {
                    "release lane $caseIndex $branchIndex"
                } else {
                    "feature-$caseIndex-$branchIndex"
                }
                val sourceCommitId = "branch-$caseIndex-$branchIndex-0"
                branchNames += branchName
                appendLine(
                    "  branch ${branchName.quoted()} order: ${branchIndex + 1}",
                )
                val branchCommitCount = random.nextInt(from = 1, until = 5)
                repeat(branchCommitCount) { commitIndex ->
                    val commitId = "branch-$caseIndex-$branchIndex-$commitIndex"
                    val type = COMMIT_TYPES[
                        (caseIndex + branchIndex + commitIndex).mod(COMMIT_TYPES.size)
                    ]
                    append("  commit id: \"$commitId\"")
                    append(" msg: \"Validate stage $branchIndex.$commitIndex\"")
                    append(" type: $type")
                    if ((caseIndex + branchIndex + commitIndex) % 3 == 0) {
                        append(" tag: \"candidate-$branchIndex-$commitIndex\"")
                    }
                    if ((caseIndex + branchIndex + commitIndex) % 11 == 0) {
                        append(" tag: \"verified-$caseIndex\"")
                    }
                    appendLine()
                    commitIds += commitId
                    expectedArrowCount += 1
                }

                appendLine(
                    "  ${if (branchIndex % 2 == 0) "checkout" else "switch"} " +
                        mainBranch.quoted(),
                )
                val mainCommitId = "main-$caseIndex-$branchIndex"
                appendLine("  commit id: \"$mainCommitId\" type: NORMAL")
                commitIds += mainCommitId
                expectedArrowCount += 1

                if (branchIndex == 0 && caseIndex % 3 == 0) {
                    appendLine(
                        "  cherry-pick id: \"$sourceCommitId\" tag: \"picked-$caseIndex\"",
                    )
                    expectedArrowCount += 2
                }

                val mergeId = "merge-$caseIndex-$branchIndex"
                val mergeType = COMMIT_TYPES[
                    (caseIndex + branchIndex + 1).mod(COMMIT_TYPES.size)
                ]
                appendLine(
                    "  merge ${branchName.quoted()} id: \"$mergeId\" " +
                        "tag: \"merged-$branchIndex\" type: $mergeType",
                )
                commitIds += mergeId
                expectedArrowCount += 2
            }
        }

        return GeneratedGitGraphCase(
            source = source,
            branchNames = branchNames,
            commitIds = commitIds,
            expectedArrowCount = expectedArrowCount,
            expectedBranchCount = branchCount + 1,
            showBranches = showBranches,
            showCommitLabels = showCommitLabels,
        )
    }

    private fun validateScene(
        generated: GeneratedGitGraphCase,
        scene: MermaidScene,
    ) {
        val contextMessage = generated.source
        val paths = scene.elements.filterIsInstance<ScenePath>()
        val texts = scene.elements.filterIsInstance<SceneText>()
        val elementIds = scene.elements.mapNotNullTo(mutableSetOf()) { element ->
            when (element) {
                is SceneAsset -> element.id
                is SceneShape -> element.id
                is ScenePath -> element.id
                is SceneText -> null
            }
        }

        assertTrue(scene.width.isFinite() && scene.width > 0f, contextMessage)
        assertTrue(scene.height.isFinite() && scene.height > 0f, contextMessage)
        assertEquals(
            generated.expectedArrowCount,
            paths.count { path -> path.id.startsWith("git-arrow-") },
            contextMessage,
        )
        assertEquals(
            if (generated.showBranches) generated.expectedBranchCount else 0,
            paths.count { path -> path.id.startsWith("git-branch-") },
            contextMessage,
        )
        generated.commitIds.forEach { commitId ->
            assertTrue(
                elementIds.any { id -> id.startsWith("git-commit-$commitId") },
                "Missing commit '$commitId':\n$contextMessage",
            )
        }
        generated.branchNames.forEach { branchName ->
            assertEquals(
                generated.showBranches,
                texts.any { text -> text.text == branchName },
                "Unexpected branch label '$branchName':\n$contextMessage",
            )
        }
        if (!generated.showCommitLabels) {
            assertTrue(
                texts.none { text -> text.text in generated.commitIds },
                "Commit labels should be hidden:\n$contextMessage",
            )
        }
        scene.elements.forEach { element -> assertFiniteGeometry(element, contextMessage) }
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected randomized Git Graph render success:\n$source\n$result",
        ).value
    }

    private fun assertFiniteGeometry(
        element: SceneElement,
        contextMessage: String,
    ) {
        when (element) {
            is SceneAsset -> assertValid(element.bounds, contextMessage)
            is SceneShape -> {
                assertValid(element.bounds, contextMessage)
                element.geometry?.paths.orEmpty().forEach { path ->
                    path.points.forEach { point ->
                        assertTrue(point.x.isFinite() && point.y.isFinite(), contextMessage)
                    }
                }
            }
            is SceneText -> assertValid(element.bounds, contextMessage)
            is ScenePath -> {
                assertTrue(element.strokeWidth.isFinite(), contextMessage)
                element.points.forEach { point ->
                    assertTrue(point.x.isFinite() && point.y.isFinite(), contextMessage)
                }
                element.commands.forEach { command ->
                    val points = when (command) {
                        is ScenePathCommand.MoveTo -> listOf(command.point)
                        is ScenePathCommand.LineTo -> listOf(command.point)
                        is ScenePathCommand.QuadraticTo ->
                            listOf(command.control, command.end)
                        is ScenePathCommand.CubicTo ->
                            listOf(command.control1, command.control2, command.end)
                        is ScenePathCommand.ArcTo -> listOf(command.end)
                    }
                    points.forEach { point ->
                        assertTrue(point.x.isFinite() && point.y.isFinite(), contextMessage)
                    }
                }
            }
        }
    }

    private fun assertValid(
        bounds: SceneRect,
        contextMessage: String,
    ) {
        assertTrue(bounds.left.isFinite(), contextMessage)
        assertTrue(bounds.top.isFinite(), contextMessage)
        assertTrue(bounds.right.isFinite(), contextMessage)
        assertTrue(bounds.bottom.isFinite(), contextMessage)
        assertTrue(bounds.width >= 0f, contextMessage)
        assertTrue(bounds.height >= 0f, contextMessage)
    }

    private fun String.quoted(): String = "\"$this\""

    private fun Int?.orZero(): Int = this ?: 0

    private data class GeneratedGitGraphCase(
        val source: String,
        val branchNames: List<String>,
        val commitIds: List<String>,
        val expectedArrowCount: Int,
        val expectedBranchCount: Int,
        val showBranches: Boolean,
        val showCommitLabels: Boolean,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256

        val DIRECTIONS = listOf("LR", "TB", "BT")
        val COMMIT_TYPES = listOf("NORMAL", "REVERSE", "HIGHLIGHT")
    }
}
