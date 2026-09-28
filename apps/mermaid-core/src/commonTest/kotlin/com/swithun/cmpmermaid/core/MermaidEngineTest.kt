package com.swithun.cmpmermaid.core

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MermaidEngineTest {
    private val textMetrics = TextMetricProvider { request ->
        val charactersPerLine = (request.maxWidth / 8f).toInt().coerceAtLeast(1)
        val lineCount = ceil(request.text.length.toDouble() / charactersPerLine).toInt().coerceAtLeast(1)
        TextMetrics(
            width = minOf(request.maxWidth, request.text.length * 8f),
            height = lineCount * 18f,
        )
    }
    private val context = MermaidRenderContext(
        textMetrics = textMetrics,
        options = MermaidRenderOptions(layout = "dagre"),
    )
    private val engine = MermaidEngine()

    @Test
    fun rendersCommonFlowchartSyntaxIntoSceneGraph() {
        val result = engine.render(
            """
                flowchart TB
                  subgraph intake [Question intake]
                    A([Student asks]) --> B{Enough context?}
                    B -- No --> C[Clarify]
                  end
                  B -- Yes --> D[(Answer cache)]
                  D --> E((Done))
                  classDef success fill:#DCFCE7,stroke:#15803D,color:#14532D
                  class E success
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val paths = scene.elements.filterIsInstance<ScenePath>()

        assertTrue(scene.width > 0f)
        assertTrue(scene.height > 0f)
        assertTrue(shapes.any { it.id == "A" && it.kind == SceneShapeKind.Stadium })
        assertTrue(shapes.any { it.id == "B" && it.kind == SceneShapeKind.Diamond })
        assertTrue(shapes.any { it.id == "D" && it.kind == SceneShapeKind.Cylinder })
        assertTrue(shapes.any { it.id == "E" && it.kind == SceneShapeKind.Circle })
        assertTrue(shapes.any { it.id == "subgraph_intake" })
        assertEquals(4, paths.size)
    }

    @Test
    fun matchesMermaidSvgEdgeLabelBackgroundGeometry() {
        val result = engine.render(
            "flowchart LR\n A -- label --> B",
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val background = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "L_A_B_0_label_background" }

        assertEquals(SceneShapeKind.Rectangle, background.kind)
        assertEquals(44f, background.bounds.width)
        assertEquals(22f, background.bounds.height)
        assertEquals(SceneColor(0x00000000), background.stroke)
        assertEquals(0f, background.strokeWidth)
        assertEquals(0f, background.cornerRadius)
    }

    @Test
    fun laysOutLeftToRightWithoutNodeOverlap() {
        val result = engine.render(
            """
                graph LR
                  A[Parse] --> B[Layout] --> C[Render]
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val nodes = scene.elements
            .filterIsInstance<SceneShape>()
            .filter { it.id in setOf("A", "B", "C") }
            .associateBy(SceneShape::id)

        assertTrue(nodes.getValue("A").bounds.right < nodes.getValue("B").bounds.left)
        assertTrue(nodes.getValue("B").bounds.right < nodes.getValue("C").bounds.left)
    }

    @Test
    fun supportsFrontMatterAndExpandedShapeSyntax() {
        val result = engine.render(
            """
                ---
                title: Native diagram
                ---
                flowchart TB
                  A@{ shape: hex, label: "Validate" } --> B@{ shape: dbl-circ, label: "Done" }
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val nodes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        assertEquals(SceneShapeKind.Hexagon, nodes.getValue("A").kind)
        assertEquals(SceneShapeKind.DoubleCircle, nodes.getValue("B").kind)
    }

    @Test
    fun distinguishesParallelogramAndTrapezoidDelimiters() {
        val result = engine.render(
            """
                flowchart LR
                  A[/Input/] --> B[/Batch\]
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val nodes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        assertEquals(SceneShapeKind.Parallelogram, nodes.getValue("A").kind)
        assertEquals(SceneShapeKind.Trapezoid, nodes.getValue("B").kind)
    }

    @Test
    fun rendersSequenceDiagramThroughRegisteredPlugin() {
        val result = engine.render(
            """
                sequenceDiagram
                  Alice->>Bob: Hello
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any { it.id == "actor-Alice-top" })
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any { it.id == "actor-Bob-top" })
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any { it.id == "message-0" })
    }

    @Test
    fun rendersJourneyDiagramThroughRegisteredPlugin() {
        val result = engine.render(
            """
                journey
                  section Discover
                    Compare options: 4: Customer
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertTrue(
            scene.elements.filterIsInstance<SceneShape>()
                .any { it.id == "journey-task-0" },
        )
        assertTrue(
            scene.elements.filterIsInstance<ScenePath>()
                .any { it.id == "journey-activity-line" },
        )
    }

    @Test
    fun rendersTimelineDiagramThroughRegisteredPlugin() {
        val result = engine.render("timeline\n  2026 : Supported", context)

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any {
            shape -> shape.id == "timeline-node-0"
        })
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any {
            path -> path.id == "timeline-axis"
        })
    }

    @Test
    fun rendersPacketAndPacketBetaThroughRegisteredPlugin() {
        listOf("packet", "packet-beta").forEach { header ->
            val result = engine.render("$header\n  0-7: \"Header\"", context)

            val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
            assertTrue(scene.elements.filterIsInstance<SceneShape>().any {
                shape -> shape.id == "packet-0-0-block"
            })
            assertTrue(scene.elements.filterIsInstance<SceneText>().any {
                text -> text.text == "Header"
            })
        }
    }

    @Test
    fun rendersRadarThroughRegisteredPlugin() {
        val result = engine.render(
            """
                radar-beta
                  axis speed["Speed"], quality["Quality"], value["Value"]
                  curve current["Current"] { 70, 80, 90 }
                  max 100
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any {
            path -> path.id == "radar-axis-0"
        })
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any {
            path -> path.id == "radar-curve-0"
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any {
            text -> text.text == "Current"
        })
    }

    @Test
    fun rendersSankeyAndSankeyBetaThroughRegisteredPlugin() {
        listOf("sankey", "sankey-beta").forEach { header ->
            val result = engine.render("$header\nInput,Output,42", context)

            val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
            assertEquals(2, scene.elements.filterIsInstance<SceneShape>().size)
            assertEquals(1, scene.elements.filterIsInstance<ScenePath>().size)
            assertTrue(scene.elements.filterIsInstance<SceneText>().any {
                text -> text.text == "Input 42"
            })
        }
    }

    @Test
    fun rendersIshikawaAndIshikawaBetaThroughRegisteredPlugin() {
        listOf("ishikawa", "ishikawa-beta").forEach { header ->
            val result = engine.render(
                """
                    $header
                    Blurry Photo
                        Process
                            Out of focus
                        User
                            Shaky hands
                """.trimIndent(),
                context,
            )

            val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
            assertTrue(scene.elements.filterIsInstance<ScenePath>().any {
                path -> path.id == "ishikawa-spine"
            })
            assertTrue(scene.elements.filterIsInstance<SceneText>().any {
                text -> text.text == "Blurry Photo"
            })
        }
    }

    @Test
    fun rendersCynefinBetaThroughRegisteredPlugin() {
        val result = engine.render(
            """
                cynefin-beta
                  complex
                    "Emergent practice"
                  complicated
                    "Expert analysis"
                  complex --> complicated : "Pattern stabilizes"
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any { shape ->
            shape.id == "cynefin-confusion" && shape.kind == SceneShapeKind.Ellipse
        })
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any { path ->
            path.id == "cynefin-transition-0" &&
                path.arrowEnd == SceneArrowHead.Triangle
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "Emergent practice"
        })
    }

    @Test
    fun rendersCynefinColonHeaderThroughRegisteredPlugin() {
        val result = engine.render(
            """
                cynefin-beta:
                  clear
                    "Governed process"
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "Governed process"
        })
    }

    @Test
    fun rendersWardleyBetaThroughRegisteredPlugin() {
        val result = engine.render(
            """
                wardley-beta
                  title Strategic map
                  anchor Customer [0.9, 0.95]
                  component Service [0.7, 0.6]
                  Customer +> Service
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any { path ->
            path.id == "wardley-link-0" &&
                path.arrowEnd == SceneArrowHead.Triangle
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "Strategic map"
        })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "Customer"
        })
    }

    @Test
    fun rendersEventModelingThroughRegisteredPlugin() {
        val result = engine.render(
            """
                eventmodeling
                tf 01 ui CartUI
                tf 02 cmd AddItem
                tf 03 evt ItemAdded
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertEquals(
            3,
            scene.elements.filterIsInstance<SceneShape>().count { shape ->
                shape.id.startsWith("eventmodeling-swimlane-")
            },
        )
        assertEquals(
            3,
            scene.elements.filterIsInstance<SceneShape>().count { shape ->
                shape.id.startsWith("eventmodeling-box-")
            },
        )
        assertEquals(
            2,
            scene.elements.filterIsInstance<ScenePath>().count { path ->
                path.id.startsWith("eventmodeling-relation-")
            },
        )
    }

    @Test
    fun rendersBlockAndBlockBetaThroughRegisteredPlugin() {
        listOf("block", "block-beta").forEach { header ->
            val result = engine.render(
                """
                    $header
                      columns 2
                      source(("Source"))
                      target{"Target"}
                      source --> target
                """.trimIndent(),
                context,
            )

            val scene = assertIs<GMResult.Ok<MermaidScene>>(
                result,
                "$header: $result",
            ).value
            val shapes = scene.elements.filterIsInstance<SceneShape>()
            assertTrue(shapes.any { shape ->
                shape.id == "source" && shape.kind == SceneShapeKind.Circle
            })
            assertTrue(shapes.any { shape ->
                shape.id == "target" && shape.kind == SceneShapeKind.Diamond
            })
            assertTrue(scene.elements.filterIsInstance<ScenePath>().any { path ->
                path.id == "1-source-target"
            })
        }
    }

    @Test
    fun rendersAgentflowBetaThroughRegisteredPlugin() {
        val result = engine.render(
            """
                agentflow-beta LR
                  input["Question"]@{ shape: input }
                  tool["Search"]@{ shape: tool }
                  input --> tool
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any { shape ->
            shape.id == "input" && shape.kind == SceneShapeKind.Parallelogram
        })
        assertTrue(scene.elements.filterIsInstance<SceneShape>().any { shape ->
            shape.id == "tool" && shape.kind == SceneShapeKind.Subroutine
        })
        assertTrue(scene.elements.filterIsInstance<ScenePath>().any { path ->
            path.arrowEnd == SceneArrowHead.Triangle
        })
    }

    @Test
    fun rejectsEffectiveHandDrawnLookForExistingDiagramTypes() {
        val result = engine.render(
            """
                ---
                config:
                  look: handDrawn
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
            context,
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.UnsupportedFeature>(error)
        assertEquals("handDrawn look", error.feature)
    }

    @Test
    fun returnsStructuredErrorWhenCleanedFlowchartExceedsHostTextLimit() {
        val source = "flowchart LR\n A[${"x".repeat(80)}]"
        val result = engine.render(
            source,
            context.copy(
                options = context.options.copy(maxTextSize = 40),
            ),
        )

        val error = assertIs<MermaidError.ResourceLimit>(
            assertIs<GMResult.Err<MermaidError>>(result).error,
        )
        assertEquals("maxTextSize", error.resource)
        assertEquals(source.length, error.actual)
        assertEquals(40, error.maximum)
    }

    @Test
    fun excludesFlowchartCommentsFromHostTextLimitLikeMermaid12() {
        val result = engine.render(
            "%% ${"comment".repeat(30)}\nflowchart LR\n A --> B",
            context.copy(
                options = context.options.copy(maxTextSize = 30),
            ),
        )

        assertIs<GMResult.Ok<MermaidScene>>(result, result.toString())
    }

    @Test
    fun sanitizesInteractionUrlsButPreservesImageSourcesLikeFlowDb() {
        val result = engine.render(
            """
                flowchart LR
                  A@{ img: "data:text/html,payload", label: "Image" }
                  B[Link]
                  click B "javascript:alert(1)"
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val image = scene.elements.filterIsInstance<SceneAsset>().single()
        val interaction = scene.interactions.single()

        assertEquals("data:text/html,payload", image.source)
        assertEquals("about:blank", interaction.link)
    }

    @Test
    fun ignoresInvalidCssColorLikeTheSvgRenderer() {
        val result = engine.render(
            """
                flowchart TB
                  A --> B
                  style A fill:not-a-color
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val node = scene.elements.filterIsInstance<SceneShape>().first { it.id == "A" }
        assertEquals(context.theme.nodeFill, node.fill)
    }

    @Test
    fun appliesFlowchartThemeVariablesFromFrontmatter() {
        val result = engine.render(
            """
                ---
                config:
                  themeVariables:
                    mainBkg: "#112233"
                    nodeBorder: "rgb(68, 85, 102)"
                    nodeTextColor: white
                    defaultLinkColor: "#778899"
                    fontSize: 18px
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val node = scene.elements.filterIsInstance<SceneShape>().first { it.id == "A" }
        val edge = scene.elements.filterIsInstance<ScenePath>().single()

        assertEquals(SceneColor(0xFF112233), node.fill)
        assertEquals(SceneColor(0xFF445566), node.stroke)
        assertEquals(SceneColor(0xFF778899), edge.color)
        assertTrue(
            scene.elements
                .filterIsInstance<SceneText>()
                .filter { it.text in setOf("A", "B") }
                .all {
                    it.color == SceneColor(0xFFFFFFFF) &&
                        it.fontSize == 18f
                },
        )
    }

    @Test
    fun assignsExplicitMultiNodeEdgeIdOnlyToLastStartAndFirstEnd() {
        val result = engine.render(
            """
                flowchart LR
                  A:::source & B:::source e1@--> C & D
                  e1@{ animate: true }
                  classDef source fill:#e0f2fe,stroke:#0369a1
                  class e1 source
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val nodes = scene.elements.filterIsInstance<SceneShape>().filter { it.id in setOf("A", "B", "C", "D") }
        val paths = scene.elements.filterIsInstance<ScenePath>()
        assertEquals(4, nodes.size)
        assertEquals(4, paths.size)
        assertEquals(1, paths.count { it.id == "e1" })
    }

    @Test
    fun supportsLongInvisibleAndBidirectionalEdges() {
        val result = engine.render(
            """
                flowchart LR
                  A ----> B
                  B ~~~ C
                  C o--x D
                  D <==> E
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val paths = scene.elements.filterIsInstance<ScenePath>()
        assertEquals(3, paths.size)
        assertTrue(paths.any { it.arrowStart == SceneArrowHead.None && it.arrowEnd == SceneArrowHead.Cross })
        assertTrue(paths.any { it.arrowStart == SceneArrowHead.Triangle && it.arrowEnd == SceneArrowHead.Triangle })
    }

    @Test
    fun supportsMarkerCombinationsAcrossStrokeTypes() {
        val cases = listOf(
            "A x--x B" to Triple(SceneArrowHead.Cross, SceneArrowHead.Cross, SceneStrokePattern.Solid),
            "A o==o B" to Triple(SceneArrowHead.Circle, SceneArrowHead.Circle, SceneStrokePattern.Solid),
            "A <-.-> B" to Triple(SceneArrowHead.Triangle, SceneArrowHead.Triangle, SceneStrokePattern.Dotted),
            "A x-. label .-x B" to Triple(SceneArrowHead.Cross, SceneArrowHead.Cross, SceneStrokePattern.Dotted),
            "A o== label ==o B" to Triple(SceneArrowHead.Circle, SceneArrowHead.Circle, SceneStrokePattern.Solid),
            "A <-- label --> B" to Triple(SceneArrowHead.Triangle, SceneArrowHead.Triangle, SceneStrokePattern.Solid),
        )

        cases.forEach { (edgeSource, expected) ->
            val result = engine.render("flowchart LR\n  $edgeSource", context)
            val scene = assertIs<GMResult.Ok<MermaidScene>>(result, edgeSource).value
            val path = scene.elements.filterIsInstance<ScenePath>().single()
            assertEquals(expected.first, path.arrowStart, edgeSource)
            assertEquals(expected.second, path.arrowEnd, edgeSource)
            assertEquals(expected.third, path.strokePattern, edgeSource)
        }
    }

    @Test
    fun matchesMermaid12FlowchartDefaultEdgeWidths() {
        val result = engine.render(
            """
                flowchart LR
                  A --> B
                  B ==> C
            """.trimIndent(),
            context,
        )

        val paths = assertIs<GMResult.Ok<MermaidScene>>(result).value
            .elements
            .filterIsInstance<ScenePath>()

        assertEquals(2f, paths[0].strokeWidth)
        assertEquals(3.5f, paths[1].strokeWidth)
    }

    @Test
    fun usesMermaid12FlowchartAppearanceWhenNoThemeIsSpecified() {
        val result = engine.render(
            """
                flowchart LR
                  subgraph group [Group]
                    A --> B
                  end
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val nodeText = scene.elements
            .filterIsInstance<SceneText>()
            .first { it.text == "A" }

        assertNull(MermaidRenderOptions().fontSize)
        assertEquals(14f, nodeText.fontSize)
        assertEquals("\"Recursive Variable\", arial, sans-serif", nodeText.fontFamily)
        assertEquals(SceneTextWeight.Normal, nodeText.weight)
        assertEquals(SceneColor(0xFFFFFFFF), shapes.getValue("A").fill)
        assertEquals(SceneColor(0xFF28253D), shapes.getValue("A").stroke)
        assertEquals(SceneColor(0xFF28253D), nodeText.color)
        assertEquals(2f, shapes.getValue("A").strokeWidth)
        assertEquals(SceneColor(0xFFFDF4FF), shapes.getValue("subgraph_group").fill)
        assertEquals(SceneColor(0xFFE879F9), shapes.getValue("subgraph_group").stroke)
        assertEquals(MermaidTheme.FlowchartDefault.dropShadow, shapes.getValue("A").shadow)
    }

    @Test
    fun usesClassicDefaultThemeVariablesForQuotedNullTheme() {
        val result = engine.render(
            """
                ---
                config:
                  flowchart:
                    theme: "null"
                ---
                flowchart LR
                  subgraph group [Group]
                    A --> B
                  end
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val nodeText = scene.elements
            .filterIsInstance<SceneText>()
            .first { it.text == "A" }

        assertEquals(SceneColor(0xFFECECFF), shapes.getValue("A").fill)
        assertEquals(SceneColor(0xFF9370DB), shapes.getValue("A").stroke)
        assertEquals(1f, shapes.getValue("A").strokeWidth)
        assertEquals(SceneColor(0xFF131300), nodeText.color)
        assertEquals(16f, nodeText.fontSize)
        assertEquals("\"trebuchet ms\", verdana, arial, sans-serif", nodeText.fontFamily)
        assertEquals(SceneColor(0xFFFFFFDE), shapes.getValue("subgraph_group").fill)
        assertEquals(SceneColor(0xFFAAAA33), shapes.getValue("subgraph_group").stroke)
    }

    @Test
    fun appliesExplicitMermaidReduxColorTheme() {
        val result = engine.render(
            """
                ---
                config:
                  theme: redux-color
                ---
                flowchart LR
                  subgraph first [First]
                    A --> B
                  end
                  subgraph second [Second]
                    C --> D
                  end
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val edge = scene.elements.filterIsInstance<ScenePath>().first()

        assertEquals(SceneColor(0xFFFFFFFF), shapes.getValue("A").fill)
        assertEquals(SceneColor(0xFF28253D), shapes.getValue("A").stroke)
        assertEquals(2f, shapes.getValue("A").strokeWidth)
        assertEquals(2f, edge.strokeWidth)
        assertEquals(SceneColor(0xFFFDF4FF), shapes.getValue("subgraph_first").fill)
        assertEquals(SceneColor(0xFFE879F9), shapes.getValue("subgraph_first").stroke)
        assertEquals(SceneColor(0xFFF0FDFA), shapes.getValue("subgraph_second").fill)
        assertEquals(SceneColor(0xFF2DD4BF), shapes.getValue("subgraph_second").stroke)
        assertEquals(
            SceneShadow(
                color = SceneColor(0x0F000000),
                offsetX = 4f,
                offsetY = 4f,
            ),
            shapes.getValue("A").shadow,
        )
        assertEquals(shapes.getValue("A").shadow, shapes.getValue("subgraph_first").shadow)
    }

    @Test
    fun appliesVennAppearanceDefaultWithoutOverridingExplicitTheme() {
        var defaultContext: MermaidRenderContext? = null
        var explicitContext: MermaidRenderContext? = null
        val capturingPlugin = object : MermaidDiagramPlugin {
            override val id: String = "venn"
            override val headers: Set<String> = setOf("venn-beta")

            override fun compile(
                source: String,
                context: MermaidRenderContext,
            ): GMResult<MermaidScene, MermaidError> {
                if (context.options.themeName == "default") {
                    explicitContext = context
                } else {
                    defaultContext = context
                }
                return GMResult.Ok(
                    MermaidScene(
                        width = 1f,
                        height = 1f,
                        background = context.theme.background,
                        elements = emptyList(),
                    ),
                )
            }
        }
        val vennEngine = MermaidEngine(listOf(capturingPlugin))

        assertIs<GMResult.Ok<MermaidScene>>(
            vennEngine.render("venn-beta\nset A", context),
        )
        assertIs<GMResult.Ok<MermaidScene>>(
            vennEngine.render(
                """
                    ---
                    config:
                      theme: default
                      look: classic
                    ---
                    venn-beta
                    set A
                """.trimIndent(),
                context,
            ),
        )

        assertEquals("redux-color", defaultContext?.options?.themeName)
        assertEquals("neo", defaultContext?.options?.look)
        assertEquals("default", explicitContext?.options?.themeName)
        assertEquals("classic", explicitContext?.options?.look)
    }

    @Test
    fun scopesAgentflowAppearanceAndLayoutConfiguration() {
        val capturedContexts = mutableListOf<MermaidRenderContext>()
        val capturingPlugin = object : MermaidDiagramPlugin {
            override val id: String = "agentflow"
            override val headers: Set<String> = setOf("agentflow-beta")

            override fun compile(
                source: String,
                context: MermaidRenderContext,
            ): GMResult<MermaidScene, MermaidError> {
                capturedContexts += context
                return GMResult.Ok(
                    MermaidScene(
                        width = 1f,
                        height = 1f,
                        background = context.theme.background,
                        elements = emptyList(),
                    ),
                )
            }
        }
        val agentflowEngine = MermaidEngine(listOf(capturingPlugin))

        assertIs<GMResult.Ok<MermaidScene>>(
            agentflowEngine.render("agentflow-beta TB\n  a --> b", context),
        )
        assertIs<GMResult.Ok<MermaidScene>>(
            agentflowEngine.render(
                """
                    ---
                    config:
                      theme: dark
                      look: classic
                      flowchart:
                        nodeSpacing: 999
                        rankSpacing: 998
                        wrappingWidth: 997
                        minNodeWidth: 996
                      agentflow:
                        theme: forest
                        look: neo
                        titleTopMargin: 31
                        diagramPadding: 17
                        nodeSpacing: 71
                        rankSpacing: 73
                        wrappingWidth: 175
                        minNodeWidth: 145
                    ---
                    agentflow-beta LR
                      a --> b
                """.trimIndent(),
                context,
            ),
        )

        val defaults = capturedContexts[0].options
        assertEquals("redux-color", defaults.themeName)
        assertEquals("neo", defaults.look)
        assertEquals(25f, defaults.titleTopMargin)
        assertEquals(8f, defaults.diagramPadding)
        assertEquals(50f, defaults.nodeSpacing)
        assertEquals(50f, defaults.rankSpacing)
        assertEquals(120f, defaults.wrappingWidth)
        assertEquals(120f, defaults.minNodeWidth)

        val configured = capturedContexts[1].options
        assertEquals("forest", configured.themeName)
        assertEquals("neo", configured.look)
        assertEquals(31f, configured.titleTopMargin)
        assertEquals(17f, configured.diagramPadding)
        assertEquals(71f, configured.nodeSpacing)
        assertEquals(73f, configured.rankSpacing)
        assertEquals(175f, configured.wrappingWidth)
        assertEquals(145f, configured.minNodeWidth)
    }

    @Test
    fun appliesConfiguredFontFamilyToNodesAndEdgeLabels() {
        val result = engine.render(
            """
                ---
                config:
                  fontFamily: "Arial, sans-serif"
                  themeVariables:
                    fontFamily: "monospace"
                ---
                flowchart LR
                  A -->|label| B
            """.trimIndent(),
            context,
        )

        val texts = assertIs<GMResult.Ok<MermaidScene>>(result).value
            .elements
            .filterIsInstance<SceneText>()

        assertTrue(texts.isNotEmpty())
        assertTrue(texts.all { text -> text.fontFamily == "monospace" })
        assertEquals(14f, texts.first { text -> text.text == "label" }.fontSize)
    }

    @Test
    fun appliesDropShadowOnlyToNeoNodesAndSubgraphs() {
        val result = engine.render(
            """
                ---
                config:
                  look: classic
                ---
                flowchart LR
                  subgraph group [Group]
                    A --> B
                  end
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)

        assertNull(shapes.getValue("A").shadow)
        assertNull(shapes.getValue("B").shadow)
        assertNull(shapes.getValue("subgraph_group").shadow)
    }

    @Test
    fun honorsDropShadowThemeVariable() {
        val result = engine.render(
            """
                ---
                config:
                  themeVariables:
                    dropShadow: "drop-shadow(2px 3px 4px rgba(10, 20, 30, 0.5))"
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val node = scene.elements.filterIsInstance<SceneShape>().first { it.id == "A" }

        assertEquals(
            SceneShadow(
                color = SceneColor(0x800A141E),
                offsetX = 2f,
                offsetY = 3f,
                blurRadius = 4f,
            ),
            node.shadow,
        )
    }

    @Test
    fun appliesMermaid12SubgraphTitleMarginsToDagre() {
        fun render(layout: String, top: Float, bottom: Float): MermaidScene {
            val result = engine.render(
                """
                    flowchart TB
                      subgraph group [Group]
                        A -->|label| B
                      end
                """.trimIndent(),
                context.copy(
                    options = context.options.copy(
                        layout = layout,
                        subGraphTitleTopMargin = top,
                        subGraphTitleBottomMargin = bottom,
                    ),
                ),
            )
            return assertIs<GMResult.Ok<MermaidScene>>(result).value
        }

        val dagreDefault = render("dagre", 0f, 0f)
        val dagreMargin = render("dagre", 10f, 5f)
        val dagreDefaultShapes = dagreDefault.elements
            .filterIsInstance<SceneShape>()
            .associateBy(SceneShape::id)
        val dagreMarginShapes = dagreMargin.elements
            .filterIsInstance<SceneShape>()
            .associateBy(SceneShape::id)
        val dagreDefaultGroup = dagreDefaultShapes.getValue("subgraph_group")
        val dagreMarginGroup = dagreMarginShapes.getValue("subgraph_group")
        val dagreDefaultNode = dagreDefaultShapes.getValue("A")
        val dagreMarginNode = dagreMarginShapes.getValue("A")
        val dagreMarginTitle = dagreMargin.elements
            .filterIsInstance<SceneText>()
            .first { it.text == "Group" }

        assertEquals(
            15f,
            dagreMarginGroup.bounds.height - dagreDefaultGroup.bounds.height,
            0.01f,
        )
        assertEquals(
            15f,
            (dagreMarginNode.bounds.top - dagreMarginGroup.bounds.top) -
                (dagreDefaultNode.bounds.top - dagreDefaultGroup.bounds.top),
            0.01f,
        )
        assertEquals(
            10f,
            dagreMarginTitle.bounds.top - dagreMarginGroup.bounds.top,
            0.01f,
        )
    }

    @Test
    fun appliesThemeColorArrayOverridesToFlowchartSlots() {
        val result = engine.render(
            """
                ---
                config:
                  themeVariables:
                    bkgColorArray: ["#112233", "#445566"]
                    borderColorArray: ["#778899", "#aabbcc"]
                ---
                flowchart LR
                  subgraph first [First]
                    A
                  end
                  subgraph second [Second]
                    B
                  end
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)

        assertEquals(SceneColor(0xFF112233), shapes.getValue("subgraph_first").fill)
        assertEquals(SceneColor(0xFF778899), shapes.getValue("subgraph_first").stroke)
        assertEquals(SceneColor(0xFF445566), shapes.getValue("subgraph_second").fill)
        assertEquals(SceneColor(0xFFAABBCC), shapes.getValue("subgraph_second").stroke)
    }

    @Test
    fun appliesExplicitDefaultTheme() {
        val result = engine.render(
            """
                ---
                config:
                  theme: default
                ---
                flowchart LR
                  subgraph group [Group]
                    A --> B
                  end
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val nodeText = scene.elements
            .filterIsInstance<SceneText>()
            .first { it.text == "A" }

        assertEquals(SceneColor(0xFFECECFF), shapes.getValue("A").fill)
        assertEquals(SceneColor(0xFF9370DB), shapes.getValue("A").stroke)
        assertEquals(1f, shapes.getValue("A").strokeWidth)
        assertEquals(SceneColor(0xFFFFFFDE), shapes.getValue("subgraph_group").fill)
        assertEquals(SceneColor(0xFFAAAA33), shapes.getValue("subgraph_group").stroke)
        assertEquals(16f, nodeText.fontSize)
    }

    @Test
    fun honorsDagreMinimumLengthRanks() {
        val result = engine.render(
            """
                flowchart TD
                  A --> B
                  A ---> C
                  A ----> D
                  B -.-> E
                  C -..-> E
                  D ====> E
            """.trimIndent(),
            context,
        )

        val nodes = assertIs<GMResult.Ok<MermaidScene>>(result).value
            .elements
            .filterIsInstance<SceneShape>()
            .filter { shape -> shape.id in setOf("A", "B", "C", "D", "E") }
            .associateBy(SceneShape::id)

        val centers = nodes.mapValues { it.value.bounds.center.y }
        assertTrue(centers.getValue("A") < centers.getValue("D"))
        assertTrue(centers.getValue("D") < centers.getValue("C"))
        assertTrue(centers.getValue("C") < centers.getValue("B"))
        assertTrue(centers.getValue("B") < centers.getValue("E"))
    }

    @Test
    fun keepsMultiNodeLinksDistinctWithDagreRoutes() {
        val result = engine.render(
            """
                flowchart LR
                  A eAC@--> C
                  A eAD@--> D
                  B eBC@--> C
                  B eBD@--> D
            """.trimIndent(),
            context,
        )

        val paths = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
            .elements
            .filterIsInstance<ScenePath>()
            .associateBy(ScenePath::id)

        assertEquals(setOf("eAC", "eAD", "eBC", "eBD"), paths.keys)
        assertTrue(paths.values.all { it.points.size >= 3 })
        assertEquals(4, paths.values.map(ScenePath::points).toSet().size)
    }

    @Test
    fun keepsMixedEdgeLabelsSeparated() {
        val result = engine.render(
            """
                flowchart LR
                  A -- text --> B
                  A -->|pipe label| C
                  A -. dotted .-> D
                  A == thick ==> E
                  A -- open text --- F
            """.trimIndent(),
            context,
        )

        val labels = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
            .elements
            .filterIsInstance<SceneText>()
            .filter { text ->
                text.text in setOf("text", "pipe label", "dotted", "thick", "open text")
            }
            .sortedBy { text -> text.bounds.top }

        assertEquals(5, labels.size)
        labels.zipWithNext().forEach { (first, second) ->
            assertTrue(first.bounds.bottom <= second.bounds.top, "${first.text} overlaps ${second.text}")
        }
    }

    @Test
    fun supportsCssColorsAndTextStyles() {
        val result = engine.render(
            """
                flowchart LR
                  A --> B
                  style A fill:#ff000080,stroke:#008000,color:white,stroke-dasharray:5 5,font-size:20px,font-weight:bold
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val shape = scene.elements.filterIsInstance<SceneShape>().first { it.id == "A" }
        val text = scene.elements.filterIsInstance<SceneText>().first { it.text == "A" }
        assertEquals(SceneColor(0x80FF0000), shape.fill)
        assertEquals(SceneColor(0xFF008000), shape.stroke)
        assertEquals(SceneStrokePattern.Dashed, shape.strokePattern)
        assertEquals(20f, text.fontSize)
        assertEquals(SceneTextWeight.Bold, text.weight)
    }

    @Test
    fun supportsRepresentableFlowchartLabelCss() {
        val result = engine.render(
            """
                flowchart LR
                  A[Styled label] --> B
                  style A font-style:italic,text-decoration:underline line-through,line-height:2,text-align:left
            """.trimIndent(),
            context,
        )

        val text = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
            .elements
            .filterIsInstance<SceneText>()
            .first { it.text == "Styled label" }
        val styleSpan = text.spans.first { it.start == 0 && it.end == text.text.length }

        assertEquals(2f, text.lineHeight)
        assertEquals(SceneTextAlignment.Start, text.horizontalAlignment)
        assertEquals(true, styleSpan.italic)
        assertEquals(true, styleSpan.underline)
        assertEquals(true, styleSpan.lineThrough)
    }

    @Test
    fun matchesMermaidHtmlLabelRelativeFontSizeCascadeAndIgnoresSvgBorder() {
        val result = engine.render(
            """
                flowchart LR
                  A[Small] --> B
                  style A font-size:50%,font-style:bold,border:5px solid red
            """.trimIndent(),
            context,
        )

        val text = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
            .elements
            .filterIsInstance<SceneText>()
            .first { it.text == "Small" }

        assertEquals(1.75f, text.fontSize, 0.001f)
    }

    @Test
    fun routesParallelEdgesOnDistinctLanesWithoutLabelOverlap() {
        val result = engine.render(
            """
                flowchart LR
                  A -->|primary| B
                  A -.->|retry| B
                  A ==>|priority| B
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val labels = scene.elements
            .filterIsInstance<SceneText>()
            .filter { it.text in setOf("primary", "retry", "priority") }
            .sortedBy { it.bounds.top }

        assertEquals(3, labels.size)
        labels.zipWithNext().forEach { (first, second) ->
            assertTrue(
                first.bounds.bottom <= second.bounds.top,
                "${first.text} overlaps ${second.text}",
            )
        }
    }

    @Test
    fun placesHorizontalSelfLoopOnDagreSelectedSide() {
        val result = engine.render(
            """
                flowchart LR
                  A --> A
                  A --> B
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val node = scene.elements
            .filterIsInstance<SceneShape>()
            .first { shape -> shape.id == "A" }
        val loop = scene.elements
            .filterIsInstance<ScenePath>()
            .first { path -> path.id == "L_A_A_0" }

        assertEquals(4, loop.points.size)
        assertEquals(node.bounds.right, loop.points.first().x)
        assertEquals(node.bounds.right, loop.points.last().x)
        assertTrue(loop.points.drop(1).dropLast(1).all { point -> point.x > node.bounds.right })
    }

    @Test
    fun preservesMultipleDagreSelfLoopsOnTheSameNode() {
        val result = engine.render(
            """
                flowchart LR
                  A -->|first| A
                  A -.->|second| A
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val loops = scene.elements
            .filterIsInstance<ScenePath>()
            .filter { path -> path.id.startsWith("L_A_A_") }

        assertEquals(2, loops.size)
        assertTrue(loops.all { loop -> loop.points.size == 4 })
    }

    @Test
    fun doesNotApplyElkLineJumpsToDagreCrossings() {
        val result = engine.render(
            """
                flowchart TB
                  Start --> A & B & C
                  A --> D
                  B --> E
                  C --> F
                  A --> F
                  C --> D
                  D & E & F --> Finish
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val paths = scene.elements.filterIsInstance<ScenePath>()
        assertEquals(11, paths.size)
    }

    @Test
    fun connectsEdgesToExpandedSubgraphBoundsWithoutFakeNodes() {
        val result = engine.render(
            """
                flowchart LR
                  Start --> group
                  subgraph group [Validation]
                    A --> B
                  end
                  group --> Finish
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        assertTrue(shapes.any { it.id == "subgraph_group" })
        assertTrue(shapes.none { it.id == "group" })
        assertEquals(3, scene.elements.filterIsInstance<ScenePath>().size)
    }

    @Test
    fun honorsExplicitDirectionsInNestedSubgraphs() {
        val result = engine.render(
            """
                flowchart LR
                  subgraph TOP
                    direction TB
                    subgraph B1
                      direction RL
                      i1 --> f1
                    end
                    subgraph B2
                      direction BT
                      i2 --> f2
                    end
                  end
                  A --> TOP --> B
                  B1 --> B2
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val top = shapes.getValue("subgraph_TOP").bounds
        val firstGroup = shapes.getValue("subgraph_B1").bounds
        val secondGroup = shapes.getValue("subgraph_B2").bounds

        assertTrue(shapes.getValue("A").bounds.right < top.left)
        assertTrue(top.right < shapes.getValue("B").bounds.left)
        assertTrue(firstGroup.bottom < secondGroup.top)
        assertTrue(shapes.getValue("f1").bounds.right < shapes.getValue("i1").bounds.left)
        assertTrue(shapes.getValue("f2").bounds.bottom < shapes.getValue("i2").bounds.top)

        val paths = scene.elements.filterIsInstance<ScenePath>().associateBy(ScenePath::id)
        assertTrue(paths.getValue("L_i1_f1_0").points.first().x > paths.getValue("L_i1_f1_0").points.last().x)
        assertTrue(paths.getValue("L_i2_f2_0").points.first().y > paths.getValue("L_i2_f2_0").points.last().y)
        assertTrue(paths.getValue("L_B1_B2_0").points.first().y < paths.getValue("L_B1_B2_0").points.last().y)
    }

    @Test
    fun collapsedSubgraphBecomesSingleNodeAndRedirectsBoundaryEdges() {
        val result = engine.render(
            """
                flowchart LR
                  Start --> group
                  subgraph group [Validation]
                    A --> B --> C
                  end
                  group --> Finish
                  group@{ view: collapsed }
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val collapsed = shapes.single {
            it.id == "group" && it.kind == SceneShapeKind.CollapsedGroup
        }
        val collapsedPaths = collapsed.geometry?.paths.orEmpty()
        val separator = collapsedPaths[1]
        val indicators = collapsedPaths.drop(2)
        assertEquals(0.75f, separator.strokeWidth)
        assertEquals(listOf(3f, 3f), separator.dashIntervals)
        assertEquals(3, indicators.size)
        assertTrue(indicators.all { it.fill == SceneShapePaint.Stroke })
        assertTrue(indicators.all { it.stroke == SceneShapePaint.Stroke })
        assertTrue(indicators.all { it.strokeWidth == 2f })
        assertTrue(indicators.all { it.strokeColor == context.theme.nodeStroke })
        assertTrue(indicators.all { it.opacity == 0.6f })
        assertTrue(shapes.none { it.id in setOf("A", "B", "C", "subgraph_group") })
        assertEquals(2, scene.elements.filterIsInstance<ScenePath>().size)
    }

    @Test
    fun rendersEveryOfficialComparisonCase() {
        val failures = officialFlowchartCases.mapNotNull { case ->
            when (val result = engine.render(case.source, context)) {
                is GMResult.Ok -> when {
                    result.value.elements.filterIsInstance<SceneShape>().isEmpty() ->
                        "${case.id}: no shapes"
                    result.value.width <= 0f || result.value.height <= 0f ->
                        "${case.id}: invalid scene bounds"
                    else -> null
                }
                is GMResult.Err -> "${case.id}: ${result.error.message}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(separator = "\n", prefix = "Official cases failed:\n"),
        )
    }

    @Test
    fun preservesStoredDataShapeAndLabel() {
        val scene = renderCase("expanded_storage_shapes")
        val node = scene.elements.filterIsInstance<SceneShape>().first { it.id == "D" }
        assertEquals(SceneShapeKind.BowTieRectangle, node.kind)
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { it.text == "Stored data" })
    }

    @Test
    fun keepsLinedCylinderInnerArcBelowItsEndpoints() {
        val scene = renderCase("expanded_storage_shapes")
        val disk = scene.elements.filterIsInstance<SceneShape>().first { it.id == "B" }
        val geometry = assertIs<SceneShapeGeometry>(disk.geometry)
        val innerArc = geometry.paths.last().points

        assertEquals(SceneShapeKind.LinedCylinder, disk.kind)
        assertEquals(3, geometry.paths.size)
        assertEquals(innerArc.first().y, innerArc.last().y, 0.01f)
        assertTrue(
            innerArc[innerArc.size / 2].y > innerArc.first().y,
            "Mermaid's lined-cylinder inner arc must bow down into the cylinder.",
        )
    }

    @Test
    fun keepsOddShapeNotchPointingIntoTheNode() {
        val scene = renderCase("expanded_control_shapes")
        val odd = scene.elements.filterIsInstance<SceneShape>().first { it.id == "A" }
        val outline = assertIs<SceneShapeGeometry>(odd.geometry).outline

        assertEquals(5, outline.size)
        assertTrue(
            outline[0].x < outline[1].x,
            "The middle of the odd shape notch must point into the node.",
        )
        assertEquals(outline[0].x, outline[2].x, 0.01f)
        assertEquals(outline[3].x, outline[4].x, 0.01f)
    }

    @Test
    fun preservesAnimatedEdgeDashPatternInStaticPreview() {
        val scene = renderCase("edge_ids_and_length")
        assertEquals(
            SceneStrokePattern.Dashed,
            scene.elements.filterIsInstance<ScenePath>().first { it.id == "e1" }.strokePattern,
        )
        assertTrue(
            scene.elements.filterIsInstance<SceneShape>().none { it.id == "e1" },
            "A user-defined edge ID must not also create a node.",
        )
    }

    @Test
    fun keepsStadiumCapsConvexOutward() {
        val scene = renderCase("metadata_shapes")
        val stadium = scene.elements.filterIsInstance<SceneShape>().first { it.id == "A" }
        val outline = assertIs<SceneShapeGeometry>(stadium.geometry).outline
        val rightArc = outline.subList(2, 52)
        val leftArc = outline.drop(53)

        assertTrue(
            rightArc.all { it.x >= rightArc.first().x - 0.01f },
            "The right stadium cap must curve outward.",
        )
        assertTrue(
            leftArc.all { it.x <= leftArc.first().x + 0.01f },
            "The left stadium cap must curve outward.",
        )
    }

    @Test
    fun appliesEffectiveFlowchartCssFillToSpecialShapes() {
        val result = engine.render(
            """
                flowchart LR
                  A@{ shape: sm-circ } --> B@{ shape: fork }
                  B --> C@{ shape: f-circ }
            """.trimIndent(),
            context,
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val start = assertIs<SceneShapeGeometry>(shapes.getValue("A").geometry)
        val fork = assertIs<SceneShapeGeometry>(shapes.getValue("B").geometry)
        val junction = assertIs<SceneShapeGeometry>(shapes.getValue("C").geometry)

        assertEquals(SceneShapePaint.Fill, start.paths.single().fill)
        assertEquals(SceneShapePaint.Fill, fork.paths.single().fill)
        assertEquals(SceneShapePaint.Stroke, junction.paths.single().fill)
    }

    @Test
    fun enclosesNestedSubgraphsBelowParentTitle() {
        val scene = renderCase("nested_subgraphs")
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val outer = shapes.getValue("subgraph_outer").bounds
        val title = scene.elements.filterIsInstance<SceneText>().first { it.text == "Rendering engine" }
        for (id in listOf("subgraph_syntax", "subgraph_visual")) {
            val child = shapes.getValue(id).bounds
            assertTrue(outer.left < child.left && outer.right > child.right, id)
            assertTrue(
                title.bounds.bottom < child.top,
                "$id overlaps parent title: outer=$outer, title=${title.bounds}, child=$child",
            )
            assertTrue(outer.bottom > child.bottom, id)
        }
    }

    @Test
    fun connectsDecisionEdgesToActualOutlineInEveryDirection() {
        for (direction in listOf("TB", "BT", "LR", "RL")) {
            val scene = assertIs<GMResult.Ok<MermaidScene>>(
                engine.render("flowchart $direction\n A{Decision} --> B & C", context),
            ).value
            val diamond = scene.elements.filterIsInstance<SceneShape>().first { it.id == "A" }.bounds
            assertEquals(diamond.width, diamond.height, 0.01f)
            for (path in scene.elements.filterIsInstance<ScenePath>()) {
                val start = path.points.first()
                val outlineDistance = kotlin.math.abs(start.x - diamond.center.x) / (diamond.width / 2f) +
                    kotlin.math.abs(start.y - diamond.center.y) / (diamond.height / 2f)
                assertEquals(1f, outlineDistance, 0.01f, "$direction: ${path.id}")
            }
        }
    }

    @Test
    fun reservesLabelSpaceAwayFromShapeDecorations() {
        val scene = renderCase("expanded_manual_shapes")
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val labels = scene.elements.filterIsInstance<SceneText>().associateBy(SceneText::text)
        val divided = shapes.getValue("B").bounds
        assertTrue(labels.getValue("Divided").bounds.top >= divided.top + divided.height / 6f)
        val triangle = shapes.getValue("C").bounds
        assertTrue(labels.getValue("Extract").bounds.center.y > triangle.center.y)
        val flipped = shapes.getValue("G").bounds
        assertTrue(labels.getValue("Manual file").bounds.center.y < flipped.center.y)
    }

    @Test
    fun measuresNodeTextWithResolvedClassAndInlineStyles() {
        val requests = mutableListOf<TextMetricsRequest>()
        val measuringContext = MermaidRenderContext(
            textMetrics = TextMetricProvider { request ->
                requests += request
                TextMetrics(request.text.length * request.fontSize, request.fontSize * 2f)
            },
            options = MermaidRenderOptions(layout = "dagre"),
        )
        val scene = assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(
                "flowchart LR\n A[Wide label]\n " +
                    "classDef large font-size:32px,font-weight:bold,font-family:monospace\n " +
                    "class A large",
                measuringContext,
            ),
        ).value
        val request = requests.first { it.text == "Wide label" }
        assertEquals(32f, request.fontSize)
        assertEquals("monospace", request.fontFamily)
        assertEquals(SceneTextWeight.Bold, request.weight)
        val label = scene.elements.filterIsInstance<SceneText>().first { it.text == "Wide label" }
        assertTrue(label.bounds.height >= 64f)
    }

    @Test
    fun routesQuestionWorkflowWithoutCrossingUnrelatedNodes() {
        val scene = renderCase("question_workflow")
        val endpoints = mapOf(
            "L_A_B_0" to ("A" to "B"),
            "L_B_C_0" to ("B" to "C"),
            "L_C_A_0" to ("C" to "A"),
            "L_B_D_0" to ("B" to "D"),
            "L_D_E_0" to ("D" to "E"),
            "L_E_F_0" to ("E" to "F"),
        )
        val nodes = scene.elements.filterIsInstance<SceneShape>().filter { it.id in setOf("A", "B", "C", "D", "E", "F") }
        for (path in scene.elements.filterIsInstance<ScenePath>()) {
            val (from, to) = endpoints.getValue(path.id)
            for (node in nodes.filter { it.id != from && it.id != to }) {
                assertTrue(
                    path.points.zipWithNext().none { (start, end) -> crossesInterior(start, end, node.bounds) },
                    "${path.id} ($from -> $to) crosses ${node.id}: ${path.points}",
                )
            }
        }
    }

    @Test
    fun returnsCycleEdgeToActualOutlineFromOutside() {
        val scene = renderCase("self_loop_and_cycle")
        val node = scene.elements.filterIsInstance<SceneShape>().first { it.id == "A" }.bounds
        val path = scene.elements.filterIsInstance<ScenePath>().first { it.id == "L_C_A_0" }
        val end = path.points.last()
        val previous = path.points[path.points.lastIndex - 1]
        val onOutline =
            kotlin.math.abs(end.x - node.left) < 0.01f ||
                kotlin.math.abs(end.x - node.right) < 0.01f ||
                kotlin.math.abs(end.y - node.top) < 0.01f ||
                kotlin.math.abs(end.y - node.bottom) < 0.01f
        assertTrue(onOutline, "node=$node, path=${path.points}")
        val center = node.center
        val previousDistance =
            (previous.x - center.x) * (previous.x - center.x) +
                (previous.y - center.y) * (previous.y - center.y)
        val endDistance =
            (end.x - center.x) * (end.x - center.x) +
                (end.y - center.y) * (end.y - center.y)
        assertTrue(previousDistance > endDistance, "Returning arrow must approach A from outside")
    }

    @Test
    fun keepsEdgeLabelsClearOfUnrelatedRoutes() {
        for (id in listOf("parallel_edges", "edge_labels", "mixed_link_labels")) {
            val scene = renderCase(id)
            val paths = scene.elements.filterIsInstance<ScenePath>()
            val labels = scene.elements.filterIsInstance<SceneShape>()
                .filter { it.id.endsWith("_label_background") }
            for (label in labels) {
                val owner = label.id.removeSuffix("_label_background")
                for (path in paths.filter { it.id != owner }) {
                    assertTrue(
                        path.points.zipWithNext().none { (a, b) -> crossesInterior(a, b, label.bounds) },
                        "$id: ${label.id} hides ${path.id}: ${label.bounds}, ${path.points}",
                    )
                }
            }
        }
    }

    @Test
    fun separatesDenseRoutesWithoutSharedSegments() {
        for (id in listOf("basic_syntax", "crossing_routes", "dense_branch_merge")) {
            val paths = renderCase(id).elements.filterIsInstance<ScenePath>()
            for ((index, first) in paths.withIndex()) {
                for (second in paths.drop(index + 1)) {
                    for ((a, b) in first.points.zipWithNext()) {
                        for ((c, d) in second.points.zipWithNext()) {
                            val vertical = kotlin.math.abs(a.x - b.x) < 0.1f &&
                                kotlin.math.abs(c.x - d.x) < 0.1f && kotlin.math.abs(a.x - c.x) < 0.1f
                            val horizontal = kotlin.math.abs(a.y - b.y) < 0.1f &&
                                kotlin.math.abs(c.y - d.y) < 0.1f && kotlin.math.abs(a.y - c.y) < 0.1f
                            val overlap = when {
                                vertical -> minOf(maxOf(a.y, b.y), maxOf(c.y, d.y)) -
                                    maxOf(minOf(a.y, b.y), minOf(c.y, d.y))
                                horizontal -> minOf(maxOf(a.x, b.x), maxOf(c.x, d.x)) -
                                    maxOf(minOf(a.x, b.x), minOf(c.x, d.x))
                                else -> 0f
                            }
                            assertTrue(
                                overlap <= 0.1f,
                                "$id: ${first.id} and ${second.id} share $overlap units: $a -> $b, $c -> $d",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun renderCase(id: String): MermaidScene = assertIs<GMResult.Ok<MermaidScene>>(
        engine.render(officialFlowchartCases.first { it.id == id }.source, context),
    ).value

    private fun crossesInterior(start: ScenePoint, end: ScenePoint, bounds: SceneRect): Boolean {
        val inset = 0.1f
        return if (kotlin.math.abs(start.x - end.x) < inset) {
            start.x > bounds.left + inset && start.x < bounds.right - inset &&
                maxOf(start.y, end.y) > bounds.top + inset && minOf(start.y, end.y) < bounds.bottom - inset
        } else if (kotlin.math.abs(start.y - end.y) < inset) {
            start.y > bounds.top + inset && start.y < bounds.bottom - inset &&
                maxOf(start.x, end.x) > bounds.left + inset && minOf(start.x, end.x) < bounds.right - inset
        } else {
            false
        }
    }
}
