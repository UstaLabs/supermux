package com.swithun.cmpmermaid.core.railroad

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRailroadOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
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

class RailroadLayoutTest {
    private val engine = MermaidEngine()
    private val textMetrics = TextMetricProvider { request ->
        TextMetrics(
            width = request.text.length * 8f,
            height = 16f,
        )
    }

    @Test
    fun rendersUpstreamChoiceGeometryAndRuleMarkers() {
        val scene = render(
            """
            railroad-beta
              sign = choice(terminal("+"), terminal("-")) ;
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val texts = scene.elements.filterIsInstance<SceneText>()
        val paths = scene.elements.filterIsInstance<ScenePath>()

        assertEquals(191f, scene.width)
        assertEquals(128f, scene.height)
        assertEquals(4, shapes.size)
        assertEquals(2, shapes.count { shape -> shape.kind == SceneShapeKind.RoundedRectangle })
        assertEquals(2, shapes.count { shape -> shape.kind == SceneShapeKind.Circle })
        assertEquals(listOf("+", "-", "sign ="), texts.map(SceneText::text))
        assertEquals(6, paths.size)
        assertTrue(paths.any { path ->
            path.commands.any { command -> command is ScenePathCommand.CubicTo }
        })
        assertTrue(shapes.filter { shape -> shape.kind == SceneShapeKind.Circle }.all { marker ->
            marker.bounds.center.y == 50f
        })
    }

    @Test
    fun rendersAllFourNotationsThroughTheSharedAstRenderer() {
        val sources = listOf(
            """
            railroad-beta
              value = sequence(optional(terminal("-")), oneOrMore(nonterminal("digit"))) ;
            """.trimIndent(),
            """
            railroad-ebnf-beta
              value = "-"? digit+ ;
            """.trimIndent(),
            """
            railroad-abnf-beta
              value = [ "-" ] 1*digit ;
            """.trimIndent(),
            """
            railroad-peg-beta
              value <- "-"? digit+ ;
            """.trimIndent(),
        )

        sources.forEach { source ->
            val scene = render(source)
            assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
                text.text == "-"
            })
            assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
                text.text == "digit"
            })
            assertTrue(scene.elements.filterIsInstance<ScenePath>().size >= 8)
        }
    }

    @Test
    fun appliesThemeAwareAndExplicitRailroadStyles() {
        val options = MermaidRailroadOptions(
            useMaxWidth = false,
            padding = 6f,
            verticalSeparation = 12f,
            horizontalSeparation = 14f,
            arcRadius = 8f,
            fontSize = 13f,
            fontFamily = "monospace",
            terminalFill = SceneColor(0xFF112233),
            terminalStroke = SceneColor(0xFF445566),
            terminalTextColor = SceneColor(0xFFFFFFFF),
            nonTerminalFill = SceneColor(0xFF778899),
            nonTerminalStroke = SceneColor(0xFF010203),
            nonTerminalTextColor = SceneColor(0xFF040506),
            lineColor = SceneColor(0xFF070809),
            strokeWidth = 3f,
            markerFill = SceneColor(0xFF0A0B0C),
            specialFill = SceneColor(0xFF0D0E0F),
            specialStroke = SceneColor(0xFF101112),
            ruleNameColor = SceneColor(0xFF131415),
            markerRadius = 4f,
        )
        val scene = render(
            """
            railroad-beta
              rule = sequence(terminal("a"), nonterminal("b"), special("c")) ;
            """.trimIndent(),
            options,
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val terminal = shapes.first { shape -> shape.kind == SceneShapeKind.RoundedRectangle }
        val nonTerminal = shapes.first { shape ->
            shape.kind == SceneShapeKind.Rectangle &&
                shape.strokePattern == SceneStrokePattern.Solid
        }
        val special = shapes.first { shape ->
            shape.strokePattern == SceneStrokePattern.Dashed
        }
        val ruleName = scene.elements.filterIsInstance<SceneText>()
            .first { text -> text.text == "rule =" }

        assertEquals(SceneColor(0xFF112233), terminal.fill)
        assertEquals(SceneColor(0xFF445566), terminal.stroke)
        assertEquals(SceneColor(0xFF778899), nonTerminal.fill)
        assertEquals(SceneColor(0xFF010203), nonTerminal.stroke)
        assertEquals(SceneColor(0xFF0D0E0F), special.fill)
        assertEquals(listOf(5f, 3f), special.dashIntervals)
        assertEquals(SceneColor(0xFF131415), ruleName.color)
        assertEquals(13f, ruleName.fontSize)
        assertEquals(
            SceneColor(0xFF070809),
            scene.elements.filterIsInstance<ScenePath>().first().color,
        )
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun appliesFrontmatterConfigurationAndPreservesUpstreamIgnoredFlags() {
        val scene = render(
            """
            ---
            config:
              railroad:
                useMaxWidth: false
                compactMode: true
                padding: 7
                verticalSeparation: 9
                horizontalSeparation: 11
                arcRadius: 6
                fontSize: 15
                fontFamily: monospace
                terminalFill: "#123456"
                terminalStroke: "#234567"
                terminalTextColor: "#ffffff"
                nonTerminalFill: "#345678"
                nonTerminalStroke: "#456789"
                nonTerminalTextColor: "#eeeeee"
                lineColor: "#56789a"
                strokeWidth: 3
                markerFill: "#6789ab"
                commentFill: "#789abc"
                commentStroke: "#89abcd"
                commentTextColor: "#9abcde"
                specialFill: "#abcdef"
                specialStroke: "#bcdef0"
                ruleNameColor: "#cdef01"
                showMarkers: false
                markerRadius: 4
            ---
            railroad-beta
              rule = sequence(terminal("a"), nonterminal("b"), special("c")) ;
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()

        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
        assertEquals(
            SceneColor(0xFF123456),
            shapes.first { shape -> shape.kind == SceneShapeKind.RoundedRectangle }.fill,
        )
        assertEquals(
            SceneColor(0xFFABCDEF),
            shapes.first { shape -> shape.strokePattern == SceneStrokePattern.Dashed }.fill,
        )
        assertEquals(2, shapes.count { shape -> shape.kind == SceneShapeKind.Circle })
        assertTrue(scene.elements.filterIsInstance<ScenePath>().all { path ->
            path.color == SceneColor(0xFF56789A) && path.strokeWidth == 3f
        })
    }

    @Test
    fun carriesMetadataAndUsesOfficialEmptyDiagramDimensions() {
        val scene = render(
            """
            railroad-ebnf-beta
              title Empty grammar
              accTitle: Accessible grammar
              accDescr: No rules are declared.
            """.trimIndent(),
        )

        assertEquals(200f, scene.width)
        assertEquals(100f, scene.height)
        assertTrue(scene.elements.isEmpty())
        assertEquals("Empty grammar", scene.title)
        assertEquals("Accessible grammar", scene.accessibilityTitle)
        assertEquals("No rules are declared.", scene.accessibilityDescription)
    }

    @Test
    fun returnsConfigurationErrorsForInvalidDirectOptions() {
        val invalid = listOf(
            MermaidRailroadOptions(padding = -1f),
            MermaidRailroadOptions(verticalSeparation = Float.NaN),
            MermaidRailroadOptions(horizontalSeparation = Float.POSITIVE_INFINITY),
            MermaidRailroadOptions(arcRadius = -1f),
            MermaidRailroadOptions(fontSize = -1f),
            MermaidRailroadOptions(strokeWidth = -1f),
            MermaidRailroadOptions(markerRadius = -1f),
        )

        invalid.forEach { options ->
            val result = engine.render(
                "railroad-beta\nrule = terminal(\"a\") ;",
                context(options),
            )
            assertIs<MermaidError.Configuration>(
                assertIs<GMResult.Err<MermaidError>>(result).error,
            )
        }
    }

    private fun render(
        source: String,
        options: MermaidRailroadOptions = MermaidRailroadOptions(),
    ): MermaidScene = assertIs<GMResult.Ok<MermaidScene>>(
        engine.render(source, context(options)),
        source,
    ).value

    private fun context(
        options: MermaidRailroadOptions,
    ): MermaidRenderContext = MermaidRenderContext(
        textMetrics = textMetrics,
        theme = MermaidTheme.MermaidDefault,
        options = MermaidRenderOptions(railroad = options),
    )
}
