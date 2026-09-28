package com.swithun.cmpmermaid.core.erdiagram

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.MermaidThemePreset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Mermaid.js 12.0.0: themes/theme-*.js rowOdd/rowEven -> shapes/erBox.ts row fill
class ErRowThemeColorTest {
    private val source = """
        erDiagram
          SESSION { string id string workdir }
    """.trimIndent()
    private val metrics = TextMetricProvider { r -> TextMetrics(width = r.text.length * 8f, height = r.fontSize * r.lineHeight) }

    private fun rowFills(theme: MermaidTheme): List<SceneColor> {
        val result = MermaidEngine().render(source, MermaidRenderContext(textMetrics = metrics, theme = theme))
        assertIs<GMResult.Ok<*>>(result)
        val scene = (result as GMResult.Ok).value
        return scene.elements.filterIsInstance<SceneShape>().filter { it.id.contains("-row-") }.map { it.fill }
    }

    @Test
    fun defaultThemeKeepsNearWhiteRows() {
        assertEquals(listOf(SceneColor(0xFFFFFFFF), SceneColor(0xFFF7F7F7)), rowFills(MermaidTheme.preset(MermaidThemePreset.Default)))
    }

    @Test
    fun darkThemeRowsAreDerivedFromMainBkgNotWhite() {
        val fills = rowFills(MermaidTheme.preset(MermaidThemePreset.Dark))
        assertEquals(2, fills.size)
        assertTrue(fills.none { it == SceneColor(0xFFFFFFFF) || it == SceneColor(0xFFF7F7F7) }, "dark rows must not be white: $fills")
    }

    @Test
    fun themeVariablesOverrideRows() {
        val themed = MermaidTheme.withVariables(
            MermaidTheme.preset(MermaidThemePreset.Default),
            mapOf("rowOdd" to "#112233", "rowEven" to "#445566"),
        )
        assertIs<GMResult.Ok<MermaidTheme>>(themed)
        assertEquals(listOf(SceneColor(0xFF112233), SceneColor(0xFF445566)), rowFills(themed.value))
    }
}
