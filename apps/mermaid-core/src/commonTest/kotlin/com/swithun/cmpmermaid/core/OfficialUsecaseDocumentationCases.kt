package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/usecase.md.
 * Upstream document SHA-256: c6a7768a8af7b0b8dbaee1f9454ef654d93283e34e1cc339b689efe854fb052b
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:usecase-doc-fixtures
 */
internal data class MermaidUsecaseDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialUsecaseDocumentationCases: List<MermaidUsecaseDocCase> = listOf(
    MermaidUsecaseDocCase(
        id = "001_use_case_diagrams_12_0_0",
        title = "Use case diagrams (12.0.0+)",
        source = """
usecase-beta
direction LR
actor Customer("Customer")
systemBoundary "Order system"
  Checkout("Place order")
end
Customer --> Checkout
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "002_with_the_defaults",
        title = "With the defaults",
        source = """
usecase-beta
direction LR
actor Customer
actor Support
systemBoundary Storefront
  Browse("Browse catalogue")
  Checkout("Checkout")
end
systemBoundary Fulfilment
  Track("Track delivery")
end
Customer --> Browse
Customer --> Checkout
Customer --> Track
Support --> Track
Checkout ..> : include Browse
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "003_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
  layout: dagre
---
usecase-beta
direction LR
actor Customer
actor Support
systemBoundary Storefront
  Browse("Browse catalogue")
  Checkout("Checkout")
end
systemBoundary Fulfilment
  Track("Track delivery")
end
Customer --> Browse
Customer --> Checkout
Customer --> Track
Support --> Track
Checkout ..> : include Browse
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "004_actors_and_use_cases",
        title = "Actors and use cases",
        source = """
usecase-beta
Customer --> Login
actor Customer
actor Admin("Main administrator")
Login("Sign in")
Report[Generate report]
"Reset password"
Admin --> Report
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "005_actor_variants",
        title = "Actor variants",
        source = """
usecase-beta
actor Normal("Normal actor")
actor Hollow("Hollow actor")@{ type: hollow }
actor Awesome("Awesome actor")@{ type: awesome }
actor Icon("Registered icon")@{ icon: "fa:user" }
actor Fallback("Missing icon fallback")@{ icon: "not-registered:user" }
Normal --> Manage
Hollow --> Manage
Awesome --> Manage
Icon --> Manage
Fallback --> Manage
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "006_business_elements_and_stereotypes",
        title = "Business elements and stereotypes",
        source = """
usecase-beta
actor SalesAgent("Sales agent")@{ business: true } <<Employee>>
actor Broker@{ type: hollow, business: true }
Quote("Prepare quote")@{ business: true } <<Core>>
Archive[Archive quote] <<Record>>
SalesAgent --> Quote
Broker --> Quote
Quote --> Archive
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "007_labels",
        title = "Labels",
        source = """
usecase-beta
actor Analyst(*Analyst*)
Literal(**Literal markers**)
Priced[Costs 19.95 EUR — VAT included!]
Analyst -- files R&D report --> Literal
Analyst --> Priced
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "008_labels",
        title = "Labels",
        source = """
usecase-beta
actor Reviewer("`*Reviewer*`")
Literal("**Literal markers**")
Formatted("`**Formatted label**
with a physical line break`")
Quoted("Show #quot;quoted#quot; text")
Reviewer -- "`opens **form**`" --> Formatted
Reviewer --> Literal
Reviewer --> Quoted
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "009_lines_and_comments",
        title = "Lines and comments",
        source = """
usecase-beta
%% Actors are declared explicitly.
actor User

%% Each relationship is a separate statement.
User --> Login
Login --> Dashboard
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "010_system_boundaries",
        title = "System boundaries",
        source = """
usecase-beta
systemBoundary sb1["Payment service"]@{ type: package }:::system
  actor Clerk("Payment clerk")
  Authorize("Authorize payment")
  Receipt[Create receipt]
end
Clerk --> Authorize
Authorize --> Receipt
classDef system stroke:#4b4b7a
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "011_system_boundaries",
        title = "System boundaries",
        source = """
usecase-beta
systemBoundary "Payment service"
  actor Clerk("Payment clerk")
  Authorize("Authorize payment")
end
Payment_service@{ type: package }
Clerk --> Authorize
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "012_associations",
        title = "Associations",
        source = """
usecase-beta
actor User
actor Support
Start
Finish
User --> Start
Start <-- Support
Start -- Finish
User --o Start
Start o-- Support
User --x Finish
Finish x-- Support
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "013_associations",
        title = "Associations",
        source = """
usecase-beta
actor User
Login
User -- "include account details" --> Login
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "014_include_extend_and_generalization",
        title = "Include, extend, and generalization",
        source = """
usecase-beta
actor Admin
actor Person
Checkout
Payment
ApplyCoupon
Admin --|> Person
Checkout ..> : include Payment
ApplyCoupon ..> : extend Checkout
ApplyCoupon --|> Checkout
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "015_edge_ids_styles_animation_and_length",
        title = "Edge IDs, styles, animation, and length",
        source = """
usecase-beta
actor Customer
Checkout
Payment
Customer opens@-- "starts checkout" ---> Checkout
Checkout payment@..> : include Payment
classDef emphasized stroke:#d33,stroke-width:3px
class opens,payment emphasized
style opens stroke:#06c,stroke-width:4px
opens@{ animation: fast }
payment@{ animate: false }
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "016_notes",
        title = "Notes",
        source = """
usecase-beta
note for Login "`Requires an **active session**`"
note for User "Starts the workflow"
actor User
Login("Sign in")
User --> Login
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "017_json_tables",
        title = "JSON tables",
        source = """
usecase-beta
Inspect("Inspect payload")
json Payload@{
  "2": "second in source",
  "1": "first after 2",
  "enabled": true,
  "count": 3,
  "missing": null,
  "colors": ["Red", "Green"],
  "address": { "city": "Oslo" },
  "items": [{ "name": "Book" }],
  "emptyObject": {},
  "emptyArray": []
}:::data
Inspect --> Payload
classDef data stroke:#3572a5
style Payload stroke-width:2px
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "018_styling",
        title = "Styling",
        source = """
usecase-beta
actor Customer:::external
Checkout("Checkout"):::critical
systemBoundary Account
  Profile[Edit profile]
end
json Session@{ "active": true }:::data
Customer --> Checkout
Checkout --> Profile
Profile --> Session
classDef default stroke:#7f8ea3
classDef external,critical stroke-width:3px
class Customer,Checkout external,critical
class Account,Session data
style Checkout stroke:#c33,stroke-width:4px
style Account stroke:#536878
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "019_colors",
        title = "Colors",
        source = """
---
config:
  theme: redux-color
---
usecase-beta
direction LR
actor Customer
systemBoundary Catalogue
  Browse("Browse catalogue")
end
systemBoundary Payment
  Checkout("Checkout")
end
Customer --> Browse
Browse --> Checkout
Checkout ..> : include Browse
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "020_per_element_colour_rotation",
        title = "Per-element colour rotation",
        source = """
---
config:
  theme: redux-color
  usecase:
    colorScheme: rotate
---
usecase-beta
direction LR
actor Customer
actor Auditor
Browse("Browse catalogue")
Checkout("Checkout")
Customer --> Browse
Browse --> Checkout
Auditor --> Checkout
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "021_configuration",
        title = "Configuration",
        source = """
---
config:
  usecase:
    actorFontSize: 16
    actorFontFamily: Arial
    actorFontWeight: bold
    usecaseFontSize: 14
    usecaseFontFamily: Georgia
    usecaseFontWeight: normal
    nodeSpacing: 60
    rankSpacing: 70
    diagramPadding: 24
    colorScheme: role
    useMaxWidth: false
---
usecase-beta
direction LR
actor Customer
Browse("Browse catalog")
Customer --> Browse
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "022_accessibility",
        title = "Accessibility",
        source = """
usecase-beta
accTitle: Account access use cases
accDescr {
  A customer signs in and can reset a password.
  The diagram names the actor, use cases, and associations.
}
actor Customer
SignIn("Sign in")
Reset("Reset password")
Customer --> SignIn
Customer --> Reset
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "023_complete_example",
        title = "Complete example",
        source = """
usecase-beta
direction LR
accTitle: Online ordering use cases
accDescr {
  A customer places an order through the storefront.
  Staff review the order and inspect its data.
}
actor Customer("Customer")
actor Staff("Order staff")@{ type: hollow, business: true } <<Employee>>
systemBoundary ordering["Ordering System"]@{ type: package }:::system
  Browse("Browse products")
  Checkout("Checkout") <<Core>>:::critical
  Payment("Process payment")
  Review[Review order]
end
json OrderData@{
  "status": "pending",
  "items": [{ "name": "Book", "quantity": 1 }],
  "total": 29.95
}:::data
note for Checkout "`Validates the **cart** before payment`"
Customer starts@-- "places order" ---> Checkout
Customer --> Browse
Checkout pays@..> : include Payment
Staff --> Review
Review --> OrderData
classDef system stroke:#c8a02a,stroke-width:2px
classDef critical stroke:#c33,stroke-width:3px
classDef data stroke:#3572a5
starts@{ animation: fast }
style pays stroke:#6b46c1,stroke-width:2px
        """.trimIndent(),
    ),
    MermaidUsecaseDocCase(
        id = "024_constructs_that_parse_but_mean_something_else",
        title = "Constructs that parse but mean something else",
        source = """
usecase-beta
actor Customer
Reset("`Reset
password`")
Refund("Refund #40;partial#41;")
Customer --> Reset
Customer --> Refund
        """.trimIndent(),
    ),
)
