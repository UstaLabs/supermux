package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/kanban.md.
 * Upstream document SHA-256: df8ee76a26c410128f145786a6a73ebefd0a5ff31b3ee845665c833674f64b13
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:kanban-doc-fixtures
 */
internal data class MermaidKanbanDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialKanbanDocumentationCases: List<MermaidKanbanDocCase> = listOf(
    MermaidKanbanDocCase(
        id = "001_overview",
        title = "Overview",
        source = """
kanban
  column1[Column Title]
    task1[Task Description]
        """.trimIndent(),
    ),
    MermaidKanbanDocCase(
        id = "002_supported_metadata_keys",
        title = "Supported Metadata Keys",
        source = """
kanban
todo[Todo]
  id3[Update Database Function]@{ ticket: MC-2037, assigned: 'knsv', priority: 'High' }
        """.trimIndent(),
    ),
    MermaidKanbanDocCase(
        id = "003_full_example",
        title = "Full Example",
        source = """
---
config:
  kanban:
    ticketBaseUrl: 'https://mermaidchart.atlassian.net/browse/#TICKET#'
---
kanban
  Todo
    [Create Documentation]
    docs[Create Blog about the new diagram]
  [In progress]
    id6[Create renderer so that it works in all cases. We also add some extra text here for testing purposes. And some more just for the extra flare.]
  id9[Ready for deploy]
    id8[Design grammar]@{ assigned: 'knsv' }
  id10[Ready for test]
    id4[Create parsing tests]@{ ticket: MC-2038, assigned: 'K.Sveidqvist', priority: 'High' }
    id66[last item]@{ priority: 'Very Low', assigned: 'knsv' }
  id11[Done]
    id5[define getData]
    id2[Title of diagram is more than 100 chars when user duplicates diagram with 100 char]@{ ticket: MC-2036, priority: 'Very High'}
    id3[Update DB function]@{ ticket: MC-2037, assigned: knsv, priority: 'High' }

  id12[Can't reproduce]
    id3[Weird flickering in Firefox]
        """.trimIndent(),
    ),
)
