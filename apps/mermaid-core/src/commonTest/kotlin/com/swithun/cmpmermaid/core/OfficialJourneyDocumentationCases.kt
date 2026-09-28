package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/userJourney.md.
 * Upstream document SHA-256: 85776352a3900fc96997926758edb25484c2137fdb30a5f1d0394ebdfdccaa7e
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:journey-doc-fixtures
 */
internal data class MermaidJourneyDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialJourneyDocumentationCases: List<MermaidJourneyDocCase> = listOf(
    MermaidJourneyDocCase(
        id = "001_user_journey_diagram",
        title = "User Journey Diagram",
        source = """
journey
    title My working day
    section Go to work
      Make tea: 5: Me
      Go upstairs: 3: Me
      Do work: 1: Me, Cat
    section Go home
      Go downstairs: 5: Me
      Sit down: 5: Me
        """.trimIndent(),
    ),
)
