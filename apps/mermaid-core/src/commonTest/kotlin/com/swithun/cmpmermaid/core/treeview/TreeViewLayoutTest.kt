package com.swithun.cmpmermaid.core.treeview

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.MermaidTreeViewOptions
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TreeViewLayoutTest {
    private val engine = MermaidEngine()
    private val textMetrics = TextMetricProvider { request ->
        TextMetrics(
            width = request.text.length * 8f,
            height = 16f,
        )
    }

    @Test
    fun rendersUpstreamRowsConnectorsAndNormalizedViewport() {
        val scene = render(
            """
            treeView-beta
                src/
                    index.ts
            """.trimIndent(),
        )
        val texts = scene.elements.filterIsInstance<SceneText>()
        val paths = scene.elements.filterIsInstance<ScenePath>()

        assertEquals(listOf("/", "src", "index.ts"), texts.map(SceneText::text))
        assertEquals(SceneTextWeight.Bold, texts[0].weight)
        assertEquals(SceneTextWeight.Bold, texts[1].weight)
        assertEquals(SceneTextWeight.Normal, texts[2].weight)
        assertEquals(5, paths.size)
        assertEquals(12f, paths[0].points.first().x)
        assertEquals(22f, paths[0].points.last().x)
        assertEquals(27f, paths[1].points.first().x)
        assertEquals(27f, texts[0].bounds.left)
        assertEquals(138f, scene.width)
        assertEquals(102f, scene.height)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
    }

    @Test
    fun alignsDescriptionsAndExpandsHighlightsToTreeWidth() {
        val scene = render(
            """
            treeView-beta
                src/
                    App.tsx :::highlight ## main component
                    index.ts ## entry point
                package.json
            """.trimIndent(),
        )
        val descriptions = scene.elements.filterIsInstance<SceneText>()
            .filter { text -> text.italic }
        val highlight = scene.elements.filterIsInstance<SceneShape>().single()

        assertEquals(listOf("main component", "entry point"), descriptions.map(SceneText::text))
        assertEquals(descriptions[0].bounds.left, descriptions[1].bounds.left)
        assertEquals(SceneColor(0x1AFFC107), highlight.fill)
        assertTrue(highlight.bounds.right > descriptions.maxOf { text -> text.bounds.right })
    }

    @Test
    fun rendersBuiltInAndExternalIconsWithSuppression() {
        val scene = render(
            """
            ---
            config:
              treeView:
                showIcons: true
                defaultIconPack: material-icon-theme
                extensionIcons:
                  .ts: typescript
                  .txt: none
            ---
            treeView-beta
                src/
                    App.ts icon(logos:react)
                    util.ts
                    notes.txt
            """.trimIndent(),
        )
        val assets = scene.elements.filterIsInstance<SceneAsset>()
        val iconPaths = scene.elements.filterIsInstance<ScenePath>()
            .filter { path -> "icon" in path.id }

        assertEquals(listOf("logos:react", "material-icon-theme:typescript"), assets.map { it.source })
        assertEquals(2, iconPaths.size)
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text -> text.text == "notes.txt" })
    }

    @Test
    fun appliesTreeViewConfigAndThemeVariables() {
        val scene = render(
            """
            ---
            config:
              treeView:
                useMaxWidth: false
                rowIndent: 20
                paddingX: 7
                paddingY: 3
                lineThickness: 2
              themeVariables:
                treeView:
                  labelFontSize: 20px
                  labelColor: "#123456"
                  lineColor: "#234567"
                  descriptionColor: "#345678"
                  highlightBg: "rgba(10, 20, 30, 0.5)"
                  highlightStroke: "#456789"
            ---
            treeView-beta
                file.ts :::highlight ## configured
            """.trimIndent(),
        )
        val label = scene.elements.filterIsInstance<SceneText>()
            .first { text -> text.text == "file.ts" }
        val description = scene.elements.filterIsInstance<SceneText>()
            .first { text -> text.text == "configured" }
        val highlight = scene.elements.filterIsInstance<SceneShape>().single()

        assertEquals(20f, label.fontSize)
        assertEquals(SceneColor(0xFF123456), label.color)
        assertEquals(SceneColor(0xFF345678), description.color)
        assertEquals(SceneColor(0x800A141E), highlight.fill)
        assertEquals(SceneColor(0xFF456789), highlight.stroke)
        assertTrue(scene.elements.filterIsInstance<ScenePath>().all { path ->
            path.color == SceneColor(0xFF234567) && path.strokeWidth == 2f
        })
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun carriesAccessibilityMetadata() {
        val scene = render(
            """
            treeView-beta
              title Source tree
              accTitle: Accessible source tree
              accDescr: Files grouped by directory
                src/
            """.trimIndent(),
        )

        assertEquals("Source tree", scene.title)
        assertEquals("Accessible source tree", scene.accessibilityTitle)
        assertEquals("Files grouped by directory", scene.accessibilityDescription)
    }

    @Test
    fun returnsConfigurationErrorsForInvalidOptionsAndFrontmatterMaps() {
        val direct = engine.render(
            "treeView-beta\nfile.txt",
            context.copy(
                options = MermaidRenderOptions(
                    treeView = MermaidTreeViewOptions(rowIndent = -1f),
                ),
            ),
        )
        assertIs<MermaidError.Configuration>(
            assertIs<GMResult.Err<MermaidError>>(direct).error,
        )

        val frontmatter = engine.render(
            """
            ---
            config:
              treeView:
                filenameIcons:
                  Dockerfile:
                    nested: invalid
            ---
            treeView-beta
                Dockerfile
            """.trimIndent(),
            context,
        )
        assertIs<MermaidError.Configuration>(
            assertIs<GMResult.Err<MermaidError>>(frontmatter).error,
        )
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            source,
        ).value

    private val context = MermaidRenderContext(
        textMetrics = textMetrics,
        theme = MermaidTheme.MermaidDefault,
    )
}
