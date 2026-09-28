package com.swithun.cmpmermaid.core.c4

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class C4LayoutTest {
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
                lineCount = lines.size,
            )
        },
    )

    @Test
    fun rendersC4ShapesRelationsAndStereotypeText() {
        val scene = render(
            """
                C4Context
                Person(user, "Customer", "Uses the service")
                SystemDb(data, "Primary data", "Stores records")
                SystemQueue(events, "Events", "Publishes updates")
                BiRel(user, data, "Reads", "HTTPS")
                Rel(data, events, "Publishes")
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val texts = scene.elements.filterIsInstance<SceneText>().map(SceneText::text)
        val relations = scene.elements.filterIsInstance<ScenePath>()

        assertEquals(SceneShapeKind.Person, shapes.shape("c4-shape-user").kind)
        assertEquals(SceneShapeKind.Cylinder, shapes.shape("c4-shape-data").kind)
        assertEquals(
            SceneShapeKind.DirectAccessStorage,
            shapes.shape("c4-shape-events").kind,
        )
        assertTrue(
            texts.containsAll(
                listOf(
                    "Customer",
                    "[Person]",
                    "Primary data",
                    "[Software System]",
                    "Events",
                ),
            ),
        )
        assertEquals(SceneArrowHead.Triangle, relations.first().arrowStart)
        assertEquals(SceneArrowHead.Triangle, relations.first().arrowEnd)
        assertEquals(SceneArrowHead.None, relations.last().arrowStart)
        assertEquals(SceneArrowHead.Triangle, relations.last().arrowEnd)
    }

    @Test
    fun containsNestedShapesAndBoundaries() {
        val scene = render(
            """
                C4Container
                Enterprise_Boundary(company, "Company") {
                  System_Boundary(system, "Banking") {
                    Container(app, "Application", "Kotlin")
                    ContainerDb(data, "Database", "SQL")
                  }
                }
            """.trimIndent(),
        )
        val company = scene.shape("c4-boundary-company").bounds
        val system = scene.shape("c4-boundary-system").bounds
        val app = scene.shape("c4-shape-app").bounds
        val data = scene.shape("c4-shape-data").bounds

        assertTrue(company.contains(system))
        assertTrue(system.contains(app))
        assertTrue(system.contains(data))
        assertEquals(SceneStrokePattern.Dashed, scene.shape("c4-boundary-company").strokePattern)
    }

    @Test
    fun prefixesDynamicRelationsInSourceOrder() {
        val scene = render(
            """
                C4Dynamic
                Container(client, "Client")
                Container(api, "API")
                ContainerDb(data, "Data")
                Rel(client, api, "Calls")
                Rel(api, data, "Reads")
            """.trimIndent(),
        )
        val texts = scene.elements.filterIsInstance<SceneText>().map(SceneText::text)

        assertTrue("1: Calls" in texts)
        assertTrue("2: Reads" in texts)
    }

    @Test
    fun appliesElementAndRelationshipStyles() {
        val scene = render(
            """
                C4Component
                Component(api, "API", "Kotlin")
                ComponentDb(data, "Data", "SQL")
                Rel(api, data, "Reads")
                UpdateElementStyle(api, ${'$'}bgColor="#102030", ${'$'}borderColor="#405060", ${'$'}fontColor="#f0f0f0")
                UpdateRelStyle(api, data, ${'$'}textColor="#aabbcc", ${'$'}lineColor="#112233", ${'$'}offsetX="12", ${'$'}offsetY="-8")
            """.trimIndent(),
        )
        val api = scene.shape("c4-shape-api")
        val relation = scene.elements.filterIsInstance<ScenePath>().single()
        val label = scene.elements.filterIsInstance<SceneText>().single { it.text == "Reads" }

        assertEquals(0xFF102030, api.fill.argb)
        assertEquals(0xFF405060, api.stroke.argb)
        assertEquals(0xFF112233, relation.color.argb)
        assertEquals(0xFFAABBCC, label.color.argb)
    }

    @Test
    fun reportsUnknownRelationshipEndpointsAsLayoutErrors() {
        val result = engine.render(
            """
                C4Context
                Person(user, "User")
                Rel(user, missing, "Calls")
            """.trimIndent(),
            context,
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.Layout>(error)
        assertTrue(error.message.contains("missing"))
    }

    @Test
    fun renderingIsDeterministic() {
        val source = """
            C4Deployment
            Node(runtime, "Runtime", "Linux") {
              Container(api, "API", "Kotlin")
              ContainerDb(data, "Data", "SQL")
            }
            Rel(api, data, "Reads", "JDBC")
        """.trimIndent()

        assertEquals(render(source), render(source))
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
    }

    private fun List<SceneShape>.shape(id: String): SceneShape =
        single { shape -> shape.id == id }

    private fun MermaidScene.shape(id: String): SceneShape =
        elements.filterIsInstance<SceneShape>().shape(id)

    private fun SceneRect.contains(other: SceneRect): Boolean =
        left <= other.left &&
            top <= other.top &&
            right >= other.right &&
            bottom >= other.bottom

    private fun Int?.orZero(): Int = this ?: 0
}
