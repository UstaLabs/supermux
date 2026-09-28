package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/ishikawa.md.
 * Upstream document SHA-256: 7279caf0c50428cfbcd92366e6fd7e88d724a8902be545787376c559bff4c35d
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:ishikawa-doc-fixtures
 */
internal data class MermaidIshikawaDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialIshikawaDocumentationCases: List<MermaidIshikawaDocCase> = listOf(
    MermaidIshikawaDocCase(
        id = "001_syntax",
        title = "Syntax",
        source = """
ishikawa-beta
    Blurry Photo
    Process
        Out of focus
        Shutter speed too slow
        Protective film not removed
        Beautification filter applied
    User
        Shaky hands
    Equipment
        LENS
            Inappropriate lens
            Damaged lens
            Dirty lens
        SENSOR
            Damaged sensor
            Dirty sensor
    Environment
        Subject moved too quickly
        Too dark
        """.trimIndent(),
    ),
)
