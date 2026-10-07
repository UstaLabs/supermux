package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlowElkLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * 8f),
                height = 18f,
            )
        },
    )

    @Test
    fun rejectsEveryMermaid12ElkAlgorithmWithoutJavaScriptFallback() {
        val source = """
            flowchart LR
              A[Parse] --> B[Layout] --> C[Render]
        """.trimIndent()
        val layouts = listOf(
            "elk",
            "elk.layered",
            "elk.stress",
            "elk.force",
            "elk.mrtree",
            "elk.sporeOverlap",
            "elk.box",
            "elk.rectpacking",
        )

        layouts.forEach { layout ->
            val result = engine.render(
                source,
                context.copy(options = context.options.copy(layout = layout)),
            )
            val error = assertIs<GMResult.Err<MermaidError.UnsupportedFeature>>(
                result,
                layout,
            ).error

            assertEquals("ELK layout", error.feature)
        }
    }

    @Test
    fun rejectsFlowchartElkHeaderWithoutFallingBackToDagre() {
        val result = engine.render(
            """
                flowchart-elk LR
                  A --> B
            """.trimIndent(),
            context,
        )
        val error = assertIs<GMResult.Err<MermaidError.UnsupportedFeature>>(result).error

        assertEquals("ELK layout", error.feature)
    }
}
