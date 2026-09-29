package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/treemap.md.
 * Upstream document SHA-256: a32940b952d87164dc18ce9d8ea679e94f4c0de0bf44a5669b40033170c70ce1
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:treemap-doc-fixtures
 */
internal data class MermaidTreemapDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialTreemapDocumentationCases: List<MermaidTreemapDocCase> = listOf(
    MermaidTreemapDocCase(
        id = "001_basic_treemap",
        title = "Basic Treemap",
        source = """
treemap-beta
"Category A"
    "Item A1": 10
    "Item A2": 20
"Category B"
    "Item B1": 15
    "Item B2": 25
        """.trimIndent(),
    ),
    MermaidTreemapDocCase(
        id = "002_hierarchical_treemap",
        title = "Hierarchical Treemap",
        source = """
treemap-beta
"Products"
    "Electronics"
        "Phones": 50
        "Computers": 30
        "Accessories": 20
    "Clothing"
        "Men's": 40
        "Women's": 40
        """.trimIndent(),
    ),
    MermaidTreemapDocCase(
        id = "003_treemap_with_styling",
        title = "Treemap with Styling",
        source = """
treemap-beta
"Section 1"
    "Leaf 1.1": 12
    "Section 1.2":::class1
      "Leaf 1.2.1": 12
"Section 2"
    "Leaf 2.1": 20:::class1
    "Leaf 2.2": 25
    "Leaf 2.3": 12

classDef class1 fill:red,color:blue,stroke:#FFD600;
        """.trimIndent(),
    ),
    MermaidTreemapDocCase(
        id = "004_using_classdef_for_styling",
        title = "Using classDef for Styling",
        source = """
treemap-beta
"Main"
    "A": 20
    "B":::important
        "B1": 10
        "B2": 15
    "C": 5

classDef important fill:#f96,stroke:#333,stroke-width:2px;
        """.trimIndent(),
    ),
    MermaidTreemapDocCase(
        id = "005_theme_configuration",
        title = "Theme Configuration",
        source = """
---
config:
    theme: 'forest'
---
treemap-beta
"Category A"
    "Item A1": 10
    "Item A2": 20
"Category B"
    "Item B1": 15
    "Item B2": 25
        """.trimIndent(),
    ),
    MermaidTreemapDocCase(
        id = "006_diagram_padding",
        title = "Diagram Padding",
        source = """
---
config:
  treemap:
    diagramPadding: 200
---
treemap-beta
"Category A"
    "Item A1": 10
    "Item A2": 20
"Category B"
    "Item B1": 15
    "Item B2": 25
        """.trimIndent(),
    ),
    MermaidTreemapDocCase(
        id = "007_value_formatting",
        title = "Value Formatting",
        source = """
---
config:
  treemap:
    valueFormat: '${'$'}0,0'
---
treemap-beta
"Budget"
    "Operations"
        "Salaries": 700000
        "Equipment": 200000
        "Supplies": 100000
    "Marketing"
        "Advertising": 400000
        "Events": 100000
        """.trimIndent(),
    ),
    MermaidTreemapDocCase(
        id = "008_value_formatting",
        title = "Value Formatting",
        source = """
---
config:
  treemap:
    valueFormat: '${'$'}.1%'
---
treemap-beta
"Market Share"
    "Company A": 0.35
    "Company B": 0.25
    "Company C": 0.15
    "Others": 0.25
        """.trimIndent(),
    ),
)
