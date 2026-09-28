package com.swithun.cmpmermaid.core.usecase

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UsecaseLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.split('\n')
            TextMetrics(
                width = (lines.maxOfOrNull(String::length) ?: 0) * request.fontSize * 0.55f,
                height = lines.size * request.fontSize * request.lineHeight,
                lineCount = lines.size,
            )
        },
    )

    @Test
    fun rendersDedicatedActorGeometryAndIconAsset() {
        val scene = render(
            """
            usecase-beta
            actor Normal
            actor Hollow@{ type: hollow, business: true }
            actor Awesome@{ type: awesome }
            actor Icon@{ icon: "fa:user" }
            Normal --> Login
            Hollow --> Login
            Awesome --> Login
            Icon --> Login
            """.trimIndent(),
        )

        listOf("Normal", "Hollow", "Awesome", "Icon").forEach { id ->
            val actor = scene.elements.filterIsInstance<SceneShape>().firstOrNull { it.id == id }
            assertNotNull(actor)
            assertNotNull(actor.geometry)
            assertTrue(actor.geometry.paths.size >= 1)
        }
        assertTrue(scene.elements.filterIsInstance<SceneAsset>().any { it.id == "Icon_asset" })
        assertTrue(
            scene.elements
                .filterIsInstance<SceneShape>()
                .first { it.id == "Hollow" }
                .geometry
                ?.paths
                .orEmpty()
                .any { path -> path.fill == com.swithun.cmpmermaid.core.SceneShapePaint.None },
        )
    }

    @Test
    fun rendersPackageBoundaryAndJsonGrid() {
        val scene = render(
            """
            usecase-beta
            systemBoundary system["Order system"]@{ type: package }
              Inspect
            end
            json Payload@{
              "state": "ready",
              "items": [1, 2]
            }
            Inspect --> Payload
            """.trimIndent(),
        )

        assertTrue(scene.elements.filterIsInstance<SceneShape>().any { it.id == "subgraph_system" })
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any { it.id == "subgraph_system_tab" })
        val json = scene.elements.filterIsInstance<SceneShape>().first { it.id == "Payload" }
        assertTrue(json.geometry.orEmptyPaths() >= 8)
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { it.text == "items" })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { it.text == "2" })
    }

    @Test
    fun preservesRelationshipMarkersPatternsAndRoleColors() {
        val scene = render(
            """
            usecase-beta
            actor Customer
            Checkout
            Payment
            Customer --> Checkout
            Checkout ..> : include Payment
            Payment ..> : extend Checkout
            """.trimIndent(),
        )

        val paths = scene.elements.filterIsInstance<ScenePath>().associateBy(ScenePath::id)
        assertEquals(SceneArrowHead.Triangle, paths.getValue("edge-0").arrowEnd)
        assertEquals(SceneStrokePattern.Dotted, paths.getValue("edge-1").strokePattern)
        assertEquals(SceneColor(0xFF38BDF8), paths.getValue("edge-1").color)
        assertEquals(SceneColor(0xFFFB923C), paths.getValue("edge-2").color)
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        assertEquals(SceneColor(0xFFF5F3FF), shapes.getValue("Customer").fill)
        assertEquals(SceneColor(0xFFF0FDFA), shapes.getValue("Checkout").fill)
    }

    @Test
    fun matchesOfficialCascadeWhenRotateColorSchemeIsEnabled() {
        val scene = render(
            """
            ---
            config:
              theme: redux-color
              usecase:
                colorScheme: rotate
            ---
            usecase-beta
            actor Customer
            actor Auditor
            Browse
            Checkout
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)

        assertEquals(SceneColor(0xFFFDF4FF), shapes.getValue("Customer").fill)
        assertEquals(SceneColor(0xFFF0FDFA), shapes.getValue("Auditor").fill)
        assertEquals(SceneColor(0xFFF0FDFA), shapes.getValue("Browse").fill)
        assertEquals(SceneColor(0xFF2DD4BF), shapes.getValue("Browse").stroke)
        assertEquals(SceneColor(0xFFF0FDFA), shapes.getValue("Checkout").fill)
        assertEquals(SceneColor(0xFF2DD4BF), shapes.getValue("Checkout").stroke)
    }

    @Test
    fun matchesOfficialActorStyleCascadeUnderNeoLook() {
        val scene = render(
            """
            usecase-beta
            actor Plain
            actor Customer:::external
            classDef external fill:#dcfce7,stroke:#0f766e,stroke-width:3px
            """.trimIndent(),
        )
        val plain = scene.elements.filterIsInstance<SceneShape>().first { it.id == "Plain" }
        val customer = scene.elements.filterIsInstance<SceneShape>().first { it.id == "Customer" }

        assertEquals(SceneColor(0xFFDCFCE7), customer.fill)
        assertEquals(plain.stroke, customer.stroke)
        assertEquals(plain.strokeWidth, customer.strokeWidth)
        assertEquals(context.theme.strokeWidth, customer.strokeWidth)
    }

    @Test
    fun matchesOfficialPresetNormalizationOfUsecaseRoleTokens() {
        val scene = render(
            """
            ---
            config:
              theme: redux-color
              themeVariables:
                usecaseBkg: "#dcfce7"
                usecaseBorder: "#15803d"
            ---
            usecase-beta
            Browse
            """.trimIndent(),
        )
        val browse = scene.elements.filterIsInstance<SceneShape>().first { it.id == "Browse" }

        assertEquals(SceneColor(0xFFF0FDFA), browse.fill)
        assertEquals(SceneColor(0xFF2DD4BF), browse.stroke)
    }

    @Test
    fun matchesUpstreamEllipsePaddingAndPlainEntityEscaping() {
        val ellipseScene = render(
            """
            usecase-beta
            Browse
            """.trimIndent(),
        )
        val entityScene = render(
            """
            usecase-beta
            Browse("Tokyo &amp; customer")
            """.trimIndent(),
        )
        val shape = ellipseScene.elements.filterIsInstance<SceneShape>().first { it.id == "Browse" }
        val label = entityScene.elements.filterIsInstance<SceneText>().single()

        assertEquals(160f, shape.bounds.width)
        assertEquals(58f, shape.bounds.height)
        assertEquals("Tokyo &amp; customer", label.text)
    }

    @Test
    fun usesDiagramDefaultTypographyForGenericUsecaseElements() {
        val scene = render(
            """
            usecase-beta
            Inspect
            note for Inspect "Review"
            json Payload@{ "ready": true }
            Inspect starts@-- "starts" --> Payload
            """.trimIndent(),
        )
        val textByValue = scene.elements
            .filterIsInstance<SceneText>()
            .associateBy(SceneText::text)

        assertEquals(12f, textByValue.getValue("Inspect").fontSize)
        assertEquals(14f, textByValue.getValue("Review").fontSize)
        assertEquals(14f, textByValue.getValue("Payload").fontSize)
        assertEquals(14f, textByValue.getValue("ready").fontSize)
        assertEquals(10f, textByValue.getValue("starts").fontSize)
    }

    @Test
    fun usesExplicitGlobalThemeTypographyForGenericUsecaseElements() {
        val scene = render(
            """
            ---
            config:
              theme: default
            ---
            usecase-beta
            Inspect
            note for Inspect "Review"
            json Payload@{ "ready": true }
            """.trimIndent(),
        )
        val textByValue = scene.elements
            .filterIsInstance<SceneText>()
            .associateBy(SceneText::text)

        listOf("Review", "Payload", "ready").forEach { value ->
            val text = textByValue.getValue(value)
            assertEquals(16f, text.fontSize)
            assertEquals("\"trebuchet ms\", verdana, arial, sans-serif", text.fontFamily)
        }
    }

    @Test
    fun appliesUsecaseViewportConfiguration() {
        val scene = render(
            """
            ---
            config:
              usecase:
                useMaxWidth: false
            ---
            usecase-beta
            actor Customer
            Customer --> Checkout
            """.trimIndent(),
        )

        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun convertsTextMetricExceptionsAtTheLowestLayoutBoundary() {
        val result = engine.render(
            "usecase-beta\nactor Customer",
            MermaidRenderContext(
                textMetrics = TextMetricProvider { throw IllegalStateException("metric failed") },
            ),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.Unexpected>(error)
        assertTrue(error.message.contains("Use Case text measurement failed"))
    }

    private fun render(source: String): MermaidScene = when (val result = engine.render(source, context)) {
        is GMResult.Ok -> result.value
        is GMResult.Err -> throw AssertionError(result.error.message)
    }

    private fun com.swithun.cmpmermaid.core.SceneShapeGeometry?.orEmptyPaths(): Int =
        this?.paths?.size ?: 0
}
