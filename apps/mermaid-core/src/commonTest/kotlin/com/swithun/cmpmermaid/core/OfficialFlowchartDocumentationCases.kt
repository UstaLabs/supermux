package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0 packages/mermaid/src/docs/syntax/flowchart.md.
 * Upstream document SHA-256: 3af71671eded9283eb70777bdcbfb83722bf7c2ae4863263cc3699939e5a0447
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:flowchart-doc-fixtures
 */
internal data class MermaidFlowchartDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialFlowchartDocumentationCases: List<MermaidFlowchartDocCase> = listOf(
    MermaidFlowchartDocCase(
        id = "001_a_node_default",
        title = "A node (default)",
        source = """
---
title: Node
---
flowchart LR
    id
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "002_a_node_with_text",
        title = "A node with text",
        source = """
---
title: Node with text
---
flowchart LR
    id1[This is the text in the box]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "003_unicode_text",
        title = "Unicode text",
        source = """
flowchart LR
    id["This ❤ Unicode"]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "004_markdown_formatting",
        title = "Markdown formatting",
        source = """
---
config:
  htmlLabels: false
---
flowchart LR
    markdown["`This **is** _Markdown_`"]
    newLines["`Line1
    Line 2
    Line 3`"]
    markdown --> newLines
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "005_direction",
        title = "Direction",
        source = """
flowchart TD
    Start --> Stop
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "006_direction",
        title = "Direction",
        source = """
flowchart LR
    Start --> Stop
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "007_with_the_defaults",
        title = "With the defaults",
        source = """
flowchart LR
  subgraph Client
    UI[Web app]
    Cache[(Local cache)]
  end
  subgraph Services
    API[API gateway]
    Auth[Auth service]
    Orders[Order service]
  end
  subgraph Storage
    DB[(Orders DB)]
  end
  UI --> API
  UI --> Cache
  API --> Auth
  API --> Orders
  Orders --> DB
  Auth -. token .-> UI
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "008_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
  layout: dagre
---
flowchart LR
  subgraph Client
    UI[Web app]
    Cache[(Local cache)]
  end
  subgraph Services
    API[API gateway]
    Auth[Auth service]
    Orders[Order service]
  end
  subgraph Storage
    DB[(Orders DB)]
  end
  UI --> API
  UI --> Cache
  API --> Auth
  API --> Orders
  Orders --> DB
  Auth -. token .-> UI
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "009_a_node_with_round_edges",
        title = "A node with round edges",
        source = """
flowchart LR
    id1(This is the text in the box)
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "010_a_stadium_shaped_node",
        title = "A stadium-shaped node",
        source = """
flowchart LR
    id1([This is the text in the box])
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "011_a_node_in_a_subroutine_shape",
        title = "A node in a subroutine shape",
        source = """
flowchart LR
    id1[[This is the text in the box]]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "012_a_node_in_a_cylindrical_shape",
        title = "A node in a cylindrical shape",
        source = """
flowchart LR
    id1[(Database)]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "013_a_node_in_the_form_of_a_circle",
        title = "A node in the form of a circle",
        source = """
flowchart LR
    id1((This is the text in the circle))
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "014_a_node_in_an_asymmetric_shape",
        title = "A node in an asymmetric shape",
        source = """
flowchart LR
    id1>This is the text in the box]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "015_a_node_rhombus",
        title = "A node (rhombus)",
        source = """
flowchart LR
    id1{This is the text in the box}
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "016_a_hexagon_node",
        title = "A hexagon node",
        source = """
flowchart LR
    id1{{This is the text in the box}}
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "017_parallelogram",
        title = "Parallelogram",
        source = """
flowchart TD
    id1[/This is the text in the box/]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "018_parallelogram_alt",
        title = "Parallelogram alt",
        source = """
flowchart TD
    id1[\This is the text in the box\]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "019_trapezoid",
        title = "Trapezoid",
        source = """
flowchart TD
    A[/Christmas\]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "020_trapezoid_alt",
        title = "Trapezoid alt",
        source = """
flowchart TD
    B[\Go shopping/]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "021_double_circle",
        title = "Double circle",
        source = """
flowchart TD
    id1(((This is the text in the circle)))
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "022_example_flowchart_with_new_shapes",
        title = "Example Flowchart with New Shapes",
        source = """
flowchart RL
    A@{ shape: manual-file, label: "File Handling"}
    B@{ shape: manual-input, label: "User Input"}
    C@{ shape: docs, label: "Multiple Documents"}
    D@{ shape: procs, label: "Process Automation"}
    E@{ shape: paper-tape, label: "Paper Records"}
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "023_process",
        title = "Process",
        source = """
flowchart TD
    A@{ shape: rect, label: "This is a process" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "024_event",
        title = "Event",
        source = """
flowchart TD
    A@{ shape: rounded, label: "This is an event" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "025_terminal_point_stadium",
        title = "Terminal Point (Stadium)",
        source = """
flowchart TD
    A@{ shape: stadium, label: "Terminal point" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "026_subprocess",
        title = "Subprocess",
        source = """
flowchart TD
    A@{ shape: subproc, label: "This is a subprocess" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "027_database_cylinder",
        title = "Database (Cylinder)",
        source = """
flowchart TD
    A@{ shape: cyl, label: "Database" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "028_start_circle",
        title = "Start (Circle)",
        source = """
flowchart TD
    A@{ shape: circle, label: "Start" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "029_odd",
        title = "Odd",
        source = """
flowchart TD
    A@{ shape: odd, label: "Odd shape" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "030_decision_diamond",
        title = "Decision (Diamond)",
        source = """
flowchart TD
    A@{ shape: diamond, label: "Decision" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "031_prepare_conditional_hexagon",
        title = "Prepare Conditional (Hexagon)",
        source = """
flowchart TD
    A@{ shape: hex, label: "Prepare conditional" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "032_data_input_output_lean_right",
        title = "Data Input/Output (Lean Right)",
        source = """
flowchart TD
    A@{ shape: lean-r, label: "Input/Output" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "033_data_input_output_lean_left",
        title = "Data Input/Output (Lean Left)",
        source = """
flowchart TD
    A@{ shape: lean-l, label: "Output/Input" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "034_datastore_top_and_bottom_border",
        title = "Datastore (Top and bottom border)",
        source = """
flowchart TD
    A@{ shape: datastore, label: "Datastore" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "035_priority_action_trapezoid_base_bottom",
        title = "Priority Action (Trapezoid Base Bottom)",
        source = """
flowchart TD
    A@{ shape: trap-b, label: "Priority action" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "036_manual_operation_trapezoid_base_top",
        title = "Manual Operation (Trapezoid Base Top)",
        source = """
flowchart TD
    A@{ shape: trap-t, label: "Manual operation" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "037_stop_double_circle",
        title = "Stop (Double Circle)",
        source = """
flowchart TD
    A@{ shape: dbl-circ, label: "Stop" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "038_text_block",
        title = "Text Block",
        source = """
flowchart TD
    A@{ shape: text, label: "This is a text block" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "039_card_notched_rectangle",
        title = "Card (Notched Rectangle)",
        source = """
flowchart TD
    A@{ shape: notch-rect, label: "Card" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "040_lined_shaded_process",
        title = "Lined/Shaded Process",
        source = """
flowchart TD
    A@{ shape: lin-rect, label: "Lined process" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "041_start_small_circle",
        title = "Start (Small Circle)",
        source = """
flowchart TD
    A@{ shape: sm-circ, label: "Small start" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "042_stop_framed_circle",
        title = "Stop (Framed Circle)",
        source = """
flowchart TD
    A@{ shape: framed-circle, label: "Stop" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "043_fork_join_long_rectangle",
        title = "Fork/Join (Long Rectangle)",
        source = """
flowchart TD
    A@{ shape: fork, label: "Fork or Join" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "044_collate_hourglass",
        title = "Collate (Hourglass)",
        source = """
flowchart TD
    A@{ shape: hourglass, label: "Collate" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "045_comment_curly_brace",
        title = "Comment (Curly Brace)",
        source = """
flowchart TD
    A@{ shape: comment, label: "Comment" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "046_comment_right_curly_brace_right",
        title = "Comment Right (Curly Brace Right)",
        source = """
flowchart TD
    A@{ shape: brace-r, label: "Comment" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "047_comment_with_braces_on_both_sides",
        title = "Comment with braces on both sides",
        source = """
flowchart TD
    A@{ shape: braces, label: "Comment" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "048_com_link_lightning_bolt",
        title = "Com Link (Lightning Bolt)",
        source = """
flowchart TD
    A@{ shape: bolt, label: "Communication link" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "049_document",
        title = "Document",
        source = """
flowchart TD
    A@{ shape: doc, label: "Document" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "050_delay_half_rounded_rectangle",
        title = "Delay (Half-Rounded Rectangle)",
        source = """
flowchart TD
    A@{ shape: delay, label: "Delay" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "051_direct_access_storage_horizontal_cylinder",
        title = "Direct Access Storage (Horizontal Cylinder)",
        source = """
flowchart TD
    A@{ shape: das, label: "Direct access storage" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "052_disk_storage_lined_cylinder",
        title = "Disk Storage (Lined Cylinder)",
        source = """
flowchart TD
    A@{ shape: lin-cyl, label: "Disk storage" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "053_display_curved_trapezoid",
        title = "Display (Curved Trapezoid)",
        source = """
flowchart TD
    A@{ shape: curv-trap, label: "Display" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "054_divided_process_divided_rectangle",
        title = "Divided Process (Divided Rectangle)",
        source = """
flowchart TD
    A@{ shape: div-rect, label: "Divided process" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "055_extract_small_triangle",
        title = "Extract (Small Triangle)",
        source = """
flowchart TD
    A@{ shape: tri, label: "Extract" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "056_internal_storage_window_pane",
        title = "Internal Storage (Window Pane)",
        source = """
flowchart TD
    A@{ shape: win-pane, label: "Internal storage" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "057_junction_filled_circle",
        title = "Junction (Filled Circle)",
        source = """
flowchart TD
    A@{ shape: f-circ, label: "Junction" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "058_lined_document",
        title = "Lined Document",
        source = """
flowchart TD
    A@{ shape: lin-doc, label: "Lined document" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "059_loop_limit_notched_pentagon",
        title = "Loop Limit (Notched Pentagon)",
        source = """
flowchart TD
    A@{ shape: notch-pent, label: "Loop limit" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "060_manual_file_flipped_triangle",
        title = "Manual File (Flipped Triangle)",
        source = """
flowchart TD
    A@{ shape: flip-tri, label: "Manual file" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "061_manual_input_sloped_rectangle",
        title = "Manual Input (Sloped Rectangle)",
        source = """
flowchart TD
    A@{ shape: sl-rect, label: "Manual input" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "062_multi_document_stacked_document",
        title = "Multi-Document (Stacked Document)",
        source = """
flowchart TD
    A@{ shape: docs, label: "Multiple documents" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "063_multi_process_stacked_rectangle",
        title = "Multi-Process (Stacked Rectangle)",
        source = """
flowchart TD
    A@{ shape: processes, label: "Multiple processes" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "064_paper_tape_flag",
        title = "Paper Tape (Flag)",
        source = """
flowchart TD
    A@{ shape: flag, label: "Paper tape" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "065_stored_data_bow_tie_rectangle",
        title = "Stored Data (Bow Tie Rectangle)",
        source = """
flowchart TD
    A@{ shape: bow-rect, label: "Stored data" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "066_summary_crossed_circle",
        title = "Summary (Crossed Circle)",
        source = """
flowchart TD
    A@{ shape: cross-circ, label: "Summary" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "067_tagged_document",
        title = "Tagged Document",
        source = """
flowchart TD
    A@{ shape: tag-doc, label: "Tagged document" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "068_tagged_process_tagged_rectangle",
        title = "Tagged Process (Tagged Rectangle)",
        source = """
flowchart TD
    A@{ shape: tag-rect, label: "Tagged process" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "069_icon_shape",
        title = "Icon Shape",
        source = """
flowchart TD
    A@{ icon: "fa:user", form: "square", label: "User Icon", pos: "t", h: 60 }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "070_parameters",
        title = "Parameters",
        source = """
flowchart TD
  %% My image with a constrained aspect ratio
  A@{ img: "https://mermaid.js.org/favicon.svg", label: "My example image label", pos: "t", h: 60, constraint: "on" }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "071_a_link_with_arrow_head",
        title = "A link with arrow head",
        source = """
flowchart LR
    A-->B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "072_an_open_link",
        title = "An open link",
        source = """
flowchart LR
    A --- B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "073_text_on_links",
        title = "Text on links",
        source = """
flowchart LR
    A-- This is the text! ---B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "074_text_on_links",
        title = "Text on links",
        source = """
flowchart LR
    A---|This is the text|B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "075_a_link_with_arrow_head_and_text",
        title = "A link with arrow head and text",
        source = """
flowchart LR
    A-->|text|B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "076_a_link_with_arrow_head_and_text",
        title = "A link with arrow head and text",
        source = """
flowchart LR
    A-- text -->B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "077_dotted_link",
        title = "Dotted link",
        source = """
flowchart LR
   A-.->B;
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "078_dotted_link_with_text",
        title = "Dotted link with text",
        source = """
flowchart LR
   A-. text .-> B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "079_thick_link",
        title = "Thick link",
        source = """
flowchart LR
   A ==> B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "080_thick_link_with_text",
        title = "Thick link with text",
        source = """
flowchart LR
   A == text ==> B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "081_an_invisible_link",
        title = "An invisible link",
        source = """
flowchart LR
    A ~~~ B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "082_chaining_of_links",
        title = "Chaining of links",
        source = """
flowchart LR
   A -- text --> B -- text2 --> C
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "083_chaining_of_links",
        title = "Chaining of links",
        source = """
flowchart LR
   a --> b & c--> d
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "084_chaining_of_links",
        title = "Chaining of links",
        source = """
flowchart TB
    A & B--> C & D
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "085_chaining_of_links",
        title = "Chaining of links",
        source = """
flowchart TB
    A --> C
    A --> D
    B --> C
    B --> D
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "086_attaching_an_id_to_edges",
        title = "Attaching an ID to Edges",
        source = """
flowchart LR
  A e1@--> B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "087_turning_an_animation_on",
        title = "Turning an Animation On",
        source = """
flowchart LR
  A e1@==> B
  e1@{ animate: true }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "088_selecting_type_of_animation",
        title = "Selecting Type of Animation",
        source = """
flowchart LR
  A e1@--> B
  e1@{ animation: fast }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "089_using_classdef_statements_for_animations",
        title = "Using classDef Statements for Animations",
        source = """
flowchart LR
  A e1@--> B
  classDef animate stroke-dasharray: 9,5,stroke-dashoffset: 900,animation: dash 25s linear infinite;
  class e1 animate
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "090_circle_edge_example",
        title = "Circle edge example",
        source = """
flowchart LR
    A --o B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "091_cross_edge_example",
        title = "Cross edge example",
        source = """
flowchart LR
    A --x B
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "092_multi_directional_arrows",
        title = "Multi directional arrows",
        source = """
flowchart LR
    A o--o B
    B <--> C
    C x--x D
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "093_minimum_length_of_a_link",
        title = "Minimum length of a link",
        source = """
flowchart TD
    A[Start] --> B{Is it?}
    B -->|Yes| C[OK]
    C --> D[Rethink]
    D --> B
    B ---->|No| E[End]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "094_minimum_length_of_a_link",
        title = "Minimum length of a link",
        source = """
flowchart TD
    A[Start] --> B{Is it?}
    B -- Yes --> C[OK]
    C --> D[Rethink]
    D --> B
    B -- No ----> E[End]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "095_special_characters_that_break_syntax",
        title = "Special characters that break syntax",
        source = """
flowchart LR
    id1["This is the (text) in the box"]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "096_entity_codes_to_escape_characters",
        title = "Entity codes to escape characters",
        source = """
flowchart LR
        A["A double quote:#quot;"] --> B["A dec char:#9829;"]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "097_subgraphs",
        title = "Subgraphs",
        source = """
flowchart TB
    c1-->a2
    subgraph one
    a1-->a2
    end
    subgraph two
    b1-->b2
    end
    subgraph three
    c1-->c2
    end
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "098_subgraphs",
        title = "Subgraphs",
        source = """
flowchart TB
    c1-->a2
    subgraph ide1 [one]
    a1-->a2
    end
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "099_flowcharts",
        title = "flowcharts",
        source = """
flowchart TB
    c1-->a2
    subgraph one
    a1-->a2
    end
    subgraph two
    b1-->b2
    end
    subgraph three
    c1-->c2
    end
    one --> two
    three --> two
    two --> c2
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "100_direction_in_subgraphs",
        title = "Direction in subgraphs",
        source = """
flowchart LR
  subgraph TOP
    direction TB
    subgraph B1
        direction RL
        i1 -->f1
    end
    subgraph B2
        direction BT
        i2 -->f2
    end
  end
  A --> TOP --> B
  B1 --> B2
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "101_limitation",
        title = "Limitation",
        source = """
flowchart LR
    subgraph subgraph1
        direction TB
        top1[top] --> bottom1[bottom]
    end
    subgraph subgraph2
        direction TB
        top2[top] --> bottom2[bottom]
    end
    %% ^ These subgraphs are identical, except for the links to them:

    %% Link *to* subgraph1: subgraph1 direction is maintained
    outside --> subgraph1
    %% Link *within* subgraph2:
    %% subgraph2 inherits the direction of the top-level graph (LR)
    outside ---> top2
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "102_collapsible_subgraphs_v11_17_0",
        title = "Collapsible subgraphs (v11.17.0+)",
        source = """
flowchart TD
    Start --> one
    subgraph one [My Group]
        A --> B
        B --> C
    end
    one --> End
    one@{ view: collapsed }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "103_markdown_strings",
        title = "Markdown Strings",
        source = """
---
config:
  htmlLabels: false
---
flowchart LR
subgraph "One"
  a("`The **cat**
  in the hat`") -- "edge label" --> b{{"`The **dog** in the hog`"}}
end
subgraph "`**Two**`"
  c("`The **cat**
  in the hat`") -- "`Bold **edge label**`" --> d("The dog in the hog")
end
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "104_interaction",
        title = "Interaction",
        source = """
flowchart LR
    A-->B
    B-->C
    C-->D
    click A callback "Tooltip for a callback"
    click B "https://www.github.com" "This is a tooltip for a link"
    click C call callback() "Tooltip for a callback"
    click D href "https://www.github.com" "This is a tooltip for a link"
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "105_interaction",
        title = "Interaction",
        source = """
flowchart LR
    A-->B
    B-->C
    C-->D
    D-->E
    click A "https://www.github.com" _blank
    click B "https://www.github.com" "Open this in a new tab" _blank
    click C href "https://www.github.com" _blank
    click D href "https://www.github.com" "Open this in a new tab" _blank
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "106_comments",
        title = "Comments",
        source = """
flowchart LR
%% this is a comment A -- text --> B{node}
   A -- text --> B -- text2 --> C
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "107_edge_level_curve_style_using_edge_ids_v11_10_0",
        title = "Edge level curve style using Edge IDs (v11.10.0+)",
        source = """
flowchart LR
    A e1@==> B
    A e2@--> C
    e1@{ curve: linear }
    e2@{ curve: natural }
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "108_styling_a_node",
        title = "Styling a node",
        source = """
flowchart LR
    id1(Start)-->id2(Stop)
    style id1 fill:#f9f,stroke:#333,stroke-width:4px
    style id2 fill:#bbf,stroke:#f66,stroke-width:2px,color:#fff,stroke-dasharray: 5 5
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "109_classes",
        title = "Classes",
        source = """
flowchart LR
    A:::someclass --> B
    classDef someclass fill:#f96
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "110_classes",
        title = "Classes",
        source = """
flowchart LR
    A:::foo & B:::bar --> C:::foobar
    classDef foo stroke:#f00
    classDef bar stroke:#0f0
    classDef foobar stroke:#00f
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "111_css_classes",
        title = "CSS classes",
        source = """
flowchart LR
    A:::myStyle --> B
    classDef myStyle fill:#ff0000,stroke:#ffff00,stroke-width:4px
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "112_basic_support_for_fontawesome",
        title = "Basic support for fontawesome",
        source = """
flowchart TD
    B["fa:fa-twitter for peace"]
    B-->C[fa:fa-ban forbidden]
    B-->D(fa:fa-spinner)
    B-->E(A fa:fa-camera-retro perhaps?)
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "113_custom_icons",
        title = "Custom icons",
        source = """
flowchart TD
    B["fa:fa-twitter for peace"]
    B-->C["fab:fa-truck-bold a custom icon"]
        """.trimIndent(),
    ),
    MermaidFlowchartDocCase(
        id = "114_graph_declarations_with_spaces_between_vertices_and_link_and_without_semicolon",
        title = "Graph declarations with spaces between vertices and link and without semicolon",
        source = """
flowchart LR
    A[Hard edge] -->|Link text| B(Round edge)
    B --> C{Decision}
    C -->|One| D[Result one]
    C -->|Two| E[Result two]
        """.trimIndent(),
    ),
)
