package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/swimlanes.md.
 * Upstream document SHA-256: 3530c6ecba9686953eb37a33af766e17465abc2fb37b725af44ea25cb2a0c4a7
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:swimlane-doc-fixtures
 */
internal data class MermaidSwimlaneDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialSwimlaneDocumentationCases: List<MermaidSwimlaneDocCase> = listOf(
    MermaidSwimlaneDocCase(
        id = "001_with_the_defaults",
        title = "With the defaults",
        source = """
swimlane-beta LR
  subgraph Customer
    Browse[Browse catalogue]
    Pay[Pay]
  end
  subgraph Warehouse
    Pick[Pick items]
    Ship[Ship order]
  end
  subgraph Finance
    Invoice[Raise invoice]
  end
  Browse --> Pay
  Pay --> Pick
  Pick --> Ship
  Pay --> Invoice
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "002_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
---
swimlane-beta LR
  subgraph Customer
    Browse[Browse catalogue]
    Pay[Pay]
  end
  subgraph Warehouse
    Pick[Pick items]
    Ship[Ship order]
  end
  subgraph Finance
    Invoice[Raise invoice]
  end
  Browse --> Pay
  Pay --> Pick
  Pick --> Ship
  Pay --> Invoice
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "003_basic_example",
        title = "Basic Example",
        source = """
swimlane-beta LR
  subgraph Customer
    request[Request service]
    receive[Receive update]
  end

  subgraph Support
    triage[Triage request]
    answer[Send answer]
  end

  subgraph Engineering
    investigate[Investigate issue]
    fix[Prepare fix]
  end

  request --> triage
  triage -->|Known issue| answer
  triage -->|Needs code change| investigate
  investigate --> fix --> answer
  answer --> receive
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "004_lanes",
        title = "Lanes",
        source = """
swimlane-beta
  subgraph Sales
    lead[Qualify lead]
    quote[Prepare quote]
  end
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "005_lanes",
        title = "Lanes",
        source = """
swimlane-beta LR
  subgraph sales [Sales team]
    lead[Qualify lead]
    quote[Prepare quote]
  end

  subgraph finance [Finance team]
    review[Review terms]
    approve[Approve quote]
  end

  lead --> quote --> review --> approve
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "006_nodes",
        title = "Nodes",
        source = """
swimlane-beta LR
  subgraph Intake
    start([Start])
    task[Do work]
    fix[Fix issues]
  end

  subgraph Review
    decision{Ready?}
  end

  subgraph Complete
    done((Done))
  end

  start --> task --> decision
  decision -->|Yes| done
  decision -->|No| fix
  fix --> task
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "007_edges",
        title = "Edges",
        source = """
swimlane-beta LR
  subgraph Buyer
    choose[Choose product]
    pay[Pay invoice]
  end

  subgraph Store
    reserve[Reserve stock]
    ship[Ship product]
  end

  choose --> reserve
  reserve -->|Invoice ready| pay
  pay --> ship
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "008_accessibility",
        title = "Accessibility",
        source = """
swimlane-beta LR
  accTitle: Support escalation
  accDescr: A request starts with the customer, is triaged by support, and may be escalated to engineering.

  subgraph Customer
    request[Open request]
  end

  subgraph Support
    triage[Triage]
  end

  subgraph Engineering
    resolve[Resolve]
  end

  request --> triage --> resolve
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "009_make_each_lane_mean_one_kind_of_ownership",
        title = "Make Each Lane Mean One Kind of Ownership",
        source = """
swimlane-beta LR
  subgraph Customer
    submit[Submit order]
    confirm[Confirm delivery]
  end

  subgraph Store
    check[Check order]
    pack[Pack items]
  end

  subgraph Carrier
    collect[Collect package]
    deliver[Deliver package]
  end

  submit --> check --> pack --> collect --> deliver --> confirm
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "010_label_cross_lane_handoffs",
        title = "Label Cross-Lane Handoffs",
        source = """
swimlane-beta LR
  subgraph Applicant
    apply[Submit application]
    sign[Sign agreement]
  end

  subgraph Reviewer
    screen[Screen application]
    decide{Approved?}
  end

  subgraph System
    create[Create account]
    notify[Send welcome email]
  end

  apply -->|Application received| screen
  screen --> decide
  decide -->|Approved| create --> notify --> sign
  decide -->|Needs changes| apply
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "011_keep_long_processes_readable",
        title = "Keep Long Processes Readable",
        source = """
swimlane-beta TB
  subgraph Intake
    collect[Collect request]
    validate[Validate details]
  end

  subgraph Review
    review[Review request]
    decide{Ready?}
  end

  subgraph Delivery
    schedule[Schedule work]
    complete[Complete work]
  end

  collect --> validate --> review --> decide
  decide -->|Yes| schedule --> complete
  decide -->|No| collect
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "012_use_stable_ids",
        title = "Use Stable Ids",
        source = """
swimlane-beta LR
  subgraph ops [Operations]
    intake[Receive request]
    plan[Plan work]
  end

  subgraph legal [Legal]
    review[Review contract]
  end

  intake --> plan --> review

  classDef attention fill:#fff2cc,stroke:#d6a500,color:#111;
  class review attention;
        """.trimIndent(),
    ),
    MermaidSwimlaneDocCase(
        id = "013_put_decisions_where_they_are_made",
        title = "Put Decisions Where They Are Made",
        source = """
swimlane-beta LR
  subgraph Support
    classify{Can support solve it?}
    respond[Respond to customer]
  end

  subgraph Product
    prioritize[Prioritize fix]
  end

  subgraph Engineering
    implement[Implement fix]
  end

  classify -->|Yes| respond
  classify -->|No| prioritize --> implement --> respond
        """.trimIndent(),
    ),
)
