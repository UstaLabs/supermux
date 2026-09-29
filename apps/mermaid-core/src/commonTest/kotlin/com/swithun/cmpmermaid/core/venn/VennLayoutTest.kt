package com.swithun.cmpmermaid.core.venn

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VennLayoutTest {
    @Test
    fun rendersTitleCirclesIntersectionsAndTextNodesInOfficialPaintOrder() {
        val scene = render(
            """
            ---
            title: Product fit
            ---
            venn-beta
              set Desirable
                text Need["Customer need"]
              set Feasible
              set Viable
              union Desirable,Feasible["Buildable"]
              union Feasible,Viable["Sustainable"]
              union Desirable,Viable["Marketable"]
              union Desirable,Feasible,Viable["Ship it"]
            """.trimIndent(),
        )

        val paths = scene.elements.filterIsInstance<ScenePath>()
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertEquals(7, paths.size)
        assertEquals("Product fit", texts.first().text)
        assertTrue(texts.any { text -> text.text == "Desirable" })
        assertTrue(texts.any { text -> text.text == "Ship it" })
        assertTrue(texts.any { text ->
            text.text.replace(Regex("\\s+"), " ") == "Customer need"
        })
        assertTrue(paths.all { path -> path.points.all { point -> point.x.isFinite() && point.y.isFinite() } })
        assertEquals(824f, scene.width)
        assertEquals(477.2f, scene.height)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
    }

    @Test
    fun appliesSetIntersectionAndTextStylesWithFrontmatterGeometry() {
        val scene = render(
            """
            ---
            config:
              venn:
                width: 640
                height: 360
                padding: 24
                useMaxWidth: false
                useDebugLayout: true
            ---
            venn-beta
              set A["Alpha"]:20
                text A1["React"]
              set B["Beta"]:12
              union A,B["AB"]:3
              style A fill:#ff0000,stroke:#0000ff,stroke-width:4px,fill-opacity:0.25
              style A,B fill:#00ff00,color:#112233
              style A1 color:rgb(255, 0, 128)
            """.trimIndent(),
        )

        val circle = scene.elements.filterIsInstance<ScenePath>().first()
        assertEquals(SceneColor(0x40FF0000), circle.fillColor)
        assertEquals(SceneColor(0xF20000FF), circle.color)
        assertEquals(4f, circle.strokeWidth)
        val intersection = scene.elements
            .filterIsInstance<ScenePath>()
            .first { path -> path.id.startsWith("venn-intersection") }
        assertEquals(SceneColor(0xFF00FF00), intersection.fillColor)
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "AB" && text.color == SceneColor(0xFF112233)
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "React" && text.color == SceneColor(0xFFFF0080)
        })
        assertTrue(scene.elements.filterIsInstance<SceneShape>().isNotEmpty())
        assertEquals(664f, scene.width)
        assertEquals(384f, scene.height)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun preservesMermaidTitleCssAndDoesNotSplitSingleWordLabels() {
        val scene = render(
            """
            ---
            config:
              venn:
                width: 640
                height: 360
            ---
            venn-beta
              title Release readiness
              set Engineering["Engineering"]:20
                text T1["Observability"]
              set Product["Product"]:18
              union Engineering,Product["Shared delivery"]:6
            """.trimIndent(),
        )

        val texts = scene.elements.filterIsInstance<SceneText>()
        val title = texts.first { text -> text.text == "Release readiness" }
        assertEquals(32f, title.fontSize)
        assertEquals(12f, title.bounds.top, absoluteTolerance = 0.001f)
        assertTrue(texts.any { text -> text.text == "Engineering" && !text.softWrap })
        assertTrue(texts.any { text -> text.text == "Observability" && !text.softWrap })
    }

    @Test
    fun expandsNormalizedViewportToContainLongTitle() {
        val scene = render(
            """
            ---
            title: "Intrinsic service boundary - End To End Production Compatibility Verification Evidence Label 251"
            config:
              venn:
                width: 720
                height: 450
                useMaxWidth: false
            ---
            venn-beta
              set Runtime
              set Storage
              union Runtime,Storage
            """.trimIndent(),
        )

        val title = scene.elements
            .filterIsInstance<SceneText>()
            .first { text -> text.text.startsWith("Intrinsic service boundary") }
        assertTrue(scene.width > 744f)
        assertTrue(scene.height > 474f)
        assertTrue(title.bounds.center.x > scene.width / 2f)
        assertTrue(title.bounds.right > scene.width)
    }

    @Test
    fun wrapsAreaLabelsUsingVennJsPreStyleFontMetrics() {
        val scene = render(
            """
            venn-beta
              title Delivery ownership
              set Product["Product planning"]:24
              set Engineering["Engineering delivery"]
              union Product,Engineering["Shared roadmap"]:6
            """.trimIndent(),
        )

        val engineering = scene.elements
            .filterIsInstance<SceneText>()
            .first { text -> text.text.replace("\n", " ") == "Engineering delivery" }
        assertEquals("Engineering delivery", engineering.text)
    }

    @Test
    fun rejectsInvalidStylesAndDirectConfigurationWithoutThrowing() {
        val invalidStyle = MermaidEngine().render(
            "venn-beta\nset A\nstyle A fill:not-a-color",
            context(),
        )
        assertIs<GMResult.Err<MermaidError>>(invalidStyle)

        val invalidConfig = MermaidEngine().render(
            "venn-beta\nset A",
            context().copy(
                options = context().options.copy(
                    venn = context().options.venn.copy(width = 0f),
                ),
            ),
        )
        assertIs<GMResult.Err<MermaidError>>(invalidConfig)
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(MermaidEngine().render(source, context())).value

    private fun context(): MermaidRenderContext = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lineCount = request.text.count { character -> character == '\n' } + 1
            TextMetrics(
                width = request.text
                    .lineSequence()
                    .maxOfOrNull { line -> line.length * request.fontSize * 0.58f }
                    ?: 0f,
                height = request.fontSize * request.lineHeight * lineCount,
                lineCount = lineCount,
            )
        },
    )
}
