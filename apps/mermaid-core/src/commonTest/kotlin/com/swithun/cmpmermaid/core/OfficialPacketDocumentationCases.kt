package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/packet.md.
 * Upstream document SHA-256: a3e5127a8f21aa9e1575ddccbdda3a01a8ee4fd987550c3b58be5ac7c7342152
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:packet-doc-fixtures
 */
internal data class MermaidPacketDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialPacketDocumentationCases: List<MermaidPacketDocCase> = listOf(
    MermaidPacketDocCase(
        id = "001_examples",
        title = "Examples",
        source = """
---
title: "TCP Packet"
---
packet
0-15: "Source Port"
16-31: "Destination Port"
32-63: "Sequence Number"
64-95: "Acknowledgment Number"
96-99: "Data Offset"
100-105: "Reserved"
106: "URG"
107: "ACK"
108: "PSH"
109: "RST"
110: "SYN"
111: "FIN"
112-127: "Window"
128-143: "Checksum"
144-159: "Urgent Pointer"
160-191: "(Options and Padding)"
192-255: "Data (variable length)"
        """.trimIndent(),
    ),
    MermaidPacketDocCase(
        id = "002_examples",
        title = "Examples",
        source = """
packet
title UDP Packet
+16: "Source Port"
+16: "Destination Port"
32-47: "Length"
48-63: "Checksum"
64-95: "Data (variable length)"
        """.trimIndent(),
    ),
)
