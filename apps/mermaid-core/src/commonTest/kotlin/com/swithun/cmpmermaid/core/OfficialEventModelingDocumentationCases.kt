package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/eventmodeling.md.
 * Upstream document SHA-256: 554b655daac92e8162849f428c038e26cb482d7d859297028ba986cfd0159ee5
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:eventmodeling-doc-fixtures
 */
internal data class MermaidEventModelingDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialEventModelingDocumentationCases:
    List<MermaidEventModelingDocCase> = listOf(
    MermaidEventModelingDocCase(
        id = "001_timeline",
        title = "Timeline",
        source = """
eventmodeling

tf 01 ui CartUI
tf 02 cmd AddItem
tf 03 evt ItemAdded
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "002_inline_data",
        title = "Inline data",
        source = """
eventmodeling

tf 01 ui CartUI
tf 02 cmd AddItem { description: string }
tf 03 evt ItemAdded { description: string }
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "003_inline_data",
        title = "Inline data",
        source = """
eventmodeling

timeframe 01 ui CartUI
timeframe 02 command AddItem { description: string }
timeframe 03 event ItemAdded { description: string }
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "004_data_block",
        title = "Data block",
        source = """
eventmodeling

tf 01 ui CartUI
tf 02 cmd AddItem [[AddItem01]]
tf 03 evt ItemAdded [[ItemAdded]]
tf 04 cmd AddItem [[AddItem02]]
tf 05 evt ItemAdded [[ItemAdded]]

data AddItem01 {
  description: 'john'
  image: 'avatar_john'
  price: 20.4
}

data AddItem02 {
  description: 'jack'
  image: 'avatar_jack'
  price: 12.5
}

data ItemAdded {
  description: string
  image: string
  price: number
}
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "005_data_block",
        title = "Data block",
        source = """
eventmodeling

timeframe 01 ui CartUI
timeframe 02 command AddItem [[AddItem01]]
timeframe 03 event ItemAdded [[ItemAdded]]
timeframe 04 command AddItem [[AddItem02]]
timeframe 05 event ItemAdded [[ItemAdded]]

data AddItem01 {
  description: 'john'
  image: 'avatar_john'
  price: 20.4
}

data AddItem02 {
  description: 'jack'
  image: 'avatar_jack'
  price: 12.5
}

data ItemAdded {
  description: string
  image: string
  price: number
}
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "006_resetting_the_flow",
        title = "Resetting the flow",
        source = """
eventmodeling

tf 01 ui CartUI
tf 02 cmd AddItem
tf 03 evt ItemAdded

rf 04 evt External.InventoryChanged
tf 05 pcr InventoryProcessor
tf 06 cmd ChangeInventory
tf 07 evt Cart.InventoryChanged
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "007_resetting_the_flow",
        title = "Resetting the flow",
        source = """
eventmodeling

timeframe 01 ui CartUI
timeframe 02 command AddItem
timeframe 03 event ItemAdded

resetframe 04 event External.InventoryChanged
timeframe 05 processor InventoryProcessor
timeframe 06 command ChangeInventory
timeframe 07 event Cart.InventoryChanged
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "008_multiple_relations",
        title = "Multiple relations",
        source = """
eventmodeling

rf 02 evt CartCreated
rf 03 evt ItemAdded
rf 04 evt ItemRemoved
rf 05 evt CartCleared
tf 01 rmo CartUI ->> 02 ->> 03 ->> 04 ->> 05
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "009_state_change",
        title = "State Change",
        source = """
eventmodeling

tf 01 ui CartUI
tf 02 cmd AddItem
tf 03 evt ItemAdded
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "010_state_view",
        title = "State View",
        source = """
eventmodeling

tf 03 evt ItemAdded
tf 02 rmo CartItems
tf 04 ui CartUI
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "011_translation",
        title = "Translation",
        source = """
eventmodeling

tf 03 evt External.InventoryChanged
tf 02 pcr InventoryProcessor
tf 04 cmd ChangeInventory
tf 05 evt Cart.InventoryChanged
        """.trimIndent(),
    ),
    MermaidEventModelingDocCase(
        id = "012_swimlanes_and_namespaces",
        title = "Swimlanes and Namespaces",
        source = """
eventmodeling

rf 01 evt Inventory.InventoryChanged
rf 02 evt External.InventoryChanged
        """.trimIndent(),
    ),
)
