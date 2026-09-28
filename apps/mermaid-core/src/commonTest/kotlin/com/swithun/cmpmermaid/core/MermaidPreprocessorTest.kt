package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MermaidPreprocessorTest {
    @Test
    fun encodesEntitiesLikeMermaidUtility() {
        val source =
            "style this; is ; everything :something#not-nothing; and this too; \n" +
                "classDef this; is ; everything :something#not-nothing; and this too; \n" +
                "Hello #there; #andHere;#77653;"

        assertEquals(
            "style this; is ; everything :something#not-nothing; and this too \n" +
                "classDef this; is ; everything :something#not-nothing; and this too \n" +
                "Hello ﬂ°there¶ß ﬂ°andHere¶ßﬂ°°77653¶ß",
            MermaidPreprocessor.encodeEntities(source),
        )
    }

    @Test
    fun leavesCssHexColorsWithoutSemicolonsUnencoded() {
        val source =
            "classDef active fill:#dcfce7,stroke:#15803d,color:#14532d;\n" +
                "style archive fill:#dbeafe,stroke:#1d4ed8,stroke-width:3px"

        assertEquals(
            "classDef active fill:#dcfce7,stroke:#15803d,color:#14532d\n" +
                "style archive fill:#dbeafe,stroke:#1d4ed8,stroke-width:3px",
            MermaidPreprocessor.encodeEntities(source),
        )
    }

    @Test
    fun usesNativeDagreDefaultWithoutAJavaScriptRuntime() {
        val result = MermaidPreprocessor.preprocess(
            """
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value

        assertEquals("dagre", options.layout)
    }

    @Test
    fun directiveLayoutOverridesFrontmatterAndCallerOptions() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  layout: elk
                ---
                %%{init: {'layout': 'dagre'}}%%
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions(layout = "elk.stress")),
        ).value

        assertEquals("dagre", options.layout)
    }

    @Test
    fun portsFrontmatterExtractionAndFlowchartConfig() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                title: Configured graph
                config:
                  flowchart:
                    padding: 24
                    minNodeWidth: 180
                    nodeSpacing: 72
                    rankSpacing: 84
                    curve: linear
                    inheritDir: true
                    subGraphTitleMargin:
                      top: 10
                      bottom: 5
                ---
                flowchart TB
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value
        assertEquals("Configured graph", processed.title)
        assertEquals("flowchart TB\n  A --> B", processed.code.cleaned)
        assertEquals(14, processed.code.frontmatterLineOffset)
        assertEquals(24f, options.flowchartPadding)
        assertEquals(180f, options.minNodeWidth)
        assertEquals(72f, options.nodeSpacing)
        assertEquals(84f, options.rankSpacing)
        assertEquals("linear", options.curve)
        assertEquals(true, options.inheritDirection)
        assertEquals(10f, options.subGraphTitleTopMargin)
        assertEquals(5f, options.subGraphTitleBottomMargin)
    }

    @Test
    fun portsMermaidElkConfiguration() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  elk:
                    mergeEdges: true
                    nodePlacementStrategy: NETWORK_SIMPLEX
                    nodePlacementAlignment: RIGHTDOWN
                    preset: modelOrder
                    straightenEdges: false
                    lineHops: gap
                    layeringStrategy: COFFMAN_GRAHAM
                    layeringLayerBound: 7
                    cycleBreakingStrategy: GREEDY_MODEL_ORDER
                    forceNodeModelOrder: true
                    considerModelOrder: PREFER_NODES
                    keepEntryNodeOnTop: true
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value

        assertEquals(true, options.elk.mergeEdges)
        assertEquals("NETWORK_SIMPLEX", options.elk.nodePlacementStrategy)
        assertEquals("RIGHTDOWN", options.elk.nodePlacementAlignment)
        assertEquals("modelOrder", options.elk.preset)
        assertEquals(false, options.elk.straightenEdges)
        assertEquals(MermaidElkLineHops.Gap, options.elk.lineHops)
        assertEquals("COFFMAN_GRAHAM", options.elk.layeringStrategy)
        assertEquals(7, options.elk.layeringLayerBound)
        assertEquals("GREEDY_MODEL_ORDER", options.elk.cycleBreakingStrategy)
        assertEquals(true, options.elk.forceNodeModelOrder)
        assertEquals("PREFER_NODES", options.elk.considerModelOrder)
        assertEquals(true, options.elk.keepEntryNodeOnTop)
    }

    @Test
    fun portsMermaidPieConfiguration() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  pie:
                    textPosition: 0.4
                    donutHole: 0.3
                    legendPosition: bottom
                    highlightSlice: Potassium
                ---
                pie
                  "Calcium" : 40
                  "Potassium" : 60
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value

        assertEquals(0.4f, options.pieTextPosition)
        assertEquals(0.3f, options.pieDonutHole)
        assertEquals("bottom", options.pieLegendPosition)
        assertEquals("Potassium", options.pieHighlightSlice)
    }

    @Test
    fun rejectsOutOfRangePieConfiguration() {
        listOf(
            "textPosition: 1.1",
            "donutHole: -0.1",
            "donutHole: 0.91",
            "legendPosition: diagonal",
        ).forEach { config ->
            val result = MermaidPreprocessor.preprocess(
                """
                    ---
                    config:
                      pie:
                        $config
                    ---
                    pie
                      "A" : 1
                """.trimIndent(),
            )

            assertIs<GMResult.Err<MermaidError>>(
                result,
                "Expected invalid Pie config to fail: $config",
            )
        }
    }

    @Test
    fun portsAndMergesCynefinConfiguration() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  cynefin:
                    width: 700
                    height: 500
                    padding: 24
                    showDomainDescriptions: false
                    boundaryAmplitude: 12
                    seed: 42
                    useMaxWidth: false
                ---
                %%{init: {'cynefin': {'width': 900, 'boundaryAmplitude': 0}}}%%
                cynefin-beta
                  complex
                    "Adaptive work"
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value.cynefin

        assertEquals(900f, options.width)
        assertEquals(500f, options.height)
        assertEquals(24f, options.padding)
        assertEquals(false, options.showDomainDescriptions)
        assertEquals(0f, options.boundaryAmplitude)
        assertEquals(42f, options.seed)
        assertEquals(false, options.useMaxWidth)
    }

    @Test
    fun rejectsInvalidCynefinConfiguration() {
        listOf(
            "width: 0" to "cynefin.width",
            "height: -1" to "cynefin.height",
            "padding: -1" to "cynefin.padding",
            "boundaryAmplitude: 51" to "cynefin.boundaryAmplitude",
            "seed: NaN" to "cynefin.seed",
            "showDomainDescriptions: maybe" to "cynefin.showDomainDescriptions",
            "useMaxWidth: sometimes" to "cynefin.useMaxWidth",
        ).forEach { (config, path) ->
            val result = MermaidPreprocessor.preprocess(
                """
                    ---
                    config:
                      cynefin:
                        $config
                    ---
                    cynefin-beta
                      complex
                """.trimIndent(),
            )

            val error = assertIs<GMResult.Err<MermaidError>>(result, config).error
            assertIs<MermaidError.Configuration>(error, config)
            assertContains(error.message, path, message = config)
        }
    }

    @Test
    fun portsAndValidatesEventModelingConfiguration() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  eventmodeling:
                    padding: 24
                    rowHeight: 40
                    useMaxWidth: false
                ---
                eventmodeling
                tf 01 ui UI
            """.trimIndent(),
        )
        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value.eventModeling

        assertEquals(24f, options.padding)
        assertEquals(40f, options.rowHeight)
        assertEquals(false, options.useMaxWidth)

        listOf(
            "padding: NaN" to "eventmodeling.padding",
            "rowHeight: 0" to "eventmodeling.rowHeight",
            "useMaxWidth: sometimes" to "eventmodeling.useMaxWidth",
        ).forEach { (config, path) ->
            val invalid = MermaidPreprocessor.preprocess(
                """
                    ---
                    config:
                      eventmodeling:
                        $config
                    ---
                    eventmodeling
                """.trimIndent(),
            )
            val error = assertIs<GMResult.Err<MermaidError>>(invalid, config).error
            assertIs<MermaidError.Configuration>(error, config)
            assertContains(error.message, path, message = config)
        }
    }

    @Test
    fun flattensNestedCynefinThemeVariablesAndReportsTheirExactPath() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  themeVariables:
                    cynefin:
                      domainFontSize: 18
                      complexBg: "#112233"
                ---
                cynefin-beta
                  complex
            """.trimIndent(),
        )
        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value

        assertEquals("18", options.themeVariables["cynefin.domainFontSize"])
        assertEquals("#112233", options.themeVariables["cynefin.complexBg"])

        val invalid = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  themeVariables:
                    cynefin:
                      complexBg:
                        nested: nope
                ---
                cynefin-beta
                  complex
            """.trimIndent(),
        )
        val error = assertIs<GMResult.Err<MermaidError>>(invalid).error
        assertContains(error.message, "themeVariables.cynefin.complexBg")
    }

    @Test
    fun directiveOverridesFrontmatterLikeCleanAndMerge() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  flowchart:
                    padding: 20
                    curve: basis
                ---
                %%{init: {'flowchart': {'padding': 32, 'curve': 'step'}}}%%
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value
        assertEquals(32f, options.flowchartPadding)
        assertEquals("step", options.curve)
        assertEquals("flowchart LR\n  A --> B", processed.code.cleaned)
    }

    @Test
    fun stripsSecureHostOptionsFromDiagramConfig() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  maxEdges: 999
                  maxTextSize: 99999
                  securityLevel: loose
                  secure: []
                ---
                %%{init: {
                  'maxEdges': 1000,
                  'maxTextSize': 100000,
                  'securityLevel': 'loose'
                }}%%
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(
                MermaidRenderOptions(
                    securityLevel = MermaidSecurityLevel.Strict,
                    maxTextSize = 17,
                    maxEdges = 7,
                ),
            ),
        ).value
        assertEquals(MermaidSecurityLevel.Strict, options.securityLevel)
        assertEquals(17, options.maxTextSize)
        assertEquals(7, options.maxEdges)
    }

    @Test
    fun returnsStructuredErrorForInvalidConfigType() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  flowchart:
                    padding: wide
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.Configuration>(error)
        assertContains(error.message, "flowchart.padding")
    }

    @Test
    fun rejectsInvalidSubgraphTitleMargin() {
        val invalidMap = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  flowchart:
                    subGraphTitleMargin: 12
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )
        val mapError = assertIs<GMResult.Err<MermaidError>>(invalidMap).error
        assertIs<MermaidError.Configuration>(mapError)
        assertContains(mapError.message, "flowchart.subGraphTitleMargin")

        val negative = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  flowchart:
                    subGraphTitleMargin:
                      top: -1
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )
        val negativeError = assertIs<GMResult.Err<MermaidError>>(negative).error
        assertIs<MermaidError.Configuration>(negativeError)
        assertContains(negativeError.message, "flowchart.subGraphTitleMargin.top")
    }

    @Test
    fun acceptsMermaidBuiltInThemeConfiguration() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  theme: forest
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value
        assertEquals("forest", options.themeName)
    }

    @Test
    fun preservesQuotedNullThemeButTreatsYamlNullAsAbsent() {
        val quoted = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  flowchart:
                    theme: "null"
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )
        val yamlNull = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  flowchart:
                    theme: null
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val quotedOptions = assertIs<GMResult.Ok<MermaidPreprocessResult>>(quoted)
            .value
            .config
            .applyTo(MermaidRenderOptions(themeName = "forest"))
        val yamlNullOptions = assertIs<GMResult.Ok<MermaidPreprocessResult>>(yamlNull)
            .value
            .config
            .applyTo(MermaidRenderOptions(themeName = "forest"))

        assertEquals(
            "null",
            assertIs<GMResult.Ok<MermaidRenderOptions>>(quotedOptions).value.themeName,
        )
        assertEquals(
            "forest",
            assertIs<GMResult.Ok<MermaidRenderOptions>>(yamlNullOptions).value.themeName,
        )
    }

    @Test
    fun ignoresUnknownScopedAppearanceBeforeApplyingSameLayerGlobalValue() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  theme: dark
                  look: classic
                  flowchart:
                    theme: totally-bogus
                    look: not-a-look
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value

        assertEquals("dark", options.themeName)
        assertEquals("classic", options.look)
    }

    @Test
    fun mergesThemeVariablesAndAcceptsDomOnlyFlowchartOptions() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  themeVariables:
                    nodeBkg: "#112233"
                    lineColor: "#445566"
                  flowchart:
                    arrowMarkerAbsolute: true
                    useMaxWidth: false
                ---
                %%{init: {'themeVariables': {'lineColor': '#778899'}}}%%
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value

        assertEquals("#112233", options.themeVariables["nodeBkg"])
        assertEquals("#778899", options.themeVariables["lineColor"])
    }

    @Test
    fun portsFontFamilyAndPreservesThemeVariablePrecedence() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  fontFamily: "Arial, sans-serif"
                  themeVariables:
                    fontFamily: "Verdana, sans-serif"
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value

        assertEquals("Arial, sans-serif", options.fontFamily)
        assertEquals("Verdana, sans-serif", options.themeVariables["fontFamily"])
    }

    @Test
    fun preservesThemeColorArraysAsStructuredValues() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  themeVariables:
                    bkgColorArray: ["#112233", "rgb(68, 85, 102)"]
                    borderColorArray:
                      - "#778899"
                      - "#aabbcc"
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value

        assertEquals(
            listOf("#112233", "rgb(68, 85, 102)"),
            options.themeColorArrays["bkgColorArray"],
        )
        assertEquals(
            listOf("#778899", "#aabbcc"),
            options.themeColorArrays["borderColorArray"],
        )
    }

    @Test
    fun portsCompletePacketConfigShape() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  packet:
                    rowHeight: 40
                    bitWidth: 24
                    bitsPerRow: 16
                    showBits: false
                    paddingX: 3
                    paddingY: 7
                    useMaxWidth: false
                ---
                packet
                  +16: "Header"
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val packet = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value.packet

        assertEquals(40f, packet.rowHeight)
        assertEquals(24f, packet.bitWidth)
        assertEquals(16, packet.bitsPerRow)
        assertEquals(false, packet.showBits)
        assertEquals(3f, packet.paddingX)
        assertEquals(7f, packet.paddingY)
        assertEquals(false, packet.useMaxWidth)
    }

    @Test
    fun rejectsPacketConfigOutsideUpstreamSchemaBounds() {
        val invalidFields = listOf(
            "rowHeight: 0",
            "bitWidth: 0",
            "bitsPerRow: 0",
            "paddingX: -1",
            "paddingY: -1",
        )

        invalidFields.forEach { field ->
            val result = MermaidPreprocessor.preprocess(
                """
                    ---
                    config:
                      packet:
                        $field
                    ---
                    packet
                      0: "Flag"
                """.trimIndent(),
            )
            assertIs<GMResult.Err<MermaidError>>(result)
        }
    }

    @Test
    fun portsCompleteRadarConfigShape() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  radar:
                    width: 520
                    height: 420
                    marginTop: 31
                    marginRight: 32
                    marginBottom: 33
                    marginLeft: 34
                    axisScaleFactor: 0.8
                    axisLabelFactor: 0.9
                    curveTension: 0.25
                    useMaxWidth: false
                ---
                radar-beta
                  axis A, B, C
                  curve values { 1, 2, 3 }
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val radar = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value.radar

        assertEquals(520f, radar.width)
        assertEquals(420f, radar.height)
        assertEquals(31f, radar.marginTop)
        assertEquals(32f, radar.marginRight)
        assertEquals(33f, radar.marginBottom)
        assertEquals(34f, radar.marginLeft)
        assertEquals(0.8f, radar.axisScaleFactor)
        assertEquals(0.9f, radar.axisLabelFactor)
        assertEquals(0.25f, radar.curveTension)
        assertEquals(false, radar.useMaxWidth)
    }

    @Test
    fun rejectsRadarConfigOutsideSupportedBounds() {
        val invalidFields = listOf(
            "width: 0",
            "height: 0",
            "marginTop: -1",
            "axisScaleFactor: -0.1",
            "curveTension: 1.1",
        )

        invalidFields.forEach { field ->
            val result = MermaidPreprocessor.preprocess(
                """
                    ---
                    config:
                      radar:
                        $field
                    ---
                    radar-beta
                      axis A, B, C
                      curve values { 1, 2, 3 }
                """.trimIndent(),
            )
            assertIs<GMResult.Err<MermaidError>>(result)
        }
    }

    @Test
    fun portsCompleteTimelineConfigShape() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  timeline:
                    useWidth: 960
                    useMaxWidth: false
                    theme: forest
                    look: classic
                    layout: custom
                    diagramMarginX: 51
                    diagramMarginY: 12
                    leftMargin: 175
                    width: 160
                    height: 60
                    padding: 44
                    boxMargin: 11
                    boxTextMargin: 6
                    noteMargin: 13
                    messageMargin: 37
                    messageAlign: right
                    bottomMarginAdj: 2
                    rightAngles: true
                    taskFontSize: 15px
                    taskFontFamily: Timeline Sans
                    taskMargin: 55
                    activationWidth: 12
                    textPlacement: tspan
                    actorColours: ["#112233"]
                    sectionFills: ["#445566"]
                    sectionColours: ["#778899"]
                    disableMulticolor: true
                ---
                timeline
                  2026 : Shipped
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val timeline = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value.timeline

        assertEquals(960f, timeline.useWidth)
        assertEquals(false, timeline.useMaxWidth)
        assertEquals("forest", timeline.theme)
        assertEquals("classic", timeline.look)
        assertEquals("custom", timeline.layout)
        assertEquals(51f, timeline.diagramMarginX)
        assertEquals(12f, timeline.diagramMarginY)
        assertEquals(175f, timeline.leftMargin)
        assertEquals(160f, timeline.width)
        assertEquals(60f, timeline.height)
        assertEquals(44f, timeline.padding)
        assertEquals(11f, timeline.boxMargin)
        assertEquals(6f, timeline.boxTextMargin)
        assertEquals(13f, timeline.noteMargin)
        assertEquals(37f, timeline.messageMargin)
        assertEquals("right", timeline.messageAlign)
        assertEquals(2f, timeline.bottomMarginAdj)
        assertEquals(true, timeline.rightAngles)
        assertEquals(15f, timeline.taskFontSize)
        assertEquals("Timeline Sans", timeline.taskFontFamily)
        assertEquals(55f, timeline.taskMargin)
        assertEquals(12f, timeline.activationWidth)
        assertEquals("tspan", timeline.textPlacement)
        assertEquals(listOf(SceneColor(0xFF112233)), timeline.actorColours)
        assertEquals(listOf(SceneColor(0xFF445566)), timeline.sectionFills)
        assertEquals(listOf(SceneColor(0xFF778899)), timeline.sectionColours)
        assertEquals(true, timeline.disableMulticolor)
    }

    @Test
    fun portsAndMergesAgentflowConfigurationWithoutChangingFlowchartOptions() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  flowchart:
                    nodeSpacing: 19
                    rankSpacing: 23
                    wrappingWidth: 210
                  agentflow:
                    useMaxWidth: false
                    theme: forest
                    look: classic
                    titleTopMargin: 31
                    diagramPadding: 17
                    nodeSpacing: 71
                    rankSpacing: 73
                    wrappingWidth: 175
                    minNodeWidth: 145
                ---
                %%{init: {'agentflow': {'theme': 'dark', 'nodeSpacing': 81}}}%%
                agentflow-beta LR
                  a --> b
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(
                MermaidRenderOptions(
                    agentflow = MermaidAgentflowOptions(
                        theme = "neutral",
                        nodeSpacing = 61f,
                    ),
                ),
            ),
        ).value

        assertEquals(19f, options.nodeSpacing)
        assertEquals(23f, options.rankSpacing)
        assertEquals(210f, options.wrappingWidth)
        assertEquals(false, options.agentflow.useMaxWidth)
        assertEquals("dark", options.agentflow.theme)
        assertEquals("classic", options.agentflow.look)
        assertEquals(31f, options.agentflow.titleTopMargin)
        assertEquals(17f, options.agentflow.diagramPadding)
        assertEquals(81f, options.agentflow.nodeSpacing)
        assertEquals(73f, options.agentflow.rankSpacing)
        assertEquals(175f, options.agentflow.wrappingWidth)
        assertEquals(145f, options.agentflow.minNodeWidth)
    }

    @Test
    fun portsAndMergesCompleteC4Configuration() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  c4:
                    diagramMarginX: 31
                    diagramMarginY: 12
                    c4ShapeMargin: 42
                    c4ShapePadding: 17
                    width: 240
                    height: 84
                    boxMargin: 9
                    useMaxWidth: false
                    c4ShapeInRow: 3
                    nextLinePaddingX: 19
                    c4BoundaryInRow: 1
                    wrap: false
                    wrapPadding: 8
                    boundaryFontSize: 16px
                    boundaryFontFamily: Boundary Sans
                    boundaryFontWeight: bold
                    messageFontSize: 13px
                    messageFontFamily: Message Sans
                    messageFontWeight: 500
                    personFontSize: 18px
                    personFontFamily: Person Sans
                    personFontWeight: 600
                    person_bg_color: "#112233"
                    person_border_color: "#445566"
                ---
                %%{init: {'c4': {
                  'diagramMarginX': 41,
                  'personFontSize': '20px',
                  'person_border_color': '#778899'
                }}}%%
                C4Context
                Person(user, "User")
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value.c4
        val person = options.elementStyles.getValue("person")

        assertEquals(41f, options.diagramMarginX)
        assertEquals(12f, options.diagramMarginY)
        assertEquals(42f, options.c4ShapeMargin)
        assertEquals(17f, options.c4ShapePadding)
        assertEquals(240f, options.width)
        assertEquals(84f, options.height)
        assertEquals(9f, options.boxMargin)
        assertEquals(false, options.useMaxWidth)
        assertEquals(3, options.c4ShapeInRow)
        assertEquals(19f, options.nextLinePaddingX)
        assertEquals(1, options.c4BoundaryInRow)
        assertEquals(false, options.wrap)
        assertEquals(8f, options.wrapPadding)
        assertEquals(16f, options.boundaryFontSize)
        assertEquals("Boundary Sans", options.boundaryFontFamily)
        assertEquals("bold", options.boundaryFontWeight)
        assertEquals(13f, options.messageFontSize)
        assertEquals("Message Sans", options.messageFontFamily)
        assertEquals("500", options.messageFontWeight)
        assertEquals(20f, person.fontSize)
        assertEquals("Person Sans", person.fontFamily)
        assertEquals("600", person.fontWeight)
        assertEquals(SceneColor(0xFF112233), person.background)
        assertEquals(SceneColor(0xFF778899), person.border)
    }

    @Test
    fun rejectsInvalidC4Configuration() {
        listOf(
            "diagramMarginX: -1" to "c4.diagramMarginX",
            "c4ShapeInRow: 0" to "c4.c4ShapeInRow",
            "c4BoundaryInRow: 0" to "c4.c4BoundaryInRow",
            "wrap: sometimes" to "c4.wrap",
            "person_bg_color: invalid" to "c4.person_bg_color",
        ).forEach { (config, path) ->
            val result = MermaidPreprocessor.preprocess(
                """
                    ---
                    config:
                      c4:
                        $config
                    ---
                    C4Context
                    Person(user, "User")
                """.trimIndent(),
            )

            val error = assertIs<GMResult.Err<MermaidError>>(result, config).error
            assertIs<MermaidError.Configuration>(error, config)
            assertContains(error.message, path)
        }
    }

    @Test
    fun rejectsInvalidAgentflowConfiguration() {
        listOf(
            "useMaxWidth: sometimes" to "agentflow.useMaxWidth",
            "titleTopMargin: -1" to "agentflow.titleTopMargin",
            "diagramPadding: -1" to "agentflow.diagramPadding",
            "nodeSpacing: -1" to "agentflow.nodeSpacing",
            "rankSpacing: -1" to "agentflow.rankSpacing",
            "wrappingWidth: 0" to "agentflow.wrappingWidth",
            "minNodeWidth: 0" to "agentflow.minNodeWidth",
        ).forEach { (config, path) ->
            val result = MermaidPreprocessor.preprocess(
                """
                    ---
                    config:
                      agentflow:
                        $config
                    ---
                    agentflow-beta TB
                      a --> b
                """.trimIndent(),
            )

            val error = assertIs<GMResult.Err<MermaidError>>(result, config).error
            assertIs<MermaidError.Configuration>(error, config)
            assertContains(error.message, path)
        }
    }

    @Test
    fun rejectsKnownConfigurationWithoutANativePort() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  themeCSS: ".node { filter: blur(2px); }"
                ---
                flowchart LR
                  A --> B
            """.trimIndent(),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.UnsupportedFeature>(error)
        assertContains(error.message, "themeCSS")
    }

    @Test
    fun portsCompleteSankeyConfigShape() {
        val result = MermaidPreprocessor.preprocess(
            """
                ---
                config:
                  sankey:
                    width: 720
                    height: 360
                    linkColor: source
                    nodeAlignment: center
                    useMaxWidth: true
                    showValues: false
                    prefix: "${'$'}"
                    suffix: MW
                    nodeWidth: 16
                    nodePadding: 9
                    labelStyle: outlined
                    nodeColors:
                      Input: "#112233"
                ---
                sankey
                Input,Output,1
            """.trimIndent(),
        )

        val processed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(result).value
        val sankey = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            processed.config.applyTo(MermaidRenderOptions()),
        ).value.sankey

        assertEquals(720f, sankey.width)
        assertEquals(360f, sankey.height)
        assertEquals("source", sankey.linkColor)
        assertEquals("center", sankey.nodeAlignment)
        assertEquals(true, sankey.useMaxWidth)
        assertEquals(false, sankey.showValues)
        assertEquals("${'$'}", sankey.prefix)
        assertEquals("MW", sankey.suffix)
        assertEquals(16f, sankey.nodeWidth)
        assertEquals(9f, sankey.nodePadding)
        assertEquals("outlined", sankey.labelStyle)
        assertEquals(SceneColor(0xFF112233), sankey.nodeColors["Input"])
    }
}
