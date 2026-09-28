package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MermaidThemeTest {
    @Test
    fun exposesEveryMermaid12ThemePresetByTypedAndConfigNameApis() {
        MermaidThemePreset.entries.forEach { preset ->
            val typed = MermaidTheme.preset(preset)
            val named = assertIs<GMResult.Ok<MermaidTheme>>(
                MermaidTheme.fromName(preset.configName),
            ).value

            assertEquals(typed, named)
        }
    }

    @Test
    fun returnsConfigurationErrorForUnknownThemeName() {
        val result = MermaidTheme.fromName("not-a-mermaid-theme")

        assertIs<GMResult.Err<MermaidError.Configuration>>(result)
    }

    @Test
    fun matchesMermaid12NoteVariablesForEveryPreset() {
        val expected = mapOf(
            MermaidThemePreset.Default to Triple(0xFFFFF5AD, 0xFFAAAA33, 0xFF000000),
            MermaidThemePreset.Dark to Triple(0xFF474949, 0xFF2F2F2F, 0xFFB8B6B6),
            MermaidThemePreset.Forest to Triple(0xFFFFF5AD, 0xFF6EAA49, 0xFF000000),
            MermaidThemePreset.Neutral to Triple(0xFF666666, 0xFF999999, 0xFFFFFFFF),
            MermaidThemePreset.Base to Triple(0xFFFFF5AD, 0xFFE4DB95, 0xFF333333),
            MermaidThemePreset.Neo to Triple(0xFFFFF5AD, 0xFFE4DB95, 0xFF333333),
            MermaidThemePreset.NeoDark to Triple(0xFFFFF5AD, 0xFFE4DB95, 0xFF333333),
            MermaidThemePreset.Redux to Triple(0xFFFFF5AD, 0xFFFACC15, 0xFF28253D),
            MermaidThemePreset.ReduxColor to Triple(0xFFFFF5AD, 0xFFFACC15, 0xFF28253D),
            MermaidThemePreset.ReduxDark to Triple(0xFFFEF9C3, 0xFFFACC15, 0xFF28253D),
            MermaidThemePreset.ReduxDarkColor to
                Triple(0xFFFEF9C3, 0xFFFACC15, 0xFF28253D),
        )

        expected.forEach { (preset, colors) ->
            val theme = MermaidTheme.preset(preset)
            assertEquals(SceneColor(colors.first), theme.noteFill, preset.name)
            assertEquals(SceneColor(colors.second), theme.noteStroke, preset.name)
            assertEquals(SceneColor(colors.third), theme.noteText, preset.name)
        }
    }

    @Test
    fun matchesMermaid12CoreVariablesForLegacyPresets() {
        assertEquals(
            SceneColor(0xFF131300),
            MermaidTheme.preset(MermaidThemePreset.Default).nodeText,
        )
        assertEquals(
            SceneColor(0xFFE0DFDF),
            MermaidTheme.preset(MermaidThemePreset.Dark).nodeText,
        )
        assertEquals(
            SceneColor(0xFF321B67),
            MermaidTheme.preset(MermaidThemePreset.Forest).nodeText,
        )
        with(MermaidTheme.preset(MermaidThemePreset.Base)) {
            assertEquals(SceneColor(0xFFEEDEBB), nodeStroke)
            assertEquals(SceneColor(0xFFF4DDFF), edgeLabelFill)
            assertEquals(SceneColor(0xFFF7F9FF), groupFill)
            assertEquals(SceneColor(0xFFCFDBF3), groupStroke)
            assertEquals(SceneColor(0xFF090600), groupText)
        }
        with(MermaidTheme.preset(MermaidThemePreset.ReduxColor).gantt) {
            assertEquals(SceneColor(0xFFF4A8FF), sectionBackground)
            assertEquals(SceneColor(0xFF46ECD5), secondSectionBackground)
            assertEquals(SceneColor(0xFFECECFE), taskFill)
        }
        with(MermaidTheme.preset(MermaidThemePreset.ReduxDarkColor).gantt) {
            assertEquals(SceneColor(0xFF333333), alternateSectionBackground)
            assertEquals(SceneColor(0xFF1F2020), taskFill)
            assertEquals(SceneColor(0xFF38383E), doneTaskFill)
        }
    }

    @Test
    fun matchesAndOverridesMermaid12AgentflowContainerStroke() {
        val expected = mapOf(
            MermaidThemePreset.Default to 0xFFEEEEBC,
            MermaidThemePreset.Dark to 0xFF2F2F2F,
            MermaidThemePreset.Forest to 0xFFB4E599,
            MermaidThemePreset.Neutral to 0xFFE3E3E3,
            MermaidThemePreset.Base to 0xFFDDBBEE,
            MermaidThemePreset.Neo to 0xFFB3B3B3,
            MermaidThemePreset.NeoDark to 0xFF2F2F2F,
            MermaidThemePreset.Redux to 0xFFB3B3B3,
            MermaidThemePreset.ReduxColor to 0xFFB3B3B3,
            MermaidThemePreset.ReduxDark to 0xFF2F2F2F,
            MermaidThemePreset.ReduxDarkColor to 0xFF2F2F2F,
        )

        expected.forEach { (preset, color) ->
            assertEquals(
                SceneColor(color),
                MermaidTheme.preset(preset).flowContainerStroke,
                preset.name,
            )
        }

        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = MermaidTheme.MermaidDefault,
                values = mapOf("flowContainerStroke" to "#102030"),
            ),
        ).value
        assertEquals(SceneColor(0xFF102030), customized.flowContainerStroke)
    }

    @Test
    fun matchesMermaid12DefaultXyChartTextColors() {
        val expected = SceneColor(0xFF131300)
        val xyChart = MermaidTheme.preset(MermaidThemePreset.Default).xyChart

        listOf(
            xyChart.titleColor,
            xyChart.dataLabelColor,
            xyChart.legendTextColor,
            xyChart.xAxisLabelColor,
            xyChart.xAxisTitleColor,
            xyChart.xAxisTickColor,
            xyChart.xAxisLineColor,
            xyChart.yAxisLabelColor,
            xyChart.yAxisTitleColor,
            xyChart.yAxisTickColor,
            xyChart.yAxisLineColor,
        ).forEach { actual ->
            assertEquals(expected, actual)
        }
    }

    @Test
    fun matchesMermaid12DefaultRequirementRenderedColors() {
        val requirement = MermaidTheme.preset(MermaidThemePreset.Default).requirement

        assertEquals(SceneColor(0xFFECECFF), requirement.background)
        assertEquals(SceneColor(0xFF9370DB), requirement.borderColor)
        assertEquals(SceneColor(0xFF333333), requirement.textColor)
        assertEquals(SceneColor(0xFF333333), requirement.relationLabelColor)
        assertEquals(SceneColor(0xCCE8E8E8), requirement.edgeLabelBackground)
    }

    @Test
    fun matchesMermaid12CynefinThemePalettes() {
        with(MermaidTheme.preset(MermaidThemePreset.Default).cynefin) {
            assertEquals(SceneColor(0xFFE8F5E9), complexBackground)
            assertEquals(SceneColor(0xFFE3F2FD), complicatedBackground)
            assertEquals(SceneColor(0xFFFBE9E7), chaoticBackground)
            assertEquals(SceneColor(0xFFFFF8E1), clearBackground)
            assertEquals(SceneColor(0xFFF3E5F5), confusionBackground)
            assertEquals(SceneColor(0xFF8B0000), cliffColor)
        }
        with(MermaidTheme.preset(MermaidThemePreset.Dark).cynefin) {
            assertEquals(SceneColor(0xFF1B5E20), complexBackground)
            assertEquals(SceneColor(0xFF0D47A1), complicatedBackground)
            assertEquals(SceneColor(0xFFBF360C), chaoticBackground)
            assertEquals(SceneColor(0xFFF57F17), clearBackground)
            assertEquals(SceneColor(0xFF4A148C), confusionBackground)
            assertEquals(SceneColor(0xFFFF6B6B), cliffColor)
        }
        with(MermaidTheme.preset(MermaidThemePreset.Forest).cynefin) {
            assertEquals(SceneColor(0xFFC8E6C9), complexBackground)
            assertEquals(SceneColor(0xFFDCEDC8), complicatedBackground)
            assertEquals(SceneColor(0xFFFFE0B2), chaoticBackground)
            assertEquals(SceneColor(0xFFFFF9C4), clearBackground)
            assertEquals(SceneColor(0xFFD7CCC8), confusionBackground)
            assertEquals(SceneColor(0xFF8B4513), cliffColor)
        }
    }

    @Test
    fun derivesCynefinLineAndTextColorsForEveryPreset() {
        MermaidThemePreset.entries.forEach { preset ->
            val theme = MermaidTheme.preset(preset)

            assertEquals(theme.edge, theme.cynefin.boundaryColor, preset.name)
            assertEquals(theme.edge, theme.cynefin.arrowColor, preset.name)
            assertEquals(theme.textColor, theme.cynefin.textColor, preset.name)
            assertEquals(theme.nodeText, theme.cynefin.labelColor, preset.name)
        }
    }

    @Test
    fun matchesAndOverridesMermaid12EventModelingThemeVariables() {
        val defaultTheme = MermaidTheme.preset(MermaidThemePreset.Default)
        with(defaultTheme.eventModeling) {
            assertEquals(SceneColor(0xFFFFFFFF), uiFill)
            assertEquals(SceneColor(0xFFBCD6FE), commandFill)
            assertEquals(SceneColor(0xFFFFB778), eventFill)
            assertEquals(null, arrowhead)
            assertEquals(null, relationStroke)
        }
        with(MermaidTheme.preset(MermaidThemePreset.Dark).eventModeling) {
            assertEquals(SceneColor(0xFF2D2D2D), uiFill)
            assertEquals(SceneColor(0xFF3E547C), commandFill)
            assertEquals(SceneColor(0xFF7C5F3E), eventFill)
            assertEquals(SceneColor(0xFFD3D3D3), arrowhead)
            assertEquals(SceneColor(0xFFD3D3D3), relationStroke)
        }

        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = defaultTheme,
                values = mapOf(
                    "emUiFill" to "#102030",
                    "emCommandStroke" to "#405060",
                    "emSwimlaneBackgroundOdd" to "#708090",
                    "emArrowhead" to "#a0b0c0",
                    "emRelationStroke" to "#d0e0f0",
                ),
            ),
        ).value.eventModeling
        assertEquals(SceneColor(0xFF102030), customized.uiFill)
        assertEquals(SceneColor(0xFF405060), customized.commandStroke)
        assertEquals(SceneColor(0xFF708090), customized.swimlaneBackgroundOdd)
        assertEquals(SceneColor(0xFFA0B0C0), customized.arrowhead)
        assertEquals(SceneColor(0xFFD0E0F0), customized.relationStroke)

        assertIs<GMResult.Err<MermaidError.Configuration>>(
            MermaidTheme.withVariables(
                theme = defaultTheme,
                values = mapOf("emEventFill" to "not-a-color"),
            ),
        )
    }

    @Test
    fun appliesAndValidatesNestedCynefinThemeVariables() {
        val base = MermaidTheme.preset(MermaidThemePreset.Default)
        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = base,
                values = mapOf(
                    "cynefin.domainFontSize" to "20",
                    "cynefin.itemFontSize" to "13px",
                    "cynefin.boundaryColor" to "#102030",
                    "cynefin.boundaryWidth" to "3",
                    "cynefin.cliffColor" to "#405060",
                    "cynefin.cliffWidth" to "5",
                    "cynefin.arrowColor" to "#708090",
                    "cynefin.arrowWidth" to "4",
                    "cynefin.complexBg" to "#112233",
                    "cynefin.complicatedBg" to "#223344",
                    "cynefin.chaoticBg" to "#334455",
                    "cynefin.clearBg" to "#445566",
                    "cynefin.confusionBg" to "#556677",
                    "cynefin.textColor" to "#667788",
                    "cynefin.labelColor" to "#778899",
                ),
            ),
        ).value.cynefin

        assertEquals(20f, customized.domainFontSize)
        assertEquals(13f, customized.itemFontSize)
        assertEquals(SceneColor(0xFF102030), customized.boundaryColor)
        assertEquals(3f, customized.boundaryWidth)
        assertEquals(SceneColor(0xFF405060), customized.cliffColor)
        assertEquals(5f, customized.cliffWidth)
        assertEquals(SceneColor(0xFF708090), customized.arrowColor)
        assertEquals(4f, customized.arrowWidth)
        assertEquals(SceneColor(0xFF112233), customized.complexBackground)
        assertEquals(SceneColor(0xFF223344), customized.complicatedBackground)
        assertEquals(SceneColor(0xFF334455), customized.chaoticBackground)
        assertEquals(SceneColor(0xFF445566), customized.clearBackground)
        assertEquals(SceneColor(0xFF556677), customized.confusionBackground)
        assertEquals(SceneColor(0xFF667788), customized.textColor)
        assertEquals(SceneColor(0xFF778899), customized.labelColor)

        listOf(
            "cynefin.domainFontSize" to "0",
            "cynefin.itemFontSize" to "NaN",
            "cynefin.boundaryWidth" to "-1",
            "cynefin.cliffColor" to "not-a-color",
        ).forEach { (name, value) ->
            assertIs<GMResult.Err<MermaidError.Configuration>>(
                MermaidTheme.withVariables(base, mapOf(name to value)),
                "$name=$value",
            )
        }
    }

    @Test
    fun mergesPartialCynefinOverridesOntoTheDefaultBlockLikeMermaid12() {
        val defaultCynefin = MermaidTheme.preset(MermaidThemePreset.Default).cynefin
        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = MermaidTheme.preset(MermaidThemePreset.Dark),
                values = mapOf("cynefin.confusionBg" to "#581c87"),
            ),
        ).value.cynefin

        assertEquals(defaultCynefin.complexBackground, customized.complexBackground)
        assertEquals(defaultCynefin.boundaryColor, customized.boundaryColor)
        assertEquals(defaultCynefin.cliffColor, customized.cliffColor)
        assertEquals(defaultCynefin.textColor, customized.textColor)
        assertEquals(defaultCynefin.labelColor, customized.labelColor)
        assertEquals(SceneColor(0xFF581C87), customized.confusionBackground)
    }

    @Test
    fun appliesBrandVariablesWithoutMutatingTheBasePreset() {
        val base = MermaidTheme.preset(MermaidThemePreset.ReduxColor)
        val result = MermaidTheme.withVariables(
            theme = base,
            values = mapOf(
                "background" to "#101820",
                "mainBkg" to "#f2aa4c",
                "nodeTextColor" to "#101820",
                "defaultLinkColor" to "#f2aa4c",
                "noteBkgColor" to "#ffffff",
                "noteBorderColor" to "#f2aa4c",
                "noteTextColor" to "#101820",
                "taskBkgColor" to "#334455",
                "sectionBkgColor" to "#667788",
                "fontSize" to "18px",
            ),
            colorArrays = mapOf(
                "bkgColorArray" to listOf("#f2aa4c", "#ffffff"),
                "borderColorArray" to listOf("#101820", "#f2aa4c"),
            ),
        )

        val customized = assertIs<GMResult.Ok<MermaidTheme>>(result).value
        assertEquals(SceneColor(0xFF101820), customized.background)
        assertEquals(SceneColor(0xFFF2AA4C), customized.nodeFill)
        assertEquals(SceneColor(0xFF101820), customized.nodeText)
        assertEquals(SceneColor(0xFFF2AA4C), customized.edge)
        assertEquals(SceneColor(0xFFFFFFFF), customized.noteFill)
        assertEquals(SceneColor(0xFFF2AA4C), customized.noteStroke)
        assertEquals(SceneColor(0xFF101820), customized.noteText)
        assertEquals(SceneColor(0xFF334455), customized.gantt.taskFill)
        assertEquals(SceneColor(0xFF667788), customized.gantt.sectionBackground)
        assertEquals(18f, customized.fontSize)
        assertEquals(
            listOf(SceneColor(0xFFF2AA4C), SceneColor(0xFFFFFFFF)),
            customized.bkgColorArray,
        )
        assertEquals(MermaidTheme.FlowchartDefault, base)
    }

    @Test
    fun appliesOnlyJourneyVariablesAcceptedByMermaid12DirectiveSanitizer() {
        val base = MermaidTheme.preset(MermaidThemePreset.Default)
        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = base,
                values = mapOf(
                    "fillType0" to "#112233",
                    "textColor" to "#445566",
                    "actor0" to "#778899",
                    "faceColor" to "#aabbcc",
                ),
            ),
        ).value

        assertEquals(SceneColor(0xFF112233), customized.journey.sectionFills.first())
        assertEquals(SceneColor(0xFF445566), customized.journey.textColor)
        assertEquals(base.journey.actorColors, customized.journey.actorColors)
        assertEquals(base.journey.faceColor, customized.journey.faceColor)
    }

    @Test
    fun keepsTimelinePaletteIndependentAndAppliesColorScaleOverridesToBothDiagrams() {
        val base = MermaidTheme.preset(MermaidThemePreset.Forest)
        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = base,
                values = mapOf(
                    "mainBkg" to "#102030",
                    "nodeBorder" to "#203040",
                    "cScale0" to "#304050",
                    "cScaleInv0" to "#405060",
                    "cScaleLabel0" to "#506070",
                    "useGradient" to "false",
                ),
                themeName = MermaidThemePreset.Forest.configName,
            ),
        ).value

        assertEquals(SceneColor(0xFF102030), customized.timeline.mainBackground)
        assertEquals(SceneColor(0xFF203040), customized.timeline.nodeBorder)
        assertEquals(SceneColor(0xFF304050), customized.timeline.sectionFills[0])
        assertEquals(SceneColor(0xFF405060), customized.timeline.sectionInverseColors[0])
        assertEquals(SceneColor(0xFF506070), customized.timeline.sectionLabelColors[0])
        assertEquals(customized.mindmap.sectionFills, customized.timeline.sectionFills)
        assertEquals(
            customized.mindmap.sectionInverseColors,
            customized.timeline.sectionInverseColors,
        )
        assertEquals(
            customized.mindmap.sectionLabelColors,
            customized.timeline.sectionLabelColors,
        )
        assertEquals(false, customized.timeline.useGradient)
        assertEquals(base.timeline.sectionFills[1], customized.timeline.sectionFills[1])
    }

    @Test
    fun derivesGitInverseColorsUsingMermaid12ThemeRules() {
        val expected = mapOf(
            MermaidThemePreset.Base to SceneColor(0xFFB5E6FA),
            MermaidThemePreset.Default to SceneColor(0xFF0000A1),
            MermaidThemePreset.Dark to SceneColor(0xFF5EDD8C),
            MermaidThemePreset.Forest to SceneColor(0xFFB5E6FA),
            MermaidThemePreset.Neutral to SceneColor(0xFFAAAAAA),
            MermaidThemePreset.Neo to SceneColor(0xFFB5E6FA),
            MermaidThemePreset.NeoDark to SceneColor(0xFF0A79A6),
            MermaidThemePreset.Redux to SceneColor(0xFFB5E6FA),
            MermaidThemePreset.ReduxColor to SceneColor(0xFFB5E6FA),
            MermaidThemePreset.ReduxDark to SceneColor(0xFFB5E6FA),
            MermaidThemePreset.ReduxDarkColor to SceneColor(0xFFB5E6FA),
        )

        expected.forEach { (preset, inverseColor) ->
            val customized = assertIs<GMResult.Ok<MermaidTheme>>(
                MermaidTheme.withVariables(
                    theme = MermaidTheme.preset(preset),
                    values = mapOf("git1" to "#c2410c"),
                    themeName = preset.configName,
                ),
            ).value

            assertEquals(SceneColor(0xFFC2410C), customized.gitGraph.colors[1], preset.name)
            assertEquals(inverseColor, customized.gitGraph.inverseColors[1], preset.name)
        }
    }

    @Test
    fun preservesExplicitGitInverseColorOverride() {
        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = MermaidTheme.preset(MermaidThemePreset.Base),
                values = mapOf(
                    "git1" to "#c2410c",
                    "gitInv1" to "#102030",
                ),
                themeName = MermaidThemePreset.Base.configName,
            ),
        ).value

        assertEquals(SceneColor(0xFFC2410C), customized.gitGraph.colors[1])
        assertEquals(SceneColor(0xFF102030), customized.gitGraph.inverseColors[1])
    }

    @Test
    fun preservesNeoGitGraphGradientVariablesAndExplicitOverrides() {
        listOf(MermaidThemePreset.Neo, MermaidThemePreset.NeoDark).forEach { preset ->
            val gitGraph = MermaidTheme.preset(preset).gitGraph

            assertEquals(true, gitGraph.useGradient, preset.name)
            assertEquals(SceneColor(0xFF0042EB), gitGraph.gradientStart, preset.name)
            assertEquals(SceneColor(0xFFEB0042), gitGraph.gradientStop, preset.name)
        }

        val customized = assertIs<GMResult.Ok<MermaidTheme>>(
            MermaidTheme.withVariables(
                theme = MermaidTheme.preset(MermaidThemePreset.Neo),
                values = mapOf(
                    "useGradient" to "false",
                    "gradientStart" to "#112233",
                    "gradientStop" to "#445566",
                ),
                themeName = MermaidThemePreset.Neo.configName,
            ),
        ).value.gitGraph

        assertEquals(false, customized.useGradient)
        assertEquals(SceneColor(0xFF112233), customized.gradientStart)
        assertEquals(SceneColor(0xFF445566), customized.gradientStop)
    }
}
