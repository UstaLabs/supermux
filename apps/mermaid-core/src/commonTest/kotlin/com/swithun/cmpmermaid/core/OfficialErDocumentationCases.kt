package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/entityRelationshipDiagram.md.
 * Upstream document SHA-256: 0be2f34ff578b2d86d0429ce288e8681ef46a27d645841a5162c78d501e5441b
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:er-doc-fixtures
 */
internal data class MermaidErDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialErDocumentationCases: List<MermaidErDocCase> = listOf(
    MermaidErDocCase(
        id = "001_entity_relationship_diagrams",
        title = "Entity Relationship Diagrams",
        source = """
---
title: Order example
---
erDiagram
    CUSTOMER ||--o{ ORDER : places
    ORDER ||--|{ LINE-ITEM : contains
    CUSTOMER }|..|{ DELIVERY-ADDRESS : uses
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "002_entity_relationship_diagrams",
        title = "Entity Relationship Diagrams",
        source = """
erDiagram
    CUSTOMER ||--o{ ORDER : places
    CUSTOMER {
        string name
        string custNumber
        string sector
    }
    ORDER ||--|{ LINE-ITEM : contains
    ORDER {
        int orderNumber
        string deliveryAddress
    }
    LINE-ITEM {
        string productCode
        int quantity
        float pricePerUnit
    }
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "003_with_the_defaults",
        title = "With the defaults",
        source = """
erDiagram
  CUSTOMER ||--o{ ORDER : places
  ORDER ||--|{ LINE_ITEM : contains
  PRODUCT ||--o{ LINE_ITEM : "appears in"
  CUSTOMER {
    string name
    string email
  }
  ORDER {
    int id
    date placedAt
  }
  LINE_ITEM {
    int quantity
    float price
  }
  PRODUCT {
    string sku
    string title
  }
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "004_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
  layout: dagre
---
erDiagram
  CUSTOMER ||--o{ ORDER : places
  ORDER ||--|{ LINE_ITEM : contains
  PRODUCT ||--o{ LINE_ITEM : "appears in"
  CUSTOMER {
    string name
    string email
  }
  ORDER {
    int id
    date placedAt
  }
  LINE_ITEM {
    int quantity
    float price
  }
  PRODUCT {
    string sku
    string title
  }
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "005_unicode_text",
        title = "Unicode text",
        source = """
erDiagram
    "This ❤ Unicode"
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "006_markdown_formatting",
        title = "Markdown formatting",
        source = """
erDiagram
    "This **is** _Markdown_"
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "007_identification",
        title = "Identification",
        source = """
erDiagram
    CAR ||--o{ NAMED-DRIVER : allows
    PERSON }o..o{ NAMED-DRIVER : is
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "008_identification",
        title = "Identification",
        source = """
erDiagram
    CAR 1 to zero or more NAMED-DRIVER : allows
    PERSON many(0) optionally to 0+ NAMED-DRIVER : is
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "009_attributes",
        title = "Attributes",
        source = """
erDiagram
    CAR ||--o{ NAMED-DRIVER : allows
    CAR {
        string registrationNumber
        string make
        string model
    }
    PERSON ||--o{ NAMED-DRIVER : is
    PERSON {
        string firstName
        string lastName
        int age
    }
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "010_optional_attribute_types_v11_16_0",
        title = "Optional attribute types (v11.16.0+)",
        source = """
erDiagram
    PERSON {
        string firstName
        string? middleName
        string lastName
    }
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "011_entity_name_aliases",
        title = "Entity Name Aliases",
        source = """
erDiagram
    p[Person] {
        string firstName
        string lastName
    }
    a["Customer Account"] {
        string email
    }
    p ||--o| a : has
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "012_attribute_keys_and_comments",
        title = "Attribute Keys and Comments",
        source = """
erDiagram
    CAR ||--o{ NAMED-DRIVER : allows
    CAR {
        string registrationNumber PK
        string make
        string model
        string[] parts
    }
    PERSON ||--o{ NAMED-DRIVER : is
    PERSON {
        string driversLicense PK "The license #"
        string(99) firstName "Only 99 characters are allowed"
        string lastName
        string phone UK
        int age
    }
    NAMED-DRIVER {
        string carRegistrationNumber PK, FK
        string driverLicence PK, FK
    }
    MANUFACTURER only one to zero or more CAR : makes
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "013_direction",
        title = "Direction",
        source = """
erDiagram
    direction TB
    CUSTOMER ||--o{ ORDER : places
    CUSTOMER {
        string name
        string custNumber
        string sector
    }
    ORDER ||--|{ LINE-ITEM : contains
    ORDER {
        int orderNumber
        string deliveryAddress
    }
    LINE-ITEM {
        string productCode
        int quantity
        float pricePerUnit
    }
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "014_direction",
        title = "Direction",
        source = """
erDiagram
    direction LR
    CUSTOMER ||--o{ ORDER : places
    CUSTOMER {
        string name
        string custNumber
        string sector
    }
    ORDER ||--|{ LINE-ITEM : contains
    ORDER {
        int orderNumber
        string deliveryAddress
    }
    LINE-ITEM {
        string productCode
        int quantity
        float pricePerUnit
    }
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "015_subgraphs_v11_17_0",
        title = "Subgraphs (v11.17.0+)",
        source = """
erDiagram
    subgraph title1
        CUSTOMER
        CUSTOMER {
            string name
            string custNumber
            string sector
        }
    end
    subgraph title2
        CAR ||--o{ NAMED-DRIVER : allows
        subgraph title3
            PERSON
            PERSON {
                string firstName
                string lastName
                int age
            }
        end
    end
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "016_subgraphs_v11_17_0",
        title = "Subgraphs (v11.17.0+)",
        source = """
erDiagram
    subgraph title1
        CUSTOMER
    end
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "017_subgraphs_v11_17_0",
        title = "Subgraphs (v11.17.0+)",
        source = """
erDiagram
    subgraph "Customer Domain"
        CUSTOMER
    end
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "018_subgraphs_v11_17_0",
        title = "Subgraphs (v11.17.0+)",
        source = """
erDiagram
    subgraph id1 [title 1]
        CUSTOMER
    end
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "019_direction_in_subgraphs",
        title = "Direction in subgraphs",
        source = """
erDiagram
    direction LR
    subgraph TOP
        direction TB
        subgraph B1
            direction RL
            I1 ||--|| F1 : links
        end
        subgraph B2
            direction BT
            I2 ||--|| F2 : links
        end
    end
    A ||--|| TOP : links
    TOP ||--|| B : links
    B1 ||--|| B2 : links
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "020_styling_a_node",
        title = "Styling a node",
        source = """
erDiagram
    id1||--||id2 : label
    style id1 fill:#f9f,stroke:#333,stroke-width:4px
    style id2 fill:#bbf,stroke:#f66,stroke-width:2px,color:#fff,stroke-dasharray: 5 5
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "021_classes",
        title = "Classes",
        source = """
erDiagram
    direction TB
    CAR:::someclass {
        string registrationNumber
        string make
        string model
    }
    PERSON:::someclass {
        string firstName
        string lastName
        int age
    }
    HOUSE:::someclass

    classDef someclass fill:#f96
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "022_classes",
        title = "Classes",
        source = """
erDiagram
    CAR {
        string registrationNumber
        string make
        string model
    }
    PERSON {
        string firstName
        string lastName
        int age
    }
    PERSON:::foo ||--|| CAR : owns
    PERSON o{--|| HOUSE:::bar : has

    classDef foo stroke:#f00
    classDef bar stroke:#0f0
    classDef foobar stroke:#00f
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "023_default_class",
        title = "Default class",
        source = """
erDiagram
    CAR {
        string registrationNumber
        string make
        string model
    }
    PERSON {
        string firstName
        string lastName
        int age
    }
    PERSON:::foo ||--|| CAR : owns
    PERSON o{--|| HOUSE:::bar : has

    classDef default fill:#f9f,stroke-width:4px
    classDef foo stroke:#f00
    classDef bar stroke:#0f0
    classDef foobar stroke:#00f
        """.trimIndent(),
    ),
    MermaidErDocCase(
        id = "024_layout",
        title = "Layout",
        source = """
---
title: Order example
config:
    layout: dagre
---
erDiagram
    CUSTOMER ||--o{ ORDER : places
    ORDER ||--|{ LINE-ITEM : contains
    CUSTOMER }|..|{ DELIVERY-ADDRESS : uses
        """.trimIndent(),
    ),
)
