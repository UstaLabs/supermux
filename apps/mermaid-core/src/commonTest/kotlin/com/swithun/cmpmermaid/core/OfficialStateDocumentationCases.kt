package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0 packages/mermaid/src/docs/syntax/stateDiagram.md.
 * Upstream document SHA-256: 161c0aa290e79ddd5ced601a36dff946083214d8664bc697b86e1aea0989c583
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:state-doc-fixtures
 */
internal data class MermaidStateDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialStateDocumentationCases: List<MermaidStateDocCase> = listOf(
    MermaidStateDocCase(
        id = "001_state_diagrams",
        title = "State diagrams",
        source = """
---
title: Simple sample
---
stateDiagram-v2
    [*] --> Still
    Still --> [*]

    Still --> Moving
    Moving --> Still
    Moving --> Crash
    Crash --> [*]
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "002_state_diagrams",
        title = "State diagrams",
        source = """
stateDiagram
    [*] --> Still
    Still --> [*]

    Still --> Moving
    Moving --> Still
    Moving --> Crash
    Crash --> [*]
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "003_with_the_defaults",
        title = "With the defaults",
        source = """
stateDiagram-v2
  [*] --> Draft
  Draft --> Submitted : submit
  state Review {
    [*] --> Screening
    Screening --> Decision
  }
  Submitted --> Review
  Review --> Published : approved
  Review --> Draft : rejected
  Published --> [*]
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "004_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
  layout: dagre
---
stateDiagram-v2
  [*] --> Draft
  Draft --> Submitted : submit
  state Review {
    [*] --> Screening
    Screening --> Decision
  }
  Submitted --> Review
  Review --> Published : approved
  Review --> Draft : rejected
  Published --> [*]
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "005_states",
        title = "States",
        source = """
stateDiagram-v2
    stateId
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "006_states",
        title = "States",
        source = """
stateDiagram-v2
    state "This is a state description" as s2
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "007_states",
        title = "States",
        source = """
stateDiagram-v2
    s2 : This is a state description
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "008_transitions",
        title = "Transitions",
        source = """
stateDiagram-v2
    s1 --> s2
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "009_transitions",
        title = "Transitions",
        source = """
stateDiagram-v2
    s1 --> s2: A transition
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "010_start_and_end",
        title = "Start and End",
        source = """
stateDiagram-v2
    [*] --> s1
    s1 --> [*]
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "011_composite_states",
        title = "Composite states",
        source = """
stateDiagram-v2
    [*] --> First
    state First {
        [*] --> second
        second --> [*]
    }

    [*] --> NamedComposite
    NamedComposite: Another Composite
    state NamedComposite {
        [*] --> namedSimple
        namedSimple --> [*]
        namedSimple: Another simple
    }
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "012_composite_states",
        title = "Composite states",
        source = """
stateDiagram-v2
    [*] --> First

    state First {
        [*] --> Second

        state Second {
            [*] --> second
            second --> Third

            state Third {
                [*] --> third
                third --> [*]
            }
        }
    }
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "013_composite_states",
        title = "Composite states",
        source = """
stateDiagram-v2
    [*] --> First
    First --> Second
    First --> Third

    state First {
        [*] --> fir
        fir --> [*]
    }
    state Second {
        [*] --> sec
        sec --> [*]
    }
    state Third {
        [*] --> thi
        thi --> [*]
    }
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "014_choice",
        title = "Choice",
        source = """
stateDiagram-v2
    state if_state <<choice>>
    [*] --> IsPositive
    IsPositive --> if_state
    if_state --> False: if n < 0
    if_state --> True : if n >= 0
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "015_forks",
        title = "Forks",
        source = """
stateDiagram-v2
    state fork_state <<fork>>
      [*] --> fork_state
      fork_state --> State2
      fork_state --> State3

      state join_state <<join>>
      State2 --> join_state
      State3 --> join_state
      join_state --> State4
      State4 --> [*]
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "016_notes",
        title = "Notes",
        source = """
stateDiagram-v2
        State1: The state with a note
        note right of State1
            Important information! You can write
            notes.
        end note
        State1 --> State2
        note left of State2 : This is the note to the left.
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "017_concurrency",
        title = "Concurrency",
        source = """
stateDiagram-v2
    [*] --> Active

    state Active {
        [*] --> NumLockOff
        NumLockOff --> NumLockOn : EvNumLockPressed
        NumLockOn --> NumLockOff : EvNumLockPressed
        --
        [*] --> CapsLockOff
        CapsLockOff --> CapsLockOn : EvCapsLockPressed
        CapsLockOn --> CapsLockOff : EvCapsLockPressed
        --
        [*] --> ScrollLockOff
        ScrollLockOff --> ScrollLockOn : EvScrollLockPressed
        ScrollLockOn --> ScrollLockOff : EvScrollLockPressed
    }
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "018_setting_the_direction_of_the_diagram",
        title = "Setting the direction of the diagram",
        source = """
stateDiagram
    direction LR
    [*] --> A
    A --> B
    B --> C
    state B {
      direction LR
      a --> b
    }
    B --> D
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "019_comments",
        title = "Comments",
        source = """
stateDiagram-v2
    [*] --> Still
    Still --> [*]
%% this is a comment
    Still --> Moving
    Moving --> Still %% another comment
    Moving --> Crash
    Crash --> [*]
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "020_1_class_statement",
        title = "1. `class` statement",
        source = """
stateDiagram
   direction TB

   accTitle: This is the accessible title
   accDescr: This is an accessible description

   classDef notMoving fill:white
   classDef movement font-style:italic
   classDef badBadEvent fill:#f00,color:white,font-weight:bold,stroke-width:2px,stroke:yellow

   [*]--> Still
   Still --> [*]
   Still --> Moving
   Moving --> Still
   Moving --> Crash
   Crash --> [*]

   class Still notMoving
   class Moving, Crash movement
   class Crash badBadEvent
   class end badBadEvent
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "021_2_operator_to_apply_a_style_to_a_state",
        title = "2. `:::` operator to apply a style to a state",
        source = """
stateDiagram
   direction TB

   accTitle: This is the accessible title
   accDescr: This is an accessible description

   classDef notMoving fill:white
   classDef movement font-style:italic;
   classDef badBadEvent fill:#f00,color:white,font-weight:bold,stroke-width:2px,stroke:yellow

   [*] --> Still:::notMoving
   Still --> [*]
   Still --> Moving:::movement
   Moving --> Still
   Moving --> Crash:::movement
   Crash:::badBadEvent --> [*]
        """.trimIndent(),
    ),
    MermaidStateDocCase(
        id = "022_spaces_in_state_names",
        title = "Spaces in state names",
        source = """
stateDiagram
    classDef yourState font-style:italic,font-weight:bold,fill:white

    yswsii: Your state with spaces in it
    [*] --> yswsii:::yourState
    [*] --> SomeOtherState
    SomeOtherState --> YetAnotherState
    yswsii --> YetAnotherState
    YetAnotherState --> [*]
        """.trimIndent(),
    ),
)
