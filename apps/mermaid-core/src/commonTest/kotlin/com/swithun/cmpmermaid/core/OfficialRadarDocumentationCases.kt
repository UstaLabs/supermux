package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/radar.md.
 * Upstream document SHA-256: 61f9c4575b76df9f4af264cd29b4987543df227952f6daddfd28ca5f1ab40808
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:radar-doc-fixtures
 */
internal data class MermaidRadarDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialRadarDocumentationCases: List<MermaidRadarDocCase> = listOf(
    MermaidRadarDocCase(
        id = "001_examples",
        title = "Examples",
        source = """
---
title: "Grades"
---
radar-beta
  axis m["Math"], s["Science"], e["English"]
  axis h["History"], g["Geography"], a["Art"]
  curve a["Alice"]{85, 90, 80, 70, 75, 90}
  curve b["Bob"]{70, 75, 85, 80, 90, 85}

  max 100
  min 0
        """.trimIndent(),
    ),
    MermaidRadarDocCase(
        id = "002_examples",
        title = "Examples",
        source = """
radar-beta
  title Restaurant Comparison
  axis food["Food Quality"], service["Service"], price["Price"]
  axis ambiance["Ambiance"]

  curve a["Restaurant A"]{4, 3, 2, 4}
  curve b["Restaurant B"]{3, 4, 3, 3}
  curve c["Restaurant C"]{2, 3, 4, 2}
  curve d["Restaurant D"]{2, 2, 4, 3}

  graticule polygon
  max 5
        """.trimIndent(),
    ),
    MermaidRadarDocCase(
        id = "003_example_on_config_and_theme",
        title = "Example on config and theme",
        source = """
---
config:
  radar:
    axisScaleFactor: 0.25
    curveTension: 0.1
  theme: base
  themeVariables:
    cScale0: "#FF0000"
    cScale1: "#00FF00"
    cScale2: "#0000FF"
    radar:
      curveOpacity: 0
---
radar-beta
  axis A, B, C, D, E
  curve c1{1,2,3,4,5}
  curve c2{5,4,3,2,1}
  curve c3{3,3,3,3,3}
        """.trimIndent(),
    ),
)
