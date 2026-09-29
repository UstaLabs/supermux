package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/pie.md.
 * Upstream document SHA-256: ca76077badb79e1d2f83ebe4ac7575778425e52f5f6928bdab5024ec70576852
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:pie-doc-fixtures
 */
internal data class MermaidPieDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialPieDocumentationCases: List<MermaidPieDocCase> = listOf(
    MermaidPieDocCase(
        id = "001_pie_chart_diagrams",
        title = "Pie chart diagrams",
        source = """
pie title Pets adopted by volunteers
    "Dogs" : 386
    "Cats" : 85
    "Rats" : 15
        """.trimIndent(),
    ),
    MermaidPieDocCase(
        id = "002_example",
        title = "Example",
        source = """
---
config:
  pie:
    textPosition: 0.5
    donutHole: 0.2
    highlightSlice: Potassium
  themeVariables:
    pieOuterStrokeWidth: "5px"
---
pie showData
    title Key elements in Product X
    "Calcium" : 42.96
    "Potassium" : 50.05
    "Magnesium" : 10.01
    "Iron" :  5
        """.trimIndent(),
    ),
)
