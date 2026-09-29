package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0 packages/mermaid/src/docs/syntax/classDiagram.md.
 * Upstream document SHA-256: bb594432ec833b06a4f81395dc26dc17be99a9e07ae8620346e023a0a6b2ad3b
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:class-doc-fixtures
 */
internal data class MermaidClassDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialClassDocumentationCases: List<MermaidClassDocCase> = listOf(
    MermaidClassDocCase(
        id = "001_class_diagrams",
        title = "Class diagrams",
        source = """
---
title: Animal example
---
classDiagram
    note "From Duck till Zebra"
    Animal <|-- Duck
    note for Duck "can fly<br>can swim<br>can dive<br>can help in debugging"
    Animal <|-- Fish
    Animal <|-- Zebra
    Animal : +int age
    Animal : +String gender
    Animal: +isMammal()
    Animal: +mate()
    class Duck{
        +String beakColor
        +swim()
        +quack()
    }
    class Fish{
        -int sizeInFeet
        -canEat()
    }
    class Zebra{
        +bool is_wild
        +run()
    }
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "002_with_the_defaults",
        title = "With the defaults",
        source = """
classDiagram
  class Customer {
    +String name
    +String email
  }
  class Order {
    +String id
    +Date placedAt
    +total() Money
  }
  class LineItem {
    +int quantity
  }
  class Payment {
    <<interface>>
    +authorise() bool
  }
  Customer "1" --> "*" Order : places
  Order "1" *-- "*" LineItem : contains
  Order --> Payment : settled by
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "003_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
  layout: dagre
---
classDiagram
  class Customer {
    +String name
    +String email
  }
  class Order {
    +String id
    +Date placedAt
    +total() Money
  }
  class LineItem {
    +int quantity
  }
  class Payment {
    <<interface>>
    +authorise() bool
  }
  Customer "1" --> "*" Order : places
  Order "1" *-- "*" LineItem : contains
  Order --> Payment : settled by
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "004_class",
        title = "Class",
        source = """
---
title: Bank example
---
classDiagram
    class BankAccount
    BankAccount : +String owner
    BankAccount : +Bigdecimal balance
    BankAccount : +deposit(amount)
    BankAccount : +withdrawal(amount)
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "005_define_a_class",
        title = "Define a class",
        source = """
classDiagram
    class Animal
    Vehicle <|-- Car
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "006_class_labels",
        title = "Class labels",
        source = """
classDiagram
    class Animal["Animal with a label"]
    class Car["Car with *! symbols"]
    Animal --> Car
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "007_class_labels",
        title = "Class labels",
        source = """
classDiagram
    class `Animal Class!`
    class `Car Class`
    `Animal Class!` --> `Car Class`
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "008_defining_members_of_a_class",
        title = "Defining Members of a class",
        source = """
classDiagram
class BankAccount
BankAccount : +String owner
BankAccount : +BigDecimal balance
BankAccount : +deposit(amount)
BankAccount : +withdrawal(amount)
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "009_defining_members_of_a_class",
        title = "Defining Members of a class",
        source = """
classDiagram
class BankAccount{
    +String owner
    +BigDecimal balance
    +deposit(amount)
    +withdrawal(amount)
}
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "010_return_type",
        title = "Return Type",
        source = """
classDiagram
class BankAccount{
    +String owner
    +BigDecimal balance
    +deposit(amount) bool
    +withdrawal(amount) int
}
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "011_generic_types",
        title = "Generic Types",
        source = """
classDiagram
class Square~Shape~{
    int id
    List~int~ position
    setPoints(List~int~ points)
    getPoints() List~int~
}

Square : -List~string~ messages
Square : +setMessages(List~string~ messages)
Square : +getMessages() List~string~
Square : +getDistanceMatrix() List~List~int~~
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "012_defining_relationship",
        title = "Defining Relationship",
        source = """
classDiagram
classA <|-- classB
classC *-- classD
classE o-- classF
classG <-- classH
classI -- classJ
classK <.. classL
classM <|.. classN
classO .. classP
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "013_defining_relationship",
        title = "Defining Relationship",
        source = """
classDiagram
classA --|> classB : Inheritance
classC --* classD : Composition
classE --o classF : Aggregation
classG --> classH : Association
classI -- classJ : Link(Solid)
classK ..> classL : Dependency
classM ..|> classN : Realization
classO .. classP : Link(Dashed)
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "014_labels_on_relations",
        title = "Labels on Relations",
        source = """
classDiagram
classA <|-- classB : implements
classC *-- classD : composition
classE o-- classF : aggregation
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "015_two_way_relations",
        title = "Two-way relations",
        source = """
classDiagram
    Animal <|--|> Zebra
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "016_lollipop_interfaces",
        title = "Lollipop Interfaces",
        source = """
classDiagram
  bar ()-- foo
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "017_lollipop_interfaces",
        title = "Lollipop Interfaces",
        source = """
classDiagram
  class Class01 {
    int amount
    draw()
  }
  Class01 --() bar
  Class02 --() bar

  foo ()-- Class01
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "018_define_namespace",
        title = "Define Namespace",
        source = """
classDiagram
namespace BaseShapes {
    class Triangle
    class Rectangle {
      double width
      double height
    }
}
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "019_namespace_labels_v11_15_0",
        title = "Namespace Labels (v11.15.0+)",
        source = """
classDiagram
    namespace Auth["Authentication Service"] {
        class UserService {
            +login()
            +logout()
        }
    }
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "020_nested_namespaces_v11_15_0",
        title = "Nested Namespaces (v11.15.0+)",
        source = """
classDiagram
    namespace Company.Engineering.Backend {
        class Developer {
            +writeCode()
        }
    }
    namespace Company.Engineering.Frontend {
        class Designer {
            +createMockup()
        }
    }
    namespace Company.Engineering {
        class TechLead {
            +planSprint()
        }
    }
    TechLead --> Developer : leads
    TechLead --> Designer : leads
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "021_nested_namespaces_v11_15_0",
        title = "Nested Namespaces (v11.15.0+)",
        source = """
classDiagram
    namespace Platform {
        namespace Auth {
            class UserService {
                +login()
                +logout()
            }
        }
        namespace Data {
            class Repository {
                +find()
                +save()
            }
        }
        class Gateway {
            +route()
        }
    }
    Gateway --> UserService : delegates
    Gateway --> Repository : delegates
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "022_compact_rendering_hierarchicalnamespaces_false",
        title = "Compact rendering (`hierarchicalNamespaces: false`)",
        source = """
---
config:
  class:
    hierarchicalNamespaces: false
---
classDiagram
    namespace Company.Engineering.Backend {
        class Developer {
            +writeCode()
        }
    }
    namespace Company.Engineering.Frontend {
        class Designer {
            +createMockup()
        }
    }
    namespace Company {
        class CEO {
            +makeDecisions()
        }
    }
    CEO --> Developer : oversees
    CEO --> Designer : oversees
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "023_cardinality_multiplicity_on_relations",
        title = "Cardinality / Multiplicity on relations",
        source = """
classDiagram
    Customer "1" --> "*" Ticket
    Student "1" --> "1..*" Course
    Galaxy --> "many" Star : Contains
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "024_annotations_on_classes",
        title = "Annotations on classes",
        source = """
classDiagram
  class Shape <<interface>>
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "025_annotations_on_classes",
        title = "Annotations on classes",
        source = """
classDiagram
class Shape
<<interface>> Shape
Shape : noOfVertices
Shape : draw()
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "026_annotations_on_classes",
        title = "Annotations on classes",
        source = """
classDiagram
class Shape{
    <<interface>>
    noOfVertices
    draw()
}
class Color{
    <<enumeration>>
    RED
    BLUE
    GREEN
    WHITE
    BLACK
}
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "027_comments",
        title = "Comments",
        source = """
classDiagram
%% This whole line is a comment classDiagram class Shape <<interface>>
class Shape{
    <<interface>>
    noOfVertices
    draw()
}
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "028_setting_the_direction_of_the_diagram",
        title = "Setting the direction of the diagram",
        source = """
classDiagram
  direction RL
  class Student {
    -idCard : IdCard
  }
  class IdCard{
    -id : int
    -name : string
  }
  class Bike{
    -id : int
    -name : string
  }
  Student "1" --o "1" IdCard : carries
  Student "1" --o "1" Bike : rides
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "029_examples",
        title = "Examples",
        source = """
classDiagram
    note "This is a general note"
    note for MyClass "This is a note for a class"
    class MyClass{
    }
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "030_examples",
        title = "Examples",
        source = """
classDiagram
class Shape
link Shape "https://www.github.com" "This is a tooltip for a link"
class Shape2
click Shape2 href "https://www.github.com" "This is a tooltip for a link"
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "031_examples",
        title = "Examples",
        source = """
classDiagram
class Shape
callback Shape "callbackFunction" "This is a tooltip for a callback"
class Shape2
click Shape2 call callbackFunction() "This is a tooltip for a callback"
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "032_examples",
        title = "Examples",
        source = """
classDiagram
    class Class01
    class Class02
    callback Class01 "callbackFunction" "Callback tooltip"
    link Class02 "https://www.github.com" "This is a link"
    class Class03
    class Class04
    click Class03 call callbackFunction() "Callback tooltip"
    click Class04 href "https://www.github.com" "This is a link"
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "033_styling_a_node",
        title = "Styling a node",
        source = """
classDiagram
  class Animal
  class Mineral
  style Animal fill:#f9f,stroke:#333,stroke-width:4px
  style Mineral fill:#bbf,stroke:#f66,stroke-width:2px,color:#fff,stroke-dasharray: 5 5
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "034_classes",
        title = "Classes",
        source = """
classDiagram
    class Animal:::someclass
    classDef someclass fill:#f96
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "035_classes",
        title = "Classes",
        source = """
classDiagram
    class Animal:::someclass {
        -int sizeInFeet
        -canEat()
    }
    classDef someclass fill:#f96
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "036_default_class",
        title = "Default class",
        source = """
classDiagram
  class Animal:::pink
  class Mineral

  classDef default fill:#f96,color:red
  classDef pink color:#f9f
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "037_css_classes",
        title = "CSS Classes",
        source = """
classDiagram
    class Animal:::styleClass
        """.trimIndent(),
    ),
    MermaidClassDocCase(
        id = "038_possible_configuration_parameters",
        title = "Possible configuration parameters:",
        source = """
---
  config:
    class:
      hideEmptyMembersBox: true
---
classDiagram
  class Duck
        """.trimIndent(),
    ),
)
