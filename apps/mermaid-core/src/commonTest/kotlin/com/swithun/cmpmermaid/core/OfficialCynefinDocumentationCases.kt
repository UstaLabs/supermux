package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/cynefin.md.
 * Upstream document SHA-256: 5b0434fe85f0cef69d84e5c96d75ef13741fa77ca81f96e201748a084bc3b3c0
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:cynefin-doc-fixtures
 */
internal data class MermaidCynefinDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialCynefinDocumentationCases: List<MermaidCynefinDocCase> = listOf(
    MermaidCynefinDocCase(
        id = "001_basic_example",
        title = "Basic example",
        source = """
cynefin-beta
  title Incident Response

  complex
    "Investigate root cause"
    "Run chaos experiment"

  complicated
    "Analyze performance data"
    "Expert review needed"

  clear
    "Restart service"
    "Apply known fix"

  chaotic
    "Page on-call immediately"

  confusion
    "Unknown failure mode"
        """.trimIndent(),
    ),
    MermaidCynefinDocCase(
        id = "002_with_transitions",
        title = "With transitions",
        source = """
cynefin-beta
  title Strategy Categorization

  complex
    "Market research"

  complicated
    "Competitive analysis"

  clear
    "Standard pricing"

  chaotic
    "Crisis management"

  complex --> complicated : "Pattern identified"
  complicated --> clear : "Best practice codified"
  clear --> chaotic : "Complacency"
  chaotic --> complex : "Stabilized"
        """.trimIndent(),
    ),
    MermaidCynefinDocCase(
        id = "003_empty_framework",
        title = "Empty framework",
        source = """
cynefin-beta
  title Cynefin Framework

  complex
  complicated
  clear
  chaotic
        """.trimIndent(),
    ),
)
