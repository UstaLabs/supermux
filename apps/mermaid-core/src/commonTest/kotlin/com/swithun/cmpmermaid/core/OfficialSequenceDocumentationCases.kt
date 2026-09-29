package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0 packages/mermaid/src/docs/syntax/sequenceDiagram.md.
 * Upstream document SHA-256: dad8b72c3c8e7281d54e4e905cc64061785834140beada5c7b7ad33a2c5277e0
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:sequence-doc-fixtures
 */
internal data class MermaidSequenceDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialSequenceDocumentationCases: List<MermaidSequenceDocCase> = listOf(
    MermaidSequenceDocCase(
        id = "001_sequence_diagrams",
        title = "Sequence diagrams",
        source = """
sequenceDiagram
    Alice->>John: Hello John, how are you?
    John-->>Alice: Great!
    Alice-)John: See you later!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "002_with_the_defaults",
        title = "With the defaults",
        source = """
sequenceDiagram
  autonumber
  actor Customer
  participant Web as Web app
  participant API as API gateway
  participant Bank
  Customer->>Web: Place order
  Web->>API: POST /orders
  activate API
  API->>Bank: Authorise payment
  Bank-->>API: Approved
  API-->>Web: 201 Created
  deactivate API
  Web-->>Customer: Order confirmed
  Note over Customer,Bank: One order, one transaction
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "003_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
---
sequenceDiagram
  autonumber
  actor Customer
  participant Web as Web app
  participant API as API gateway
  participant Bank
  Customer->>Web: Place order
  Web->>API: POST /orders
  activate API
  API->>Bank: Authorise payment
  Bank-->>API: Approved
  API-->>Web: 201 Created
  deactivate API
  Web-->>Customer: Order confirmed
  Note over Customer,Bank: One order, one transaction
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "004_participants",
        title = "Participants",
        source = """
sequenceDiagram
    participant Alice
    participant Bob
    Bob->>Alice: Hi Alice
    Alice->>Bob: Hi Bob
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "005_actors",
        title = "Actors",
        source = """
sequenceDiagram
    actor Alice
    actor Bob
    Alice->>Bob: Hi Bob
    Bob->>Alice: Hi Alice
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "006_boundary",
        title = "Boundary",
        source = """
sequenceDiagram
    participant Alice@{ "type" : "boundary" }
    participant Bob
    Alice->>Bob: Request from boundary
    Bob->>Alice: Response to boundary
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "007_control",
        title = "Control",
        source = """
sequenceDiagram
    participant Alice@{ "type" : "control" }
    participant Bob
    Alice->>Bob: Control request
    Bob->>Alice: Control response
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "008_entity",
        title = "Entity",
        source = """
sequenceDiagram
    participant Alice@{ "type" : "entity" }
    participant Bob
    Alice->>Bob: Entity request
    Bob->>Alice: Entity response
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "009_database",
        title = "Database",
        source = """
sequenceDiagram
    participant Alice@{ "type" : "database" }
    participant Bob
    Alice->>Bob: DB query
    Bob->>Alice: DB result
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "010_collections",
        title = "Collections",
        source = """
sequenceDiagram
    participant Alice@{ "type" : "collections" }
    participant Bob
    Alice->>Bob: Collections request
    Bob->>Alice: Collections response
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "011_queue",
        title = "Queue",
        source = """
sequenceDiagram
    participant Alice@{ "type" : "queue" }
    participant Bob
    Alice->>Bob: Queue message
    Bob->>Alice: Queue response
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "012_external_alias_syntax",
        title = "External Alias Syntax",
        source = """
sequenceDiagram
    participant A as Alice
    participant J as John
    A->>J: Hello John, how are you?
    J->>A: Great!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "013_external_alias_syntax",
        title = "External Alias Syntax",
        source = """
sequenceDiagram
    participant API@{ "type": "boundary" } as Public API
    actor DB@{ "type": "database" } as User Database
    participant Svc@{ "type": "control" } as Auth Service
    API->>Svc: Authenticate
    Svc->>DB: Query user
    DB-->>Svc: User data
    Svc-->>API: Token
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "014_inline_alias_syntax",
        title = "Inline Alias Syntax",
        source = """
sequenceDiagram
    participant API@{ "type": "boundary", "alias": "Public API" }
    participant Auth@{ "type": "control", "alias": "Auth Service" }
    participant DB@{ "type": "database", "alias": "User Database" }
    API->>Auth: Login request
    Auth->>DB: Query user
    DB-->>Auth: User data
    Auth-->>API: Access token
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "015_alias_precedence",
        title = "Alias Precedence",
        source = """
sequenceDiagram
    participant API@{ "type": "boundary", "alias": "Internal Name" } as External Name
    participant DB@{ "type": "database", "alias": "Internal DB" } as External DB
    API->>DB: Query
    DB-->>API: Result
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "016_actor_creation_and_destruction_v10_3_0",
        title = "Actor Creation and Destruction (v10.3.0+)",
        source = """
sequenceDiagram
    Alice->>Bob: Hello Bob, how are you ?
    Bob->>Alice: Fine, thank you. And you?
    create participant Carl
    Alice->>Carl: Hi Carl!
    create actor D as Donald
    Carl->>D: Hi!
    destroy Carl
    Alice-xCarl: We are too many
    destroy Bob
    Bob->>Alice: I agree
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "017_grouping_box",
        title = "Grouping / Box",
        source = """
sequenceDiagram
    box Purple Alice & John
    participant A
    participant J
    end
    box Another Group
    participant B
    participant C
    end
    A->>J: Hello John, how are you?
    J->>A: Great!
    A->>B: Hello Bob, how is Charley?
    B->>C: Hello Charley, how are you?
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "018_basic_syntax",
        title = "Basic Syntax",
        source = """
sequenceDiagram
    participant Alice
    participant John
    Alice->>()John: Hello John
    Alice()->>John: How are you?
    John()->>()Alice: Great!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "019_activations",
        title = "Activations",
        source = """
sequenceDiagram
    Alice->>John: Hello John, how are you?
    activate John
    John-->>Alice: Great!
    deactivate John
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "020_activations",
        title = "Activations",
        source = """
sequenceDiagram
    Alice->>+John: Hello John, how are you?
    John-->>-Alice: Great!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "021_activations",
        title = "Activations",
        source = """
sequenceDiagram
    Alice->>+John: Hello John, how are you?
    Alice->>+John: John, can you hear me?
    John-->>-Alice: Hi Alice, I can hear you!
    John-->>-Alice: I feel great!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "022_notes",
        title = "Notes",
        source = """
sequenceDiagram
    participant John
    Note right of John: Text in note
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "023_notes",
        title = "Notes",
        source = """
sequenceDiagram
    Alice->John: Hello John, how are you?
    Note over Alice,John: A typical interaction
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "024_line_breaks",
        title = "Line breaks",
        source = """
sequenceDiagram
    Alice->John: Hello John,<br/>how are you?
    Note over Alice,John: A typical interaction<br/>But now in two lines
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "025_line_breaks",
        title = "Line breaks",
        source = """
sequenceDiagram
    participant Alice as Alice<br/>Johnson
    Alice->John: Hello John,<br/>how are you?
    Note over Alice,John: A typical interaction<br/>But now in two lines
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "026_loops",
        title = "Loops",
        source = """
sequenceDiagram
    Alice->John: Hello John, how are you?
    loop Every minute
        John-->Alice: Great!
    end
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "027_alt",
        title = "Alt",
        source = """
sequenceDiagram
    Alice->>Bob: Hello Bob, how are you?
    alt is sick
        Bob->>Alice: Not so good :(
    else is well
        Bob->>Alice: Feeling fresh like a daisy
    end
    opt Extra response
        Bob->>Alice: Thanks for asking
    end
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "028_parallel",
        title = "Parallel",
        source = """
sequenceDiagram
    par Alice to Bob
        Alice->>Bob: Hello guys!
    and Alice to John
        Alice->>John: Hello guys!
    end
    Bob-->>Alice: Hi Alice!
    John-->>Alice: Hi Alice!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "029_parallel",
        title = "Parallel",
        source = """
sequenceDiagram
    par Alice to Bob
        Alice->>Bob: Go help John
    and Alice to John
        Alice->>John: I want this done today
        par John to Charlie
            John->>Charlie: Can we do this today?
        and John to Diana
            John->>Diana: Can you help us today?
        end
    end
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "030_critical_region",
        title = "Critical Region",
        source = """
sequenceDiagram
    critical Establish a connection to the DB
        Service-->DB: connect
    option Network timeout
        Service-->Service: Log error
    option Credentials rejected
        Service-->Service: Log different error
    end
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "031_critical_region",
        title = "Critical Region",
        source = """
sequenceDiagram
    critical Establish a connection to the DB
        Service-->DB: connect
    end
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "032_break",
        title = "Break",
        source = """
sequenceDiagram
    Consumer-->API: Book something
    API-->BookingService: Start booking process
    break when the booking process fails
        API-->Consumer: show failure
    end
    API-->BillingService: Start billing process
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "033_background_highlighting",
        title = "Background Highlighting",
        source = """
sequenceDiagram
    participant Alice
    participant John

    rect rgb(191, 223, 255)
    note right of Alice: Alice calls John.
    Alice->>+John: Hello John, how are you?
    rect rgb(200, 150, 255)
    Alice->>+John: John, can you hear me?
    John-->>-Alice: Hi Alice, I can hear you!
    end
    John-->>-Alice: I feel great!
    end
    Alice ->>+ John: Did you want to go to the game tonight?
    John -->>- Alice: Yeah! See you there.
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "034_comments",
        title = "Comments",
        source = """
sequenceDiagram
    Alice->>John: Hello John, how are you?
    %% this is a comment
    John-->>Alice: Great!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "035_entity_codes_to_escape_characters",
        title = "Entity codes to escape characters",
        source = """
sequenceDiagram
    A->>B: I #9829; you!
    B->>A: I #9829; you #infin; times more!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "036_sequencenumbers",
        title = "sequenceNumbers",
        source = """
sequenceDiagram
    autonumber
    Alice->>John: Hello John, how are you?
    loop HealthCheck
        John->>John: Fight against hypochondria
    end
    Note right of John: Rational thoughts!
    John-->>Alice: Great!
    John->>Bob: How about you?
    Bob-->>John: Jolly good!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "037_actor_menus",
        title = "Actor Menus",
        source = """
sequenceDiagram
    participant Alice
    participant John
    link Alice: Dashboard @ https://dashboard.contoso.com/alice
    link Alice: Wiki @ https://wiki.contoso.com/alice
    link John: Dashboard @ https://dashboard.contoso.com/john
    link John: Wiki @ https://wiki.contoso.com/john
    Alice->>John: Hello John, how are you?
    John-->>Alice: Great!
    Alice-)John: See you later!
        """.trimIndent(),
    ),
    MermaidSequenceDocCase(
        id = "038_advanced_menu_syntax",
        title = "Advanced Menu Syntax",
        source = """
sequenceDiagram
    participant Alice
    participant John
    links Alice: {"Dashboard": "https://dashboard.contoso.com/alice", "Wiki": "https://wiki.contoso.com/alice"}
    links John: {"Dashboard": "https://dashboard.contoso.com/john", "Wiki": "https://wiki.contoso.com/john"}
    Alice->>John: Hello John, how are you?
    John-->>Alice: Great!
    Alice-)John: See you later!
        """.trimIndent(),
    ),
)
