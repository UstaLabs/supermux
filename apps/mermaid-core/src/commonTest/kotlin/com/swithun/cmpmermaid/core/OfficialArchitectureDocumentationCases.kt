package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/architecture.md.
 * Upstream document SHA-256: c3d8b2ff1df5854814a17f523f39effa8b8dbf2894189f47f34efd79bbfb616d
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:architecture-doc-fixtures
 */
internal data class MermaidArchitectureDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialArchitectureDocumentationCases: List<MermaidArchitectureDocCase> = listOf(
    MermaidArchitectureDocCase(
        id = "001_example",
        title = "Example",
        source = """
architecture-beta
    group api(cloud)[API]

    service db(database)[Database] in api
    service disk1(disk)[Storage] in api
    service disk2(disk)[Storage] in api
    service server(server)[Server] in api

    db:L -- R:server
    disk1:T -- B:server
    disk2:T -- B:db
        """.trimIndent(),
    ),
    MermaidArchitectureDocCase(
        id = "002_aligning_siblings_v11_16_0",
        title = "Aligning siblings (v11.16.0+)",
        source = """
architecture-beta
    group api(cloud)[API]
    service db1(database)[DB1] in api
    service db2(database)[DB2] in api
    service db3(database)[DB3] in api
    service mcp(server)[MCP] in api
    db1:R --> L:mcp
    db2:R --> L:mcp
    db3:R --> L:mcp
    align column db1 db2 db3
        """.trimIndent(),
    ),
    MermaidArchitectureDocCase(
        id = "003_aligning_siblings_v11_16_0",
        title = "Aligning siblings (v11.16.0+)",
        source = """
architecture-beta
    service src1(server)[Source 1]
    service src2(server)[Source 2]
    service src3(server)[Source 3]
    service proc(server)[Processor]
    src1:B --> T:proc
    src2:B --> T:proc
    src3:B --> T:proc
    align row src1 src2 src3
        """.trimIndent(),
    ),
    MermaidArchitectureDocCase(
        id = "004_grid_layouts_combining_row_and_column",
        title = "Grid layouts (combining `row` and `column`)",
        source = """
architecture-beta
    group sources(cloud)[Sources]
        service src_a(server)[Source A] in sources
        service src_b(server)[Source B] in sources
        service src_c(server)[Source C] in sources

    group storage(database)[Storage]
        service db_one(database)[DB One] in storage
        service db_two(database)[DB Two] in storage
        service db_three(database)[DB Three] in storage

    group output(disk)[Output]
        service brief(disk)[Brief] in output
        service analyst(server)[Analyst] in output
        service delivery(cloud)[Delivery] in output

    src_a:B --> T:db_one
    src_b:B --> T:db_two
    src_c:B --> T:db_three
    db_two:B --> T:brief
    brief:R --> L:analyst
    analyst:R --> L:delivery

    align row src_a src_b src_c
    align row db_one db_two db_three
    align row brief analyst delivery

    align column src_a db_one
    align column src_b db_two brief
    align column src_c db_three
        """.trimIndent(),
    ),
    MermaidArchitectureDocCase(
        id = "005_junctions",
        title = "Junctions",
        source = """
architecture-beta
    service left_disk(disk)[Disk]
    service top_disk(disk)[Disk]
    service bottom_disk(disk)[Disk]
    service top_gateway(internet)[Gateway]
    service bottom_gateway(internet)[Gateway]
    junction junctionCenter
    junction junctionRight

    left_disk:R -- L:junctionCenter
    top_disk:B -- T:junctionCenter
    bottom_disk:T -- B:junctionCenter
    junctionCenter:R -- L:junctionRight
    top_gateway:B -- T:junctionRight
    bottom_gateway:T -- B:junctionRight
        """.trimIndent(),
    ),
    MermaidArchitectureDocCase(
        id = "006_icons",
        title = "Icons",
        source = """
architecture-beta
    group api(logos:aws-lambda)[API]

    service db(logos:aws-aurora)[Database] in api
    service disk1(logos:aws-glacier)[Storage] in api
    service disk2(logos:aws-s3)[Storage] in api
    service server(logos:aws-ec2)[Server] in api

    db:L -- R:server
    disk1:T -- B:server
    disk2:T -- B:db
        """.trimIndent(),
    ),
)
