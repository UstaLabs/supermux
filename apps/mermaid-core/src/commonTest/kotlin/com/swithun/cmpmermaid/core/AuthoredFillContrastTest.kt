package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** supermux: a pastel `fill:` without `color:` must not keep the dark theme's light text. */
class AuthoredFillContrastTest {
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { r -> TextMetrics(width = r.text.length * 8f, height = 18f) },
        theme = MermaidTheme.preset(MermaidThemePreset.Dark),
        options = MermaidRenderOptions(layout = "dagre"),
    )

    private fun textColor(source: String, label: String): SceneColor {
        val scene = assertIs<GMResult.Ok<MermaidScene>>(MermaidEngine().render(source, context)).value
        return scene.elements.filterIsInstance<SceneText>().first { it.text == label }.color
    }

    @Test
    fun lightFillWithoutColorGetsDarkText() {
        val c = textColor("flowchart LR\n A[Pale] --> B[Plain]\n style A fill:#e8f0fe", "Pale")
        assertEquals(SceneColor(0xFF1F2328), c)
    }

    @Test
    fun explicitColorAndThemeFillsAreUntouched() {
        val explicit = textColor("flowchart LR\n A[Pale]\n style A fill:#e8f0fe,color:#ff0000", "Pale")
        assertEquals(SceneColor(0xFFFF0000), explicit)
        val plain = textColor("flowchart LR\n A[Pale] --> B[Plain]", "Plain")
        assertEquals(context.theme.nodeText, plain)
    }

    @Test
    fun lightSubgraphFillGetsDarkTitle() {
        val c = textColor("flowchart LR\n subgraph S [Group]\n A\n end\n style S fill:#fff4e5", "Group")
        assertEquals(SceneColor(0xFF1F2328), c)
    }
}
