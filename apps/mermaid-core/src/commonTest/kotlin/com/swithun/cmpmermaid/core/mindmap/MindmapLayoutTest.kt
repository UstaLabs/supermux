package com.swithun.cmpmermaid.core.mindmap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.MermaidThemePreset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapJisonParser
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapNodeType
import com.swithun.cmpmermaid.core.mindmap.upstream.tidytree.MindmapTidyTreeLayout
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MindmapLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = deterministicMetrics(),
        theme = MermaidTheme.MermaidDefault,
        options = MermaidRenderOptions(
            themeName = "default",
            look = "classic",
        ),
    )

    @Test
    fun parsesEveryMindmapShapeAndBuildsParentChildEdges() {
        val parser = MindmapJisonParser(
            padding = 10f,
            maxNodeWidth = 200f,
        )
        val db = assertIs<GMResult.Ok<*>>(
            parser.parse(
                """
                mindmap
                  root((Root))
                    default
                    square[Square]
                    rounded(Rounded)
                    circle((Circle))
                    cloud)Cloud(
                    bang))Bang((
                    hex{{Hexagon}}
                """.trimIndent() + "\n",
            ),
        ).value as com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDb
        val document = assertIs<GMResult.Ok<*>>(db.getData()).value as
            com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDocument

        assertEquals(
            listOf(
                MindmapNodeType.Circle,
                MindmapNodeType.Default,
                MindmapNodeType.Rectangle,
                MindmapNodeType.RoundedRectangle,
                MindmapNodeType.Circle,
                MindmapNodeType.Cloud,
                MindmapNodeType.Bang,
                MindmapNodeType.Hexagon,
            ),
            document.nodes.map { node -> node.type },
        )
        assertEquals(7, document.root.children.size)
        assertEquals(7, document.edges.size)
        assertEquals((0..6).toList(), document.root.children.map { node -> node.section })
    }

    @Test
    fun usesNearestLowerIndentationAsParent() {
        val document = parse(
            """
            mindmap
                Root
                    A
                        B
                      C
            """.trimIndent(),
        )

        val a = document.nodes.first { node -> node.description == "A" }
        assertEquals(listOf("B", "C"), a.children.map { child -> child.description })
    }

    @Test
    fun rejectsASecondRootThroughStructuredParseError() {
        val result = MindmapJisonParser(
            padding = 10f,
            maxNodeWidth = 200f,
        ).parse(
            """
            mindmap
              Root
            Other root
            """.trimIndent() + "\n",
        )

        val error = assertIs<GMResult.Err<MermaidError.Parse>>(result).error
        assertTrue(error.message.contains("only one root"))
    }

    @Test
    fun rendersMeasuredNodesAndDepthStyledBasisEdges() {
        val scene = render(
            """
            mindmap
              root((Root))
                A
                  B
                C
            """.trimIndent(),
        )

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val paths = scene.elements.filterIsInstance<ScenePath>()
        val labels = scene.elements.filterIsInstance<SceneText>()
        assertEquals(4, shapes.size)
        assertEquals(3, paths.size)
        assertEquals(4, labels.size)
        assertEquals(listOf(5f, 11f, 11f), paths.map(ScenePath::strokeWidth).sorted())
        assertTrue(paths.all { path -> path.curve == "basis" })
        assertTrue(paths.all { path -> path.commands.isNotEmpty() })
        assertTrue(scene.width > 0f)
        assertTrue(scene.height > 0f)
    }

    @Test
    fun producesDeterministicCoseSceneGraphs() {
        val source = """
            mindmap
              Root
                Planning
                  Scope
                  Risks
                Delivery
                  Android
                  iOS
                  Web
        """.trimIndent()

        assertEquals(render(source), render(source))
    }

    @Test
    fun rendersExplicitDagreLayoutWithMeasuredNodesAndRoutedEdges() {
        val source = """
            ---
            config:
              layout: dagre
            ---
            mindmap
              Root
                Planning
                  Scope
                  Risks
                Delivery
        """.trimIndent()

        val first = render(source)
        val second = render(source)
        val shapes = first.elements.filterIsInstance<SceneShape>()
        val root = shapes.first { shape -> shape.id == "node-0" }
        val firstLevel = listOf("node-1", "node-4").map { id ->
            shapes.first { shape -> shape.id == id }
        }

        assertEquals(first, second)
        assertTrue(firstLevel.all { shape -> shape.bounds.center.y > root.bounds.center.y })
        assertEquals(4, first.elements.filterIsInstance<ScenePath>().size)
        assertTrue(first.elements.filterIsInstance<ScenePath>().all { path ->
            path.points.size >= 2 && path.commands.isNotEmpty()
        })
    }

    @Test
    fun rendersExplicitTidyTreeOnBothSidesOfTheRoot() {
        val source = """
            ---
            config:
              layout: tidy-tree
            ---
            mindmap
              Root
                Left
                  Left child
                Right
                  Right child
        """.trimIndent()

        val first = render(source)
        val second = render(source)
        val shapes = first.elements.filterIsInstance<SceneShape>()
        val root = shapes.first { shape -> shape.id == "node-0" }.bounds.center
        val left = shapes.first { shape -> shape.id == "node-1" }.bounds.center
        val right = shapes.first { shape -> shape.id == "node-3" }.bounds.center

        assertEquals(first, second)
        assertTrue(left.x < root.x)
        assertTrue(right.x > root.x)
        assertTrue(first.elements.filterIsInstance<ScenePath>().all { path ->
            path.points.size == 4 &&
                path.points.all { point -> point.x.isFinite() && point.y.isFinite() }
        })
    }

    @Test
    fun matchesMermaidTidyTree100ReferencePlacement() {
        val document = parse(
            """
            mindmap
              Root
                left[Left]
                  leftChild)Left child(
                right((Right))
                  rightChild))Right child((
            """.trimIndent(),
        )
        val result = assertIs<GMResult.Ok<MindmapPlacement>>(
            MindmapTidyTreeLayout.layout(
                document = document,
                nodeSizes = mapOf(
                    0 to SceneSize(80f, 40f),
                    1 to SceneSize(60f, 30f),
                    2 to SceneSize(50f, 20f),
                    3 to SceneSize(70f, 35f),
                    4 to SceneSize(55f, 25f),
                ),
            ),
        ).value

        assertEquals(ScenePoint(0f, 20f), result.nodeCenters.getValue(0))
        assertEquals(ScenePoint(-141f, 15f), result.nodeCenters.getValue(1))
        assertEquals(ScenePoint(-236f, 15f), result.nodeCenters.getValue(2))
        assertEquals(ScenePoint(146f, 17.5f), result.nodeCenters.getValue(3))
        assertEquals(ScenePoint(248.5f, 17.5f), result.nodeCenters.getValue(4))
        assertEquals(
            listOf(
                ScenePoint(-40f, 20f),
                ScenePoint(-70f, 20f),
                ScenePoint(-81f, 15f),
                ScenePoint(-111f, 15f),
            ),
            result.edgePoints.getValue("edge_0_1"),
        )
        assertEquals(
            listOf(
                ScenePoint(181f, 17.5f),
                ScenePoint(211f, 17.5f),
                ScenePoint(191f, 17.5f),
                ScenePoint(236f, 17.5f),
            ),
            result.edgePoints.getValue("edge_3_4"),
        )
    }

    @Test
    fun appliesOfficialSectionPaletteAcrossThemes() {
        MermaidThemePreset.entries.forEach { preset ->
            val themed = context.copy(
                theme = MermaidTheme.preset(preset),
                options = context.options.copy(themeName = preset.configName),
            )
            val scene = render(
                "mindmap\n  Root\n    First\n    Second",
                themed,
            )
            val shapes = scene.elements.filterIsInstance<SceneShape>()
            assertEquals(3, shapes.size, preset.name)
            assertNotEquals(
                SceneColor(0x00000000),
                shapes.first { shape -> shape.id == "node-0" }.fill,
                preset.name,
            )
            assertEquals(12, themed.theme.mindmap.sectionFills.size, preset.name)
            assertEquals(12, themed.theme.mindmap.sectionInverseColors.size, preset.name)
            assertEquals(12, themed.theme.mindmap.sectionLabelColors.size, preset.name)
        }
    }

    @Test
    fun distinguishesMonochromeAndColorReduxMindmapStyles() {
        val source = "mindmap\n  Root\n    Child"

        listOf(MermaidThemePreset.Redux, MermaidThemePreset.ReduxDark).forEach { preset ->
            val themed = MermaidTheme.preset(preset)
            val scene = render(
                source,
                context.copy(
                    theme = themed,
                    options = context.options.copy(
                        themeName = preset.configName,
                        look = "neo",
                    ),
                ),
            )
            val child = scene.elements
                .filterIsInstance<SceneShape>()
                .first { shape -> shape.id == "node-1" }
            val childLabel = scene.elements
                .filterIsInstance<SceneText>()
                .first { label -> label.text == "Child" }

            assertEquals(themed.mindmap.mainBackground, child.fill, preset.name)
            assertEquals(themed.mindmap.nodeBorder, child.stroke, preset.name)
            assertEquals(themed.mindmap.nodeBorder, childLabel.color, preset.name)
        }

        listOf(
            MermaidThemePreset.ReduxColor,
            MermaidThemePreset.ReduxDarkColor,
        ).forEach { preset ->
            val themed = MermaidTheme.preset(preset)
            val scene = render(
                source,
                context.copy(
                    theme = themed,
                    options = context.options.copy(
                        themeName = preset.configName,
                        look = "neo",
                    ),
                ),
            )
            val shapes = scene.elements.filterIsInstance<SceneShape>()
            val root = shapes.first { shape -> shape.id == "node-0" }
            val child = shapes.first { shape -> shape.id == "node-1" }
            val rootLabel = scene.elements
                .filterIsInstance<SceneText>()
                .first { label -> label.text == "Root" }
            val childLabel = scene.elements
                .filterIsInstance<SceneText>()
                .first { label -> label.text == "Child" }

            assertEquals(themed.mindmap.mainBackground, root.fill, preset.name)
            assertEquals(themed.mindmap.nodeBorder, rootLabel.color, preset.name)
            assertEquals(themed.mindmap.sectionFills[1], child.fill, preset.name)
            assertEquals(themed.mindmap.sectionFills[1], child.stroke, preset.name)
            assertEquals(themed.mindmap.sectionLabelColors[1], childLabel.color, preset.name)
            assertNotEquals(themed.mindmap.mainBackground, child.fill, preset.name)
        }
    }

    @Test
    fun appliesMindmapFrontmatterSizingConfiguration() {
        val measuredWidths = mutableListOf<Float>()
        val configured = context.copy(
            textMetrics = TextMetricProvider { request ->
                measuredWidths += request.maxWidth
                TextMetrics(width = 40f, height = 20f)
            },
        )
        val scene = render(
            """
            ---
            config:
              mindmap:
                padding: 18
                maxNodeWidth: 90
                useMaxWidth: false
            ---
            mindmap
              Root
                Child
            """.trimIndent(),
            configured,
        )

        assertTrue(measuredWidths.isNotEmpty())
        assertTrue(measuredWidths.all { width -> width == 90f })
        assertTrue(scene.elements.filterIsInstance<SceneShape>().all { shape ->
            shape.bounds.left >= 18f
        })
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
        assertEquals(
            MermaidSceneViewportSizing.ResponsiveMaxWidth,
            render("mindmap\n  Root", configured).viewportSizing,
        )
    }

    @Test
    fun followsUpstreamShapeSpecificLabelWidthFallback() {
        val measuredWidths = mutableMapOf<String, Float>()
        val configured = context.copy(
            textMetrics = TextMetricProvider { request ->
                measuredWidths[request.text] = request.maxWidth
                TextMetrics(width = 40f, height = 20f)
            },
            options = context.options.copy(
                wrappingWidth = 120f,
                mindmap = context.options.mindmap.copy(maxNodeWidth = 200f),
            ),
        )
        render(
            """
            mindmap
              root((RootCircle))
                DefaultNode
                RectangleNode[Rectangle]
                RoundedNode(Rounded)
                CircleNode((Circle))
                CloudNode)Cloud(
                BangNode))Bang((
                HexNode{{Hexagon}}
            """.trimIndent(),
            configured,
        )

        assertEquals(200f, measuredWidths.getValue("RootCircle"))
        assertEquals(200f, measuredWidths.getValue("DefaultNode"))
        assertEquals(120f, measuredWidths.getValue("Rectangle"))
        assertEquals(120f, measuredWidths.getValue("Rounded"))
        assertEquals(200f, measuredWidths.getValue("Circle"))
        assertEquals(200f, measuredWidths.getValue("Cloud"))
        assertEquals(200f, measuredWidths.getValue("Bang"))
        assertEquals(120f, measuredWidths.getValue("Hexagon"))

        measuredWidths.clear()
        render(
            "mindmap\n  ReduxDefault",
            configured.copy(
                options = configured.options.copy(themeName = "redux"),
            ),
        )
        assertEquals(120f, measuredWidths.getValue("ReduxDefault"))
    }

    @Test
    fun returnsUnsupportedFeatureForExternalStylingAndUnavailableLayouts() {
        val icon = engine.render(
            "mindmap\n  Root\n    Child\n    ::icon(fa fa-book)",
            context,
        )
        val cssClass = engine.render(
            "mindmap\n  Root\n    Child\n    :::urgent",
            context,
        )
        val layout = engine.render(
            """
            ---
            config:
              layout: unknown-layout
            ---
            mindmap
              Root
                Child
            """.trimIndent(),
            context,
        )
        val elk = engine.render(
            """
            ---
            config:
              layout: elk
            ---
            mindmap
              Root
                Child
            """.trimIndent(),
            context,
        )

        assertIs<GMResult.Err<MermaidError.UnsupportedFeature>>(icon)
        assertIs<GMResult.Err<MermaidError.UnsupportedFeature>>(cssClass)
        assertIs<GMResult.Err<MermaidError.UnsupportedFeature>>(layout)
        val elkError = assertIs<GMResult.Err<MermaidError.UnsupportedFeature>>(elk).error
        assertTrue(elkError.message.contains("Mermaid.js 12.0.0 fails"))
    }

    @Test
    fun rendersMarkdownTextAndAllSupportedShapeKinds() {
        val scene = render(
            """
            mindmap
              root((**Root**))
                square[Square]
                rounded(Rounded)
                circle((Circle))
                cloud)Cloud(
                bang))Bang((
                hex{{Hexagon}}
            """.trimIndent(),
        )

        val kinds = scene.elements.filterIsInstance<SceneShape>().map(SceneShape::kind).toSet()
        assertTrue(SceneShapeKind.Circle in kinds)
        assertTrue(SceneShapeKind.Rectangle in kinds)
        assertTrue(SceneShapeKind.RoundedRectangle in kinds)
        assertTrue(SceneShapeKind.Cloud in kinds)
        assertTrue(SceneShapeKind.Bang in kinds)
        assertTrue(SceneShapeKind.Hexagon in kinds)
        assertTrue(
            scene.elements
                .filterIsInstance<SceneText>()
                .first { text -> text.text == "Root" }
                .spans
                .isNotEmpty(),
        )
    }

    @Test
    fun appliesMindmapThemeVariableOverrides() {
        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = MermaidTheme.MermaidDefault,
                values = mapOf(
                    "mainBkg" to "#102030",
                    "nodeBorder" to "#405060",
                    "git0" to "#708090",
                    "gitBranchLabel0" to "#fefefe",
                    "cScale1" to "#112233",
                    "cScaleInv1" to "#445566",
                    "cScaleLabel1" to "#778899",
                    "useGradient" to "true",
                    "gradientStart" to "#abcdef",
                    "gradientStop" to "#fedcba",
                ),
                themeName = "default",
            ),
        ).value.mindmap

        assertEquals(SceneColor(0xFF102030), customized.mainBackground)
        assertEquals(SceneColor(0xFF405060), customized.nodeBorder)
        assertEquals(SceneColor(0xFF708090), customized.rootFill)
        assertEquals(SceneColor(0xFFFEFEFE), customized.rootText)
        assertEquals(SceneColor(0xFF112233), customized.sectionFills[1])
        assertEquals(SceneColor(0xFF445566), customized.sectionInverseColors[1])
        assertEquals(SceneColor(0xFF778899), customized.sectionLabelColors[1])
        assertTrue(customized.useGradient)
        assertEquals(SceneColor(0xFFABCDEF), customized.gradientStart)
        assertEquals(SceneColor(0xFFFEDCBA), customized.gradientStop)
    }

    @Test
    fun followsBaseThemeNodeBorderGradientOverrideRule() {
        val base = MermaidTheme.preset(MermaidThemePreset.Base)
        val withoutExplicitGradient = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = base,
                values = mapOf(
                    "nodeBorder" to "#334155",
                    "git0" to "#0f766e",
                    "cScale1" to "#dbeafe",
                ),
                themeName = "base",
            ),
        ).value
        val withExplicitGradient = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = base,
                values = mapOf(
                    "nodeBorder" to "#334155",
                    "useGradient" to "true",
                ),
                themeName = "base",
            ),
        ).value

        assertFalse(withoutExplicitGradient.mindmap.useGradient)
        assertFalse(withoutExplicitGradient.gitGraph.useGradient)
        assertEquals(SceneColor(0xFF0F766E), withoutExplicitGradient.mindmap.rootFill)
        assertEquals(SceneColor(0xFFDBEAFE), withoutExplicitGradient.mindmap.sectionFills[1])
        assertTrue(withExplicitGradient.mindmap.useGradient)
        assertTrue(withExplicitGradient.gitGraph.useGradient)
    }

    private fun parse(
        source: String,
    ): com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDocument {
        val parser = MindmapJisonParser(
            padding = 10f,
            maxNodeWidth = 200f,
        )
        val db = assertIs<GMResult.Ok<*>>(parser.parse(source + "\n")).value as
            com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDb
        return assertIs<GMResult.Ok<*>>(db.getData()).value as
            com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDocument
    }

    private fun render(
        source: String,
        renderContext: MermaidRenderContext = context,
    ): MermaidScene = assertIs<GMResult.Ok<MermaidScene>>(
        engine.render(source, renderContext),
    ).value

    private companion object {
        fun deterministicMetrics(): TextMetricProvider = TextMetricProvider { request ->
            val lines = request.text.split('\n')
            val longest = lines.maxOfOrNull(String::length) ?: 0
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    max(1f, longest * request.fontSize * 0.55f),
                ),
                height = max(1, lines.size) * request.fontSize * request.lineHeight,
            )
        }
    }
}
