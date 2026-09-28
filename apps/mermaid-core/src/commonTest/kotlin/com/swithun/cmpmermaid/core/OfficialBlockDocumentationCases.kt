package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/block.md.
 * Upstream document SHA-256: 3779f5db8f6da14abfa77bb7389854c3f6c9fe472c5ade0635dee9028449f630
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:block-doc-fixtures
 */
internal data class MermaidBlockDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialBlockDocumentationCases: List<MermaidBlockDocCase> = listOf(
    MermaidBlockDocCase(
        id = "001_introduction_to_block_diagrams",
        title = "Introduction to Block Diagrams",
        source = """
block
columns 1
  db(("DB"))
  blockArrowId6<["&nbsp;&nbsp;&nbsp;"]>(down)
  block:ID
    A
    B["A wide one in the middle"]
    C
  end
  space
  D
  ID --> D
  C --> D
  style B fill:#969,stroke:#333,stroke-width:4px
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "002_basic_structure",
        title = "Basic Structure",
        source = """
block
  a b c
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "003_column_usage",
        title = "Column Usage",
        source = """
block
  columns 3
  a b c d
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "004_spanning_multiple_columns",
        title = "Spanning Multiple Columns",
        source = """
block
  columns 3
  a["A label"] b:2 c:2 d
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "005_nested_blocks",
        title = "Nested Blocks",
        source = """
block
    block
      D
    end
    A["A: I am a wide one"]
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "006_adjusting_widths",
        title = "Adjusting Widths",
        source = """
block
  columns 3
  a:3
  block:group1:2
    columns 2
    h i j k
  end
  g
  block:group2:3
    %% columns auto (default)
    l m n o p q r
  end
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "007_adjusting_widths",
        title = "Adjusting Widths",
        source = """
block
  block
    columns 1
    a["A label"] b c d
  end
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "008_example_round_edged_block",
        title = "Example - Round Edged Block",
        source = """
block
    id1("This is the text in the box")
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "009_example_stadium_shaped_block",
        title = "Example - Stadium-Shaped Block",
        source = """
block
    id1(["This is the text in the box"])
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "010_example_subroutine_shape",
        title = "Example - Subroutine Shape",
        source = """
block
    id1[["This is the text in the box"]]
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "011_example_cylindrical_shape",
        title = "Example - Cylindrical Shape",
        source = """
block
    id1[("Database")]
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "012_example_circle_shape",
        title = "Example - Circle Shape",
        source = """
block
    id1(("This is the text in the circle"))
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "013_example_asymmetric_rhombus_and_hexagon_shapes",
        title = "Example - Asymmetric, Rhombus, and Hexagon Shapes",
        source = """
block
  id1>"This is the text in the box"]
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "014_example_asymmetric_rhombus_and_hexagon_shapes",
        title = "Example - Asymmetric, Rhombus, and Hexagon Shapes",
        source = """
block
    id1{"This is the text in the box"}
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "015_example_asymmetric_rhombus_and_hexagon_shapes",
        title = "Example - Asymmetric, Rhombus, and Hexagon Shapes",
        source = """
block
    id1{{"This is the text in the box"}}
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "016_example_parallelogram_and_trapezoid_shapes",
        title = "Example - Parallelogram and Trapezoid Shapes",
        source = """
block
  id1[/"This is the text in the box"/]
  id2[\"This is the text in the box"\]
  A[/"Christmas"\]
  B[\"Go shopping"/]
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "017_example_double_circle",
        title = "Example - Double Circle",
        source = """
block
    id1((("This is the text in the circle")))
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "018_example_block_arrows",
        title = "Example - Block Arrows",
        source = """
block
  blockArrowId<["Label"]>(right)
  blockArrowId2<["Label"]>(left)
  blockArrowId3<["Label"]>(up)
  blockArrowId4<["Label"]>(down)
  blockArrowId5<["Label"]>(x)
  blockArrowId6<["Label"]>(y)
  blockArrowId7<["Label"]>(x, down)
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "019_example_space_blocks",
        title = "Example - Space Blocks",
        source = """
block
  columns 3
  a space b
  c   d   e
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "020_example_space_blocks",
        title = "Example - Space Blocks",
        source = """
block
  ida space:3 idb idc
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "021_basic_linking_and_arrow_types",
        title = "Basic Linking and Arrow Types",
        source = """
block
  A space B
  A-->B
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "022_text_on_links",
        title = "Text on Links",
        source = """
block
  A space:2 B
  A-- "X" -->B
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "023_text_on_links",
        title = "Text on Links",
        source = """
block
columns 1
  db(("DB"))
  blockArrowId6<["&nbsp;&nbsp;&nbsp;"]>(down)
  block:ID
    A
    B["A wide one in the middle"]
    C
  end
  space
  D
  ID --> D
  C --> D
  style B fill:#939,stroke:#333,stroke-width:4px
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "024_example_styling_a_single_block",
        title = "Example - Styling a Single Block",
        source = """
block
  id1 space id2
  id1("Start")-->id2("Stop")
  style id1 fill:#636,stroke:#333,stroke-width:4px
  style id2 fill:#bbf,stroke:#f66,stroke-width:2px,color:#fff,stroke-dasharray: 5 5
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "025_example_styling_a_single_class",
        title = "Example - Styling a Single Class",
        source = """
block
  A space B
  A-->B
  classDef blue fill:#6e6ce6,stroke:#333,stroke-width:4px;
  class A blue
  style B fill:#bbf,stroke:#f66,stroke-width:2px,color:#fff,stroke-dasharray: 5 5
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "026_example_system_architecture",
        title = "Example - System Architecture",
        source = """
block
  columns 3
  Frontend blockArrowId6<[" "]>(right) Backend
  space:2 down<[" "]>(down)
  Disk left<[" "]>(left) Database[("Database")]

  classDef front fill:#696,stroke:#333;
  classDef back fill:#969,stroke:#333;
  class Frontend front
  class Backend,Database back
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "027_example_business_process_flow",
        title = "Example - Business Process Flow",
        source = """
block
  columns 3
  Start(("Start")) space:2
  down<[" "]>(down) space:2
  Decision{{"Make Decision"}} right<["Yes"]>(right) Process1["Process A"]
  downAgain<["No"]>(down) space r3<["Done"]>(down)
  Process2["Process B"] r2<["Done"]>(right) End(("End"))

  style Start fill:#969;
  style End fill:#696;
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "028_example_incorrect_linking",
        title = "Example - Incorrect Linking",
        source = """
block
  A space B
  A --> B
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "029_example_misplaced_styling",
        title = "Example - Misplaced Styling",
        source = """
block
    A
    style A fill#969;
        """.trimIndent(),
    ),
    MermaidBlockDocCase(
        id = "030_example_misplaced_styling",
        title = "Example - Misplaced Styling",
        source = """
block
  A
  style A fill:#969,stroke:#333;
        """.trimIndent(),
    ),
)
