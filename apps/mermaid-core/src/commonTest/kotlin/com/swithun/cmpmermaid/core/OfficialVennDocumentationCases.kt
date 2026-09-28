package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/venn.md.
 * Upstream document SHA-256: f12ed068bd3a6f7dd5acafe976570388a95f3c3d242c9e758be2e6128b3f447f
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:venn-doc-fixtures
 */
internal data class MermaidVennDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialVennDocumentationCases: List<MermaidVennDocCase> = listOf(
    MermaidVennDocCase(
        id = "001_with_the_defaults",
        title = "With the defaults",
        source = """
venn-beta
  title What makes a good feature
  set Desirable
  set Feasible
  set Viable
  union Desirable,Feasible["Buildable"]
  union Feasible,Viable["Sustainable"]
  union Desirable,Viable["Marketable"]
  union Desirable,Feasible,Viable["Ship it"]
        """.trimIndent(),
    ),
    MermaidVennDocCase(
        id = "002_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
---
venn-beta
  title What makes a good feature
  set Desirable
  set Feasible
  set Viable
  union Desirable,Feasible["Buildable"]
  union Feasible,Viable["Sustainable"]
  union Desirable,Viable["Marketable"]
  union Desirable,Feasible,Viable["Ship it"]
        """.trimIndent(),
    ),
    MermaidVennDocCase(
        id = "003_syntax",
        title = "Syntax",
        source = """
venn-beta
  title "Team overlap"
  set Frontend
  set Backend
  union Frontend,Backend["APIs"]
        """.trimIndent(),
    ),
    MermaidVennDocCase(
        id = "004_labels",
        title = "Labels",
        source = """
venn-beta
  set A["Alpha"]
  set B["Beta"]
  union A,B["AB"]
        """.trimIndent(),
    ),
    MermaidVennDocCase(
        id = "005_higher_arity_unions",
        title = "Higher-arity unions",
        source = """
venn-beta
  set Desirable
  set Feasible
  set Viable
  union Desirable,Feasible,Viable["Innovation"]
        """.trimIndent(),
    ),
    MermaidVennDocCase(
        id = "006_sizes",
        title = "Sizes",
        source = """
venn-beta
  set A["Alpha"]:20
  set B["Beta"]:12
  union A,B["AB"]:3
        """.trimIndent(),
    ),
    MermaidVennDocCase(
        id = "007_text_nodes",
        title = "Text nodes",
        source = """
venn-beta
  set A["Frontend"]
    text A1["React"]
    text A2["Design Systems"]
  set B["Backend"]
    text B1["API"]
  union A,B["Shared"]
    text AB1["OpenAPI"]
        """.trimIndent(),
    ),
    MermaidVennDocCase(
        id = "008_styling",
        title = "Styling",
        source = """
venn-beta
  set A["Alpha"]:20
    text A1["React"]
    text A2["Design Systems"]
  set B["Beta"]:12
  union A,B["AB"]:3
  style A fill:#ff6b6b
  style A,B color:#333
  style A1 color:red
        """.trimIndent(),
    ),
)
