package com.swithun.cmpmermaid.core.swimlane

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwimlaneLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * request.fontSize * 0.55f),
                height = request.fontSize * request.lineHeight,
            )
        },
    )

    @Test
    fun rendersTopToBottomLanesAsAlignedColumns() {
        val scene = render(
            """
                swimlane-beta TB
                  subgraph Customer
                    request[Request]
                    receive[Receive]
                  end
                  subgraph Support
                    triage[Triage]
                    answer[Answer]
                  end
                  request --> triage --> answer --> receive
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val customer = shapes.getValue("subgraph_Customer")
        val support = shapes.getValue("subgraph_Support")

        assertEquals(customer.bounds.height, support.bounds.height, 0.01f)
        assertEquals(customer.bounds.center.y, support.bounds.center.y, 0.01f)
        assertTrue(customer.bounds.right <= support.bounds.left + 0.01f)
        assertTrue(shapes.getValue("request").bounds.bottom < shapes.getValue("receive").bounds.top)
    }

    @Test
    fun rendersLeftToRightLanesAsAlignedRowsWithRotatedTitles() {
        val scene = render(
            """
                swimlane-beta LR
                  subgraph Customer
                    request[Request]
                    receive[Receive]
                  end
                  subgraph Support
                    triage[Triage]
                    answer[Answer]
                  end
                  request --> triage --> answer --> receive
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val customer = shapes.getValue("subgraph_Customer")
        val support = shapes.getValue("subgraph_Support")
        val titles = scene.elements.filterIsInstance<SceneText>()
            .filter { it.text in setOf("Customer", "Support") }

        assertEquals(customer.bounds.width, support.bounds.width, 0.01f)
        assertEquals(customer.bounds.center.x, support.bounds.center.x, 0.01f)
        assertTrue(customer.bounds.bottom <= support.bounds.top + 0.01f)
        assertTrue(shapes.getValue("request").bounds.right < shapes.getValue("receive").bounds.left)
        assertTrue(titles.all { it.rotationDegrees == -90f })
        titles.forEach { title ->
            val lane = shapes.getValue("subgraph_${title.text}")
            val pivot = assertNotNull(title.rotationPivot)
            assertEquals(lane.bounds.center.x, pivot.x, 0.01f)
            assertEquals(lane.bounds.center.y, pivot.y, 0.01f)
        }
    }

    @Test
    fun synthesizesDefaultLaneForLooseNodes() {
        val scene = render(
            """
                swimlane-beta
                  A[Loose] --> B[Still loose]
            """.trimIndent(),
        )

        assertTrue(
            scene.elements.filterIsInstance<SceneShape>()
                .any { it.id == "subgraph_${SwimlaneLayout.DefaultLaneId}" },
        )
    }

    @Test
    fun keepsLabelledEdgesOrthogonalAndVisible() {
        val scene = render(
            """
                swimlane-beta LR
                  subgraph Intake
                    A[Open]
                  end
                  subgraph Review
                    B{Ready?}
                  end
                  A -->|Needs review| B
            """.trimIndent(),
        )
        val edge = scene.elements.filterIsInstance<ScenePath>().single()
        val labels = scene.elements.filterIsInstance<SceneText>().map(SceneText::text)

        assertTrue(edge.points.size >= 2)
        edge.points.zipWithNext().forEach { (first, second) ->
            assertTrue(first.x == second.x || first.y == second.y)
        }
        assertTrue("Needs review" in labels)
    }

    @Test
    fun scopedDagreOverrideUsesRegularFlowchartClusters() {
        val scene = render(
            """
                ---
                config:
                  swimlane:
                    layout: dagre
                    theme: default
                    look: classic
                ---
                swimlane-beta LR
                  A --> B
            """.trimIndent(),
        )

        assertTrue(
            scene.elements.filterIsInstance<SceneShape>()
                .none { it.id == "subgraph_${SwimlaneLayout.DefaultLaneId}" },
        )
    }

    @Test
    fun parsesAllSwimlaneSpecificConfiguration() {
        val scene = render(
            """
                ---
                config:
                  swimlane:
                    lineHops: gap
                    ignoreCrossLaneEdges: false
                    optimizeRanksByCrossings: false
                    automaticLaneOrdering: true
                ---
                swimlane-beta TB
                  subgraph A
                    one
                  end
                  subgraph B
                    two
                  end
                  one --> two
            """.trimIndent(),
        )

        assertTrue(scene.width > 0f)
        assertTrue(scene.height > 0f)
    }

    @Test
    fun preservesAccessibilityMetadataOutsideVisibleSceneText() {
        val scene = render(
            """
                swimlane-beta LR
                  accTitle: Accessible support flow
                  accDescr: A request moves from intake to resolution.
                  subgraph Intake
                    open[Open request]
                  end
                  subgraph Resolution
                    resolve[Resolve request]
                  end
                  open --> resolve
            """.trimIndent(),
        )

        assertEquals("Accessible support flow", scene.accessibilityTitle)
        assertEquals(
            "A request moves from intake to resolution.",
            scene.accessibilityDescription,
        )
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
    }
}
