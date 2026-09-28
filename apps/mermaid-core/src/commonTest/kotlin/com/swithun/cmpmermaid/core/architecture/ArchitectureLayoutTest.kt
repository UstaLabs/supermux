package com.swithun.cmpmermaid.core.architecture

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArchitectureLayoutTest {
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
    fun rendersDirectionalEdgesIconsGroupsAndLabels() {
        val scene = render(
            """
                architecture-beta
                  group api(cloud)[API]
                  group data(database)[Data]
                  service app(server)[Application] in api
                  service db(database)[Database] in data
                  app{group}:R --> L:db{group}
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val paths = scene.elements.filterIsInstance<ScenePath>()
        val app = shapes.single { it.id == "architecture-service-icon-app-background" }
        val db = shapes.single { it.id == "architecture-service-icon-db-background" }
        val groups = shapes.filter {
            it.id in setOf("architecture-group-api", "architecture-group-data")
        }
        val edge = paths.single { it.id.startsWith("architecture-edge-") }

        assertTrue(app.bounds.center.x < db.bounds.center.x)
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.strokePattern == SceneStrokePattern.Dashed })
        assertEquals(SceneArrowHead.None, edge.arrowEnd)
        assertTrue(
            shapes.single { it.id == "architecture-edge-0-arrow-end" }
                .geometry != null,
        )
        assertTrue(
            scene.elements.filterIsInstance<SceneText>()
                .map(SceneText::text)
                .containsAll(listOf("API", "Data", "Application", "Database")),
        )
    }

    @Test
    fun honorsExplicitRowAndColumnAlignment() {
        val row = render(
            """
                architecture-beta
                  service a(server)[A]
                  service b(server)[B]
                  service c(server)[C]
                  align row a b c
            """.trimIndent(),
        )
        val rowCenters = listOf("a", "b", "c").map { id ->
            row.serviceBounds(id).center
        }
        assertEquals(1, rowCenters.map { it.y }.distinct().size)
        assertTrue(rowCenters.zipWithNext().all { (first, second) -> first.x < second.x })

        val column = render(
            """
                architecture-beta
                  service a(server)[A]
                  service b(server)[B]
                  service c(server)[C]
                  align column a b c
            """.trimIndent(),
        )
        val columnCenters = listOf("a", "b", "c").map { id ->
            column.serviceBounds(id).center
        }
        assertEquals(1, columnCenters.map { it.x }.distinct().size)
        assertTrue(columnCenters.zipWithNext().all { (first, second) -> first.y < second.y })
    }

    @Test
    fun keepsCombinedAlignmentGroupsIndependentDuringOverlapResolution() {
        val scene = render(
            """
                architecture-beta
                  service input_a(server)[Input A]
                  service input_b(server)[Input B]
                  service process_a(server)[Process A]
                  service process_b(server)[Process B]
                  service output_a(disk)[Output A]
                  service output_b(disk)[Output B]
                  input_a:B --> T:process_a
                  input_b:B --> T:process_b
                  process_a:B --> T:output_a
                  process_b:B --> T:output_b
                  align row input_a input_b
                  align row process_a process_b
                  align row output_a output_b
                  align column input_a process_a output_a
                  align column input_b process_b output_b
            """.trimIndent(),
        )
        val leftColumn = listOf("input_a", "process_a", "output_a")
            .map { id -> scene.serviceBounds(id).center }
        val rightColumn = listOf("input_b", "process_b", "output_b")
            .map { id -> scene.serviceBounds(id).center }

        assertEquals(1, leftColumn.map(ScenePoint::x).distinct().size)
        assertEquals(1, rightColumn.map(ScenePoint::x).distinct().size)
        assertTrue(rightColumn.first().x - leftColumn.first().x in 120f..220f)
        assertTrue(
            leftColumn.zipWithNext().all { (first, second) ->
                second.y - first.y in 120f..220f
            },
        )
    }

    @Test
    fun nestedGroupBoundsContainTheirDescendants() {
        val scene = render(
            """
                architecture-beta
                  group outer(cloud)[Outer]
                  group inner(cloud)[Inner] in outer
                  service app(server)[Application] in inner
            """.trimIndent(),
        )
        val outer = scene.shape("architecture-group-outer").bounds
        val inner = scene.shape("architecture-group-inner").bounds
        val app = scene.serviceBounds("app")

        assertTrue(outer.contains(inner))
        assertTrue(inner.contains(app))
    }

    @Test
    fun appliesArchitectureConfigurationAndExternalIcons() {
        val scene = render(
            """
                ---
                config:
                  architecture:
                    useMaxWidth: false
                    padding: 24
                    iconSize: 48
                    fontSize: 12
                    randomize: false
                    nodeSeparation: 60
                    idealEdgeLengthMultiplier: 2
                    edgeElasticity: 0.6
                    numIter: 100
                    seed: 42
                ---
                architecture-beta
                  service app(logos:kotlin)[Application]
            """.trimIndent(),
        )
        val asset = scene.elements.filterIsInstance<SceneAsset>().single()

        assertEquals("logos:kotlin", asset.source)
        assertEquals(48f, asset.bounds.width, 0.01f)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun preservesAccessibilityMetadataAndSeededDeterminism() {
        val source = """
            architecture-beta
              accTitle: Accessible system
              accDescr: A deterministic architecture.
              service a(server)[A]
              service b(database)[B]
              a:R -- L:b
        """.trimIndent()
        val first = render(source)
        val second = render(source)

        assertEquals(first, second)
        assertEquals("Accessible system", first.accessibilityTitle)
        assertEquals("A deterministic architecture.", first.accessibilityDescription)
    }

    @Test
    fun detectorAcceptsArchitecturePrefixButGrammarRequiresBeta() {
        val result = engine.render(
            source = "architecture\nservice app",
            context = context,
        )

        assertIs<GMResult.Err<MermaidError>>(result)
        assertIs<MermaidError.Parse>(result.error)
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
    }

    private fun MermaidScene.shape(id: String): SceneShape =
        elements.filterIsInstance<SceneShape>().single { it.id == id }

    private fun MermaidScene.serviceBounds(id: String) =
        shape("architecture-service-icon-$id-background").bounds

    private fun com.swithun.cmpmermaid.core.SceneRect.contains(
        other: com.swithun.cmpmermaid.core.SceneRect,
    ): Boolean =
        left <= other.left &&
            top <= other.top &&
            right >= other.right &&
            bottom >= other.bottom
}
