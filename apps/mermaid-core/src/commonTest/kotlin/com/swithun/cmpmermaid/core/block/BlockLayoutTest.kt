package com.swithun.cmpmermaid.core.block

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidBlockOptions
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockDb
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockJisonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BlockLayoutTest {
    @Test
    fun laysOutColumnsSpansSpacesAndNestedContainers() {
        val scene = render(
            """
            block
              columns 3
              A["A"] B["B"]:2
              space
              block:group:2
                columns 1
                C["C"]
                D["D"]
              end
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val a = shapes.getValue("A")
        val b = shapes.getValue("B")
        val group = shapes.getValue("group")
        val c = shapes.getValue("C")
        val d = shapes.getValue("D")

        assertTrue(b.bounds.width > a.bounds.width)
        assertTrue(group.bounds.width > a.bounds.width)
        assertTrue(c.bounds.center.y < d.bounds.center.y)
        assertTrue(c.bounds.left >= group.bounds.left)
        assertTrue(d.bounds.right <= group.bounds.right)
        assertEquals(5, shapes.size)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
    }

    @Test
    fun rendersShapesBlockArrowsEdgesAndLabels() {
        val scene = render(
            """
            block-beta
              columns 3
              A(("Start"))
              arrow<["go"]>(right)
              B{"Stop"}
              A -- "route" --> B
              B -.-> A
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val paths = scene.elements.filterIsInstance<ScenePath>()

        assertEquals(SceneShapeKind.Circle, shapes.single { it.id == "A" }.kind)
        assertEquals(SceneShapeKind.Diamond, shapes.single { it.id == "B" }.kind)
        assertTrue(shapes.single { it.id == "arrow" }.geometry?.outline.orEmpty().size >= 7)
        assertEquals(2, paths.size)
        assertEquals(SceneArrowHead.Triangle, paths[0].arrowEnd)
        assertEquals(SceneStrokePattern.Dotted, paths[1].strokePattern)
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { it.text == "route" })
    }

    @Test
    fun keepsNonSpanningBlockArrowsAtTheirNaturalSize() {
        val scene = render(
            """
            block-beta
              columns auto
              right<["Right"]>(right)
              evidence["A substantially wider evidence label"]
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)

        assertTrue(shapes.getValue("right").bounds.width < shapes.getValue("evidence").bounds.width)
    }

    @Test
    fun appliesPositionedDoubleCircleAndCylinderSizing() {
        val doubleCircleScene = render(
            """
            block
              columns 2
              complete((("Complete")))
              evidence["A substantially wider evidence label"]
            """.trimIndent(),
        )
        val doubleCircleShapes = doubleCircleScene.elements
            .filterIsInstance<SceneShape>()
            .associateBy(SceneShape::id)
        val complete = doubleCircleShapes.getValue("complete")
        val evidence = doubleCircleShapes.getValue("evidence")
        assertTrue(complete.bounds.width > evidence.bounds.width)
        assertEquals(complete.bounds.width, complete.bounds.height, absoluteTolerance = 0.01f)

        val cylinderScene = render(
            """
            block
              columns 2
              archive[("Archive")]
              evidence["A substantially wider evidence label"]
            """.trimIndent(),
        )
        val cylinderShapes = cylinderScene.elements
            .filterIsInstance<SceneShape>()
            .associateBy(SceneShape::id)
        assertTrue(
            cylinderShapes.getValue("archive").bounds.height >
                cylinderShapes.getValue("evidence").bounds.height,
        )
    }

    @Test
    fun appliesInlineClassAndCompositePaletteStyles() {
        val theme = MermaidTheme.MermaidDefault.copy(
            bkgColorArray = listOf(SceneColor(0xFF112233), SceneColor(0xFF445566)),
            borderColorArray = listOf(SceneColor(0xFF778899), SceneColor(0xFFAABBCC)),
        )
        val options = MermaidRenderOptions(themeName = "redux-color")
        val source =
            """
            block
              block:first
                A
              end
              block:second
                B
              end
              classDef marked fill:#123456,stroke:#654321,color:#ffffff
              class A marked
              style B fill:#abcdef,stroke-width:4px
            """.trimIndent()
        val db = assertIs<GMResult.Ok<BlockDb>>(BlockJisonParser(options).parse(source)).value
        val scene = assertIs<GMResult.Ok<MermaidScene>>(
            BlockLayout().layout(
                db = db,
                context = context(options = options, theme = theme),
            ),
        ).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        assertEquals(SceneColor(0xFF112233), shapes.getValue("first").fill)
        assertEquals(SceneColor(0xFF445566), shapes.getValue("second").fill)
        assertEquals(SceneColor(0xFF123456), shapes.getValue("A").fill)
        assertEquals(SceneColor(0xFF654321), shapes.getValue("A").stroke)
        assertEquals(SceneColor(0xFFABCDEF), shapes.getValue("B").fill)
        assertEquals(4f, shapes.getValue("B").strokeWidth)
    }

    @Test
    fun appliesBlockConfigurationAndRejectsInvalidPadding() {
        val intrinsic = render(
            """
            ---
            config:
              block:
                padding: 20
                useMaxWidth: false
            ---
            block
              A B
            """.trimIndent(),
        )
        assertEquals(MermaidSceneViewportSizing.Intrinsic, intrinsic.viewportSizing)

        val invalid = MermaidEngine().render(
            "block\nA",
            context(
                options = MermaidRenderOptions(
                    block = MermaidBlockOptions(padding = -1f),
                ),
            ),
        )
        assertIs<GMResult.Err<*>>(invalid)
    }

    private fun render(
        source: String,
        theme: MermaidTheme = MermaidTheme.MermaidDefault,
    ): MermaidScene {
        val result = MermaidEngine().render(source, context(theme = theme))
        return assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
    }

    private fun context(
        options: MermaidRenderOptions = MermaidRenderOptions(),
        theme: MermaidTheme = MermaidTheme.MermaidDefault,
    ): MermaidRenderContext = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.split('\n')
            TextMetrics(
                width = lines.maxOfOrNull { line ->
                    line.length * request.fontSize * 0.5f
                } ?: 0f,
                height = lines.size * request.fontSize * request.lineHeight,
                lineCount = lines.size,
            )
        },
        theme = theme,
        options = options,
    )
}
