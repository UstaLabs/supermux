package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/mindmap.md.
 * Upstream document SHA-256: 262394dbc1ab2e4f69be3a06dd9c970ddc6bcf338f877affc631b823c81ae8bc
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:mindmap-doc-fixtures
 */
internal data class MermaidMindmapDocCase(
    val id: String,
    val title: String,
    val source: String,
    val expectedUnsupportedFeature: String?,
)

internal val officialMindmapDocumentationCases: List<MermaidMindmapDocCase> = listOf(
    MermaidMindmapDocCase(
        id = "001_an_example_of_a_mindmap",
        title = "An example of a mindmap.",
        source = """
mindmap
  root((mindmap))
    Origins
      Long history
      ::icon(fa fa-book)
      Popularisation
        British popular psychology author Tony Buzan
    Research
      On effectiveness<br/>and features
      On Automatic creation
        Uses
            Creative techniques
            Strategic planning
            Argument mapping
    Tools
      Pen and paper
      Mermaid
        """.trimIndent(),
        expectedUnsupportedFeature = "Mindmap icon",
    ),
    MermaidMindmapDocCase(
        id = "002_syntax",
        title = "Syntax",
        source = """
mindmap
Root
    A
      B
      C
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "003_square",
        title = "Square",
        source = """
mindmap
    id[I am a square]
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "004_rounded_square",
        title = "Rounded square",
        source = """
mindmap
    id(I am a rounded square)
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "005_circle",
        title = "Circle",
        source = """
mindmap
    id((I am a circle))
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "006_bang",
        title = "Bang",
        source = """
mindmap
    id))I am a bang((
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "007_cloud",
        title = "Cloud",
        source = """
mindmap
    id)I am a cloud(
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "008_hexagon",
        title = "Hexagon",
        source = """
mindmap
    id{{I am a hexagon}}
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "009_default",
        title = "Default",
        source = """
mindmap
    I am the default shape
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "010_icons",
        title = "Icons",
        source = """
mindmap
    Root
        A
        ::icon(fa fa-book)
        B(B)
        ::icon(mdi mdi-skull-outline)
        """.trimIndent(),
        expectedUnsupportedFeature = "Mindmap icon",
    ),
    MermaidMindmapDocCase(
        id = "011_classes",
        title = "Classes",
        source = """
mindmap
    Root
        A[A]
        :::urgent large
        B(B)
        C
        """.trimIndent(),
        expectedUnsupportedFeature = "Mindmap CSS class",
    ),
    MermaidMindmapDocCase(
        id = "012_unclear_indentation",
        title = "Unclear indentation",
        source = """
mindmap
Root
    A
        B
      C
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidMindmapDocCase(
        id = "013_markdown_strings",
        title = "Markdown Strings",
        source = """
mindmap
    id1["`**Root** with
a second line
Unicode works too: 🤓`"]
      id2["`The dog in **the** hog... a *very long text* that wraps to a new line`"]
      id3[Regular labels still works]
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
)
