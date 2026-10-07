package com.swithun.cmpmermaid.core.agentflow

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.ceil
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentflowSceneTest {
    @Test
    fun classicContainersMatchAgentflowFrameStyles() {
        val scene = render(
            """
                ---
                config:
                  layout: dagre
                  theme: default
                  look: classic
                ---
                agentflow-beta TB
                  start["Start"]
                  flow expanded["Expanded"]
                    task["Task"]
                  end
                  flow collapsed["Collapsed"]
                    hidden["Hidden"]
                  end
                  collapsed@{ view: collapsed }
                  start --> expanded --> collapsed
            """.trimIndent(),
        )
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val expanded = shapes.single { shape -> shape.id == "subgraph_expanded" }
        assertEquals(com.swithun.cmpmermaid.core.SceneColor(0x00000000), expanded.fill)
        assertEquals(MermaidTheme.MermaidDefault.groupStroke, expanded.stroke)
        assertEquals(0.75f, expanded.strokeWidth)
        assertEquals(10f, expanded.cornerRadius)
        assertEquals(SceneShapeKind.RoundedRectangle, expanded.kind)

        val collapsed = shapes.single { shape ->
            shape.id == "collapsed" && shape.kind == SceneShapeKind.CollapsedGroup
        }
        val collapsedPaths = assertNotNull(collapsed.geometry).paths
        val separator = collapsedPaths[1]
        assertEquals(MermaidTheme.MermaidDefault.flowContainerStroke, separator.strokeColor)
        assertEquals(MermaidTheme.MermaidDefault.nodeStroke, collapsed.stroke)
        collapsedPaths.drop(2).forEach { dot ->
            assertEquals(SceneShapePaint.Stroke, dot.fill)
            assertEquals(SceneShapePaint.None, dot.stroke)
            assertEquals(0.5f, dot.opacity)
        }

        val intrinsic = render(
            """
                ---
                config:
                  layout: dagre
                  agentflow:
                    useMaxWidth: false
                ---
                agentflow-beta TB
                  a --> b
            """.trimIndent(),
        )
        assertEquals(MermaidSceneViewportSizing.Intrinsic, intrinsic.viewportSizing)
    }

    @Test
    fun siblingNestedFlowEdgeHasFiniteGeometry() {
        val scene = render(
            """
                ---
                config:
                  layout: dagre
                  theme: default
                  look: classic
                ---
                agentflow-beta TB
                  global
                    policy["Deployment policy"]@{ shape: refdoc }
                  end
                  flow delivery["Delivery Team"]
                    flow verifier["Verification Agent"]
                      verify["Verify evidence"]@{ shape: task }
                      verify -.- policy
                    end
                    flow approver["Approval Agent"]
                      approve["Approve release"]@{ shape: decision }
                      approve -.- policy
                    end
                    verifier --> approver
                  end
            """.trimIndent(),
        )

        val paths = scene.elements.filterIsInstance<ScenePath>()
        assertNotNull(paths.find { element -> element.id == "L_verifier_approver_0" })
        val groupBounds = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape ->
                shape.id == "subgraph_verifier" || shape.id == "subgraph_approver"
            }
            .associate { shape -> shape.id to shape.bounds }
        assertTrue(
            paths.all { path ->
                path.points.all { point -> point.x.isFinite() && point.y.isFinite() } &&
                    path.commands.all(::isFinite)
            },
            "Agentflow has non-finite paths: paths=$paths, groups=$groupBounds",
        )
    }

    private fun render(source: String): MermaidScene {
        val result = MermaidEngine().render(
            source = source,
            context = MermaidRenderContext(
                textMetrics = TextMetricProvider { request ->
                    val charactersPerLine = (request.maxWidth / (request.fontSize * 0.55f))
                        .toInt()
                        .coerceAtLeast(1)
                    val lineCount = request.text.lineSequence().sumOf { line ->
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
                        height = lineCount * request.fontSize * request.lineHeight,
                    )
                },
                options = MermaidRenderOptions(layout = "dagre"),
            ),
        )
        return assertIs<GMResult.Ok<MermaidScene>>(result).value
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun isFinite(command: ScenePathCommand): Boolean = when (command) {
        is ScenePathCommand.MoveTo -> command.point.isFinite()
        is ScenePathCommand.LineTo -> command.point.isFinite()
        is ScenePathCommand.QuadraticTo -> command.control.isFinite() && command.end.isFinite()
        is ScenePathCommand.CubicTo ->
            command.control1.isFinite() && command.control2.isFinite() && command.end.isFinite()
        is ScenePathCommand.ArcTo -> command.radius.isFinite() && command.end.isFinite()
    }

    private fun com.swithun.cmpmermaid.core.ScenePoint.isFinite(): Boolean =
        x.isFinite() && y.isFinite()
}
