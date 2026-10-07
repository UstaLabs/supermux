package com.swithun.cmpmermaid.core.gitgraph

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.MermaidThemePreset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneLinearGradient
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitGraphLayoutTest {
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.split('\n')
            TextMetrics(
                width = lines.maxOfOrNull(String::length).orEmpty() * request.fontSize * 0.55f,
                height = lines.size * request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
        options = MermaidRenderOptions(
            themeName = "default",
            look = "classic",
        ),
    )

    @Test
    fun rendersBranchesArrowsLabelsTagsAndCommitSymbols() {
        val scene = render(
            """
            gitGraph LR:
                commit id:"root" tag:"v1"
                branch feature
                commit id:"feature" type: HIGHLIGHT
                checkout main
                commit id:"main" type: REVERSE
                merge feature id:"merge" tag:"v2"
                branch release
                commit id:"release"
                checkout main
                cherry-pick id:"release"
            """.trimIndent(),
        )

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val paths = scene.elements.filterIsInstance<ScenePath>()
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertEquals(3, paths.count { path -> path.id.startsWith("git-branch-") })
        assertEquals(7, paths.count { path -> path.id.startsWith("git-arrow-") })
        assertTrue(shapes.any { shape -> shape.id == "git-commit-feature-highlight-outer" })
        assertTrue(shapes.any { shape -> shape.id == "git-commit-feature-highlight-inner" })
        assertTrue(paths.any { path -> path.id == "git-commit-main-reverse" })
        assertTrue(shapes.any { shape -> shape.id == "git-commit-merge-merge-inner" })
        assertTrue(shapes.any { shape -> shape.id.startsWith("git-tag-background-root") })
        assertTrue(texts.any { text -> text.text == "v1" })
        assertTrue(texts.any { text -> text.text == "v2" })
        assertTrue(scene.width > 0f)
        assertTrue(scene.height > 0f)
    }

    @Test
    fun mapsDirectionsToOfficialCommitAxes() {
        val lr = render("gitGraph LR:\ncommit id:\"a\"\ncommit id:\"b\"")
        val tb = render("gitGraph TB:\ncommit id:\"a\"\ncommit id:\"b\"")
        val bt = render("gitGraph BT:\ncommit id:\"a\"\ncommit id:\"b\"")

        val lrCenters = listOf("a", "b").map { id -> lr.commitCenter(id) }
        val tbCenters = listOf("a", "b").map { id -> tb.commitCenter(id) }
        val btCenters = listOf("a", "b").map { id -> bt.commitCenter(id) }
        assertTrue(lrCenters[0].x < lrCenters[1].x)
        assertEquals(lrCenters[0].y, lrCenters[1].y)
        assertEquals(tbCenters[0].x, tbCenters[1].x)
        assertTrue(tbCenters[0].y < tbCenters[1].y)
        assertEquals(btCenters[0].x, btCenters[1].x)
        assertTrue(btCenters[0].y > btCenters[1].y)
    }

    @Test
    fun parallelCommitsShareTheSameRankAcrossBranches() {
        val source = """
            gitGraph LR:
                commit id:"root"
                branch feature
                commit id:"feature-1"
                checkout main
                commit id:"main-1"
        """.trimIndent()
        val sequential = render(source)
        val parallel = render(
            """
            ---
            config:
              gitGraph:
                parallelCommits: true
            ---
            $source
            """.trimIndent(),
        )

        assertNotEquals(
            sequential.commitCenter("feature-1").x,
            sequential.commitCenter("main-1").x,
        )
        assertEquals(
            parallel.commitCenter("feature-1").x,
            parallel.commitCenter("main-1").x,
        )
    }

    @Test
    fun bottomToTopParallelCommitsPreserveParentRanks() {
        val scene = render(
            """
            ---
            config:
              gitGraph:
                parallelCommits: true
            ---
            gitGraph BT:
                commit id:"root"
                branch feature
                commit id:"feature-1"
                checkout main
                commit id:"main-1"
            """.trimIndent(),
        )

        val root = scene.commitCenter("root")
        val feature = scene.commitCenter("feature-1")
        val main = scene.commitCenter("main-1")
        assertTrue(root.y > feature.y)
        assertEquals(feature.y, main.y)
    }

    @Test
    fun usesOfficialReduxBranchLabelGeometry() {
        val reduxContext = context.copy(
            theme = MermaidTheme.preset(MermaidThemePreset.Redux),
            options = context.options.copy(themeName = "redux"),
        )
        val leftToRight = render(
            "gitGraph LR:\ncommit id:\"root\"",
            reduxContext,
        )
        val mainLabel = leftToRight.elements
            .filterIsInstance<SceneShape>()
            .first { shape -> shape.id == "git-branch-label-background-main" }
        assertEquals(
            29f,
            leftToRight.commitCenter("root").x - mainLabel.bounds.right,
            absoluteTolerance = 0.01f,
        )

        val topToBottom = render(
            "gitGraph TB:\ncommit id:\"root\"",
            reduxContext,
        )
        val verticalLabel = topToBottom.elements
            .filterIsInstance<SceneShape>()
            .first { shape -> shape.id == "git-branch-label-background-main" }
        assertEquals(
            reduxContext.theme.fontSize * 1.2f + 16f,
            verticalLabel.bounds.height,
            absoluteTolerance = 0.01f,
        )
    }

    @Test
    fun offsetsRotatedLeftToRightCommitLabelsLikeOfficialRenderer() {
        val scene = render(
            "gitGraph LR:\ncommit id:\"LongCommitIdentifier\"",
        )
        val commit = scene.commitCenter("LongCommitIdentifier")
        val label = scene.elements
            .filterIsInstance<SceneText>()
            .first { text -> text.text == "LongCommitIdentifier" }

        assertTrue(label.bounds.center.x < commit.x)
        assertTrue(label.bounds.center.y > commit.y)
    }

    @Test
    fun preservesOfficialCommitLabelPaintOrderForOverlappingHorizontalLabels() {
        val scene = render(
            """
            ---
            config:
              gitGraph:
                showBranches: false
                rotateCommitLabel: false
            ---
            gitGraph LR:
                commit id:"initialize-release-coordination"
                branch maintenance
                commit id:"prepare-follow-up-correction"
                checkout main
                cherry-pick id:"prepare-follow-up-correction"
                commit id:"close-release-window" tag:"complete"
            """.trimIndent(),
        )

        val cherryPickTag = scene.elements.indexOfFirst { element ->
            element is SceneText &&
                element.text == "cherry-pick:prepare-follow-up-correction"
        }
        val laterCommitBackground = scene.elements.indexOfFirst { element ->
            element is SceneShape &&
                element.id == "git-commit-label-background-close-release-window"
        }
        val laterCommitText = scene.elements.indexOfFirst { element ->
            element is SceneText && element.text == "close-release-window"
        }
        val laterTagBackground = scene.elements.indexOfFirst { element ->
            element is SceneShape &&
                element.id == "git-tag-background-close-release-window-0"
        }
        val laterTagHole = scene.elements.indexOfFirst { element ->
            element is SceneShape &&
                element.id == "git-tag-hole-close-release-window-0"
        }
        val laterTagText = scene.elements.indexOfFirst { element ->
            element is SceneText && element.text == "complete"
        }

        assertTrue(cherryPickTag >= 0)
        assertTrue(cherryPickTag < laterCommitBackground)
        assertTrue(laterCommitBackground < laterCommitText)
        assertTrue(laterCommitText < laterTagBackground)
        assertTrue(laterTagBackground < laterTagHole)
        assertTrue(laterTagHole < laterTagText)
    }

    @Test
    fun reversesBottomToTopCommitPaintOrderLikeOfficialRenderer() {
        val scene = render(
            """
            ---
            config:
              gitGraph:
                parallelCommits: true
            ---
            gitGraph BT:
                commit id: "bt-root"
                branch develop
                branch release
                commit id: "release-base"
                checkout develop
                commit id: "bt-change"
                checkout main
                commit id: "main-change"
                merge develop id: "bt-merge"
                branch hotfix
                commit id: "independent-fix"
                checkout release
                cherry-pick id: "bt-merge" parent: "bt-change"
                cherry-pick id: "independent-fix"
            """.trimIndent(),
        )

        val labelOrder = scene.elements
            .filterIsInstance<SceneText>()
            .map(SceneText::text)
            .filter { text ->
                text.startsWith("cherry-pick:") ||
                    text in setOf("independent-fix", "bt-merge", "bt-root")
            }

        assertEquals(
            listOf(
                "cherry-pick:independent-fix",
                "cherry-pick:bt-merge|parent:bt-change",
                "independent-fix",
                "bt-merge",
                "bt-root",
            ),
            labelOrder,
        )
    }

    @Test
    fun positionsVerticalTagTextBeforeApplyingTheSvgRotation() {
        val tag = "release-candidate"
        val measuredWidth = tag.length * context.theme.gitGraph.tagLabelFontSize * 0.55f
        val measuredHeight =
            context.theme.gitGraph.tagLabelFontSize * 1.2f

        listOf("TB", "BT").forEach { direction ->
            val scene = render(
                "gitGraph $direction:\ncommit id:\"root\" tag:\"$tag\"",
            )
            val commit = scene.commitCenter("root")
            val label = scene.elements
                .filterIsInstance<SceneText>()
                .first { text -> text.text == tag }
            val rotationPivot = assertNotNull(label.rotationPivot)

            assertEquals(45f, label.rotationDegrees)
            assertEquals(
                commit.x + 19f + measuredWidth / 2f,
                label.bounds.center.x,
                absoluteTolerance = 0.01f,
            )
            assertEquals(
                commit.y + 7f - measuredHeight / 2f,
                label.bounds.center.y,
                absoluteTolerance = 0.01f,
            )
            assertEquals(
                commit.x + 14f,
                rotationPivot.x,
                absoluteTolerance = 0.01f,
            )
            assertEquals(
                commit.y + 4f,
                rotationPivot.y,
                absoluteTolerance = 0.01f,
            )
        }
    }

    @Test
    fun translatesOfficialQuarterArcsToCubicPaths() {
        val scene = render(
            """
            gitGraph LR:
                commit id:"root"
                commit id:"main-1"
                branch feature
                commit id:"feature-1"
                commit id:"feature-2"
                merge main id:"feature-merge"
            """.trimIndent(),
        )
        val rerouted = scene.elements
            .filterIsInstance<ScenePath>()
            .first { path -> path.id == "git-arrow-main-1-feature-merge" }

        assertTrue(rerouted.commands.any { command -> command is ScenePathCommand.CubicTo })
        assertTrue(rerouted.commands.none { command -> command is ScenePathCommand.QuadraticTo })
    }

    @Test
    fun usesThemeTextColorForDiagramTitle() {
        val scene = render(
            """
            ---
            title: Release history
            config:
              themeVariables:
                textColor: "#123456"
            ---
            gitGraph
                commit id:"root"
            """.trimIndent(),
        )
        val title = scene.elements
            .filterIsInstance<SceneText>()
            .first { text -> text.text == "Release history" }
        val root = scene.commitCenter("root")

        assertEquals(SceneColor(0xFF123456), title.color)
        assertEquals(23f, root.y - title.bounds.bottom, absoluteTolerance = 0.01f)
    }

    @Test
    fun disablesAutomaticWrappingWhilePreservingExplicitBranchLines() {
        val scene = render(
            """
            ---
            title: Release history
            ---
            gitGraph
                commit id:"bootstrap" tag:"v1"
                branch "release\ncandidate"
                commit id:"ready"
            """.trimIndent(),
        )
        val texts = scene.elements.filterIsInstance<SceneText>()

        assertTrue(texts.none(SceneText::softWrap))
        assertTrue(texts.any { text -> text.text == "release\ncandidate" })
    }

    @Test
    fun usesUnscaledSvgTextMetricsAndPainting() {
        val measuredScales = mutableListOf<Float?>()
        val renderContext = context.copy(
            textMetrics = TextMetricProvider { request ->
                measuredScales += request.horizontalScale
                TextMetrics(
                    width = request.text.length * request.fontSize * 0.55f,
                    height = request.fontSize * request.lineHeight,
                )
            },
        )
        val scene = render(
            "gitGraph\ncommit id:\"root\" tag:\"v1\"",
            renderContext,
        )
        val texts = scene.elements.filterIsInstance<SceneText>()

        assertTrue(measuredScales.isNotEmpty())
        assertTrue(measuredScales.all { scale -> scale == 1f })
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.all { text -> text.horizontalScale == 1f })
    }

    @Test
    fun honorsGitGraphVisibilityAndBranchConfiguration() {
        val scene = render(
            """
            ---
            config:
              gitGraph:
                mainBranchName: trunk
                mainBranchOrder: 3
                showBranches: false
                showCommitLabel: false
                rotateCommitLabel: false
                diagramPadding: 20
            ---
            gitGraph
                commit id:"root"
                branch feature order: 1
                commit id:"feature"
            """.trimIndent(),
        )

        assertTrue(
            scene.elements.filterIsInstance<ScenePath>()
                .none { path -> path.id.startsWith("git-branch-") },
        )
        assertTrue(
            scene.elements.filterIsInstance<SceneText>()
                .none { text -> text.text == "root" || text.text == "feature" },
        )
        assertTrue(scene.width > 40f)
        assertTrue(scene.height > 40f)
    }

    @Test
    fun appliesGitGraphThemeVariables() {
        val scene = render(
            """
            ---
            config:
              theme: default
              themeVariables:
                git0: "#112233"
                gitInv0: "#445566"
                gitBranchLabel0: "#778899"
                commitLineColor: "#abcdef"
                commitLabelColor: "#fedcba"
                tagLabelBackground: "#123456"
            ---
            gitGraph
                commit id:"root" type: HIGHLIGHT tag:"v1"
            """.trimIndent(),
        )

        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val paths = scene.elements.filterIsInstance<ScenePath>().associateBy(ScenePath::id)
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertEquals(
            SceneColor(0xFF445566),
            shapes.getValue("git-commit-root-highlight-outer").fill,
        )
        assertEquals(SceneColor(0xFFABCDEF), paths.getValue("git-branch-main").color)
        assertEquals(
            SceneColor(0xFF123456),
            shapes.getValue("git-tag-background-root-0").fill,
        )
        assertTrue(texts.any { text -> text.text == "root" && text.color == SceneColor(0xFFFEDCBA) })
        assertTrue(texts.any { text -> text.text == "main" && text.color == SceneColor(0xFF778899) })
    }

    @Test
    fun derivesHighlightColorFromFrontmatterThemeAndGitColor() {
        val scene = render(
            """
            ---
            config:
              theme: base
              themeVariables:
                git1: "#c2410c"
            ---
            gitGraph
                commit id:"root"
                branch feature
                commit id:"highlight" type: HIGHLIGHT
            """.trimIndent(),
        )
        val highlight = scene.elements
            .filterIsInstance<SceneShape>()
            .first { shape -> shape.id == "git-commit-highlight-highlight-outer" }

        assertEquals(SceneColor(0xFFB5E6FA), highlight.fill)
    }

    @Test
    fun usesNodeBorderForReduxColorMainBranchHighlight() {
        val reduxColorContext = context.copy(
            theme = MermaidTheme.preset(MermaidThemePreset.ReduxColor),
            options = context.options.copy(
                themeName = MermaidThemePreset.ReduxColor.configName,
                look = "neo",
            ),
        )
        val scene = render(
            "gitGraph\ncommit id:\"highlight\" type: HIGHLIGHT",
            reduxColorContext,
        )
        val shapes = scene.elements
            .filterIsInstance<SceneShape>()
            .associateBy(SceneShape::id)
        val outer = shapes.getValue("git-commit-highlight-highlight-outer")
        val inner = shapes.getValue("git-commit-highlight-highlight-inner")

        assertEquals(reduxColorContext.theme.gitGraph.nodeBorder, outer.fill)
        assertEquals(reduxColorContext.theme.gitGraph.nodeBorder, outer.stroke)
        assertEquals(reduxColorContext.theme.gitGraph.mainBackground, inner.fill)
        assertEquals(reduxColorContext.theme.gitGraph.mainBackground, inner.stroke)
    }

    @Test
    fun usesOfficialPerBranchColorsForNeoThemes() {
        val source = """
            gitGraph
                commit id:"root"
                branch feature
                commit id:"feature"
                branch hotfix
                commit id:"highlight" type: HIGHLIGHT
        """.trimIndent()

        listOf(MermaidThemePreset.Neo, MermaidThemePreset.NeoDark).forEach { preset ->
            val renderContext = context.copy(
                theme = MermaidTheme.preset(preset),
                options = context.options.copy(themeName = preset.configName),
            )
            val scene = render(source, renderContext)
            val shapes = scene.elements
                .filterIsInstance<SceneShape>()
                .associateBy(SceneShape::id)
            val paths = scene.elements
                .filterIsInstance<ScenePath>()
                .associateBy(ScenePath::id)
            val texts = scene.elements.filterIsInstance<SceneText>()

            assertEquals(
                renderContext.theme.gitGraph.nodeBorder,
                shapes.getValue("git-commit-root").fill,
                preset.name,
            )
            assertEquals(
                renderContext.theme.gitGraph.colors[1],
                shapes.getValue("git-commit-feature").fill,
                preset.name,
            )
            assertEquals(
                renderContext.theme.gitGraph.inverseColors[2],
                shapes.getValue("git-commit-highlight-highlight-outer").fill,
                preset.name,
            )
            assertEquals(
                renderContext.theme.gitGraph.colors[1],
                paths.getValue("git-arrow-root-feature").color,
                preset.name,
            )
            assertEquals(
                renderContext.theme.gitGraph.colors[2],
                paths.getValue("git-arrow-feature-highlight").color,
                preset.name,
            )
            val expectedGradient = SceneLinearGradient(
                startColor = renderContext.theme.gitGraph.gradientStart,
                endColor = renderContext.theme.gitGraph.gradientStop,
            )
            listOf("main", "feature", "hotfix").forEach { branch ->
                val background = shapes.getValue("git-branch-label-background-$branch")
                assertEquals(
                    renderContext.theme.gitGraph.mainBackground,
                    background.fill,
                    "$preset $branch fill",
                )
                assertEquals(
                    expectedGradient,
                    background.strokeGradient,
                    "$preset $branch stroke",
                )
            }
            assertTrue(
                texts.any { text ->
                    text.text == "feature" &&
                        text.color == renderContext.theme.gitGraph.branchLabelColors[1]
                },
                preset.name,
            )
        }
    }

    @Test
    fun usesReduxDarkColorForNonMainBranchLabelBorders() {
        val renderContext = context.copy(
            theme = MermaidTheme.preset(MermaidThemePreset.ReduxDarkColor),
            options = context.options.copy(
                themeName = MermaidThemePreset.ReduxDarkColor.configName,
            ),
        )
        val shapes = render(
            """
            gitGraph
                commit id:"root"
                branch release
                commit id:"candidate"
                branch develop
                commit id:"integration"
            """.trimIndent(),
            renderContext,
        ).elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)

        assertEquals(
            renderContext.theme.gitGraph.nodeBorder,
            shapes.getValue("git-branch-label-background-main").stroke,
        )
        assertEquals(
            renderContext.theme.borderColorArray[1],
            shapes.getValue("git-branch-label-background-release").stroke,
        )
        assertEquals(
            renderContext.theme.borderColorArray[2],
            shapes.getValue("git-branch-label-background-develop").stroke,
        )
    }

    @Test
    fun usesOfficialThemeColorForCherryPickBackgroundAcrossThemes() {
        val source = """
            gitGraph
                commit id:"root"
                branch feature
                commit id:"source"
                checkout main
                branch release
                commit id:"candidate"
                cherry-pick id:"source"
        """.trimIndent()
        val darkThemes = setOf(
            MermaidThemePreset.Dark,
            MermaidThemePreset.NeoDark,
            MermaidThemePreset.ReduxDark,
            MermaidThemePreset.ReduxDarkColor,
        )
        val neoColorThemes = setOf(
            MermaidThemePreset.Neo,
            MermaidThemePreset.NeoDark,
            MermaidThemePreset.Redux,
            MermaidThemePreset.ReduxColor,
            MermaidThemePreset.ReduxDark,
            MermaidThemePreset.ReduxDarkColor,
        )

        MermaidThemePreset.entries.forEach { preset ->
            val renderContext = context.copy(
                theme = MermaidTheme.preset(preset),
                options = context.options.copy(themeName = preset.configName),
            )
            val shapes = render(source, renderContext)
                .elements
                .filterIsInstance<SceneShape>()
                .associateBy(SceneShape::id)
            val cherry = shapes.values.first { shape ->
                shape.id.endsWith("-cherry-0")
            }
            val outer = shapes.getValue(cherry.id.removeSuffix("-cherry-0"))
            val expectedFruitColor = if (preset in darkThemes) {
                SceneColor(0xFF000000)
            } else {
                SceneColor(0xFFFFFFFF)
            }
            val expectedBackgroundColor = if (preset in neoColorThemes) {
                renderContext.theme.gitGraph.nodeBorder
            } else {
                renderContext.theme.gitGraph.textColor
            }

            assertEquals(expectedBackgroundColor, outer.fill, preset.name)
            assertEquals(expectedFruitColor, cherry.fill, preset.name)
        }
    }

    @Test
    fun reportsSemanticAndResourceFailuresWithoutThrowing() {
        val semantic = MermaidEngine().render(
            source = "gitGraph\ncheckout missing",
            context = context,
        )
        val semanticError = assertIs<GMResult.Err<MermaidError>>(semantic).error
        assertIs<MermaidError.Parse>(semanticError)

        val limited = MermaidEngine().render(
            source = """
                gitGraph
                    commit id:"a"
                    commit id:"b"
            """.trimIndent(),
            context = context.copy(
                options = context.options.copy(maxEdges = 0),
            ),
        )
        val resourceError = assertIs<GMResult.Err<MermaidError>>(limited).error
        assertIs<MermaidError.ResourceLimit>(resourceError)
    }

    private fun render(
        source: String,
        renderContext: MermaidRenderContext = context,
    ): MermaidScene {
        val result = MermaidEngine().render(source, renderContext)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected Git Graph render success:\n$source\n$result",
        ).value
    }

    private fun MermaidScene.commitCenter(id: String) =
        elements.filterIsInstance<SceneShape>()
            .first { shape -> shape.id == "git-commit-$id" }
            .bounds
            .center

    private fun Int?.orEmpty(): Int = this ?: 0
}
