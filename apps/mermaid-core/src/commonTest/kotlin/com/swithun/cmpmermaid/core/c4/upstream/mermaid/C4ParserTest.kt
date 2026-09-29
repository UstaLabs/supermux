package com.swithun.cmpmermaid.core.c4.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class C4ParserTest {
    @Test
    fun parsesAllDiagramHeaders() {
        C4DiagramType.entries.forEach { type ->
            val db = parse(
                """
                    ${type.sourceName}
                    Person(user, "User")
                """.trimIndent(),
            )

            assertEquals(type, db.diagramType)
        }
    }

    @Test
    fun parsesEveryElementMacroAndNestedBoundaries() {
        val db = parse(
            """
                C4Deployment
                Enterprise_Boundary(enterprise, "Enterprise") {
                  System_Boundary(systemBoundary, "System") {
                    Person(person, "Person", "Description")
                    Person_Ext(externalPerson, "External")
                    System(system, "System")
                    SystemDb(systemDb, "System DB")
                    SystemQueue(systemQueue, "System Queue")
                    System_Ext(externalSystem, "External System")
                    SystemDb_Ext(externalSystemDb, "External System DB")
                    SystemQueue_Ext(externalSystemQueue, "External System Queue")
                    Container(container, "Container", "Kotlin")
                    ContainerDb(containerDb, "Container DB", "SQL")
                    ContainerQueue(containerQueue, "Container Queue", "MQ")
                    Container_Ext(externalContainer, "External Container", "HTTP")
                    ContainerDb_Ext(externalContainerDb, "External Container DB", "SQL")
                    ContainerQueue_Ext(externalContainerQueue, "External Queue", "MQ")
                    Component(component, "Component", "KMP")
                    ComponentDb(componentDb, "Component DB", "SQL")
                    ComponentQueue(componentQueue, "Component Queue", "MQ")
                    Component_Ext(externalComponent, "External Component", "HTTP")
                    ComponentDb_Ext(externalComponentDb, "External Component DB", "SQL")
                    ComponentQueue_Ext(externalComponentQueue, "External Component Queue", "MQ")
                  }
                  Container_Boundary(containers, "Containers") {
                    Container(nested, "Nested", "Kotlin")
                  }
                  Node(node, "Node", "Linux") {
                    Container(deployed, "Deployed", "JVM")
                  }
                }
            """.trimIndent(),
        )

        assertEquals(22, db.getC4ShapeArray().size)
        assertEquals(5, db.getBoundaries().size)
        assertEquals("systemBoundary", db.getC4Shape("person")?.parentBoundary)
        assertEquals("node", db.getC4Shape("deployed")?.parentBoundary)
        assertTrue(db.isAtGlobalBoundary())
    }

    @Test
    fun tracksBoundaryDepthWhenAnAliasMatchesTheUpstreamGlobalAlias() {
        val db = parse(
            """
                C4Deployment
                Deployment_Node(global, "Global Cloud") {
                  Container(api, "API", "Kotlin/JVM")
                }
            """.trimIndent(),
        )
        val unclosed = parseError(
            """
                C4Deployment
                Deployment_Node(global, "Global Cloud") {
                  Container(api, "API", "Kotlin/JVM")
            """.trimIndent(),
        )

        assertTrue(db.isAtGlobalBoundary())
        assertEquals("global", db.getC4Shape("api")?.parentBoundary)
        assertTrue(unclosed.message.contains("Unclosed C4 boundary"))
    }

    @Test
    fun preservesNamedAttributesAndStyleUpdates() {
        val db = parse(
            """
                C4Container
                Container(app, "Application", ${'$'}descr="Description", ${'$'}sprite="browser", ${'$'}tags="v1")
                ContainerDb(data, "Data", "SQL")
                Rel(app, data, "Reads", ${'$'}descr="Query", ${'$'}tags="sync")
                UpdateElementStyle(app, ${'$'}fontColor="red", ${'$'}bgColor="#eeeeee", ${'$'}shape="component")
                UpdateRelStyle(app, data, ${'$'}textColor="blue", ${'$'}lineColor="green", ${'$'}offsetX="-20", ${'$'}offsetY="30")
                UpdateLayoutConfig(${'$'}c4ShapeInRow="2", ${'$'}c4BoundaryInRow="1")
            """.trimIndent(),
        )
        val app = db.getC4Shape("app")
        val relation = db.getRelations().single()

        assertEquals("Description", app?.descr?.text)
        assertEquals("browser", app?.sprite)
        assertEquals("v1", app?.tags)
        assertEquals("red", app?.fontColor)
        assertEquals("#eeeeee", app?.bgColor)
        assertEquals("component", app?.shape)
        assertEquals("Query", relation.descr.text)
        assertEquals("sync", relation.tags)
        assertEquals("blue", relation.textColor)
        assertEquals("green", relation.lineColor)
        assertEquals(-20, relation.offsetX)
        assertEquals(30, relation.offsetY)
        assertEquals(2, db.c4ShapeInRow)
        assertEquals(1, db.c4BoundaryInRow)
    }

    @Test
    fun preservesNamedAttributesRegardlessOfTheirPositionalSlot() {
        val shapeCases = listOf(
            ShapeNamedAttributeCase(
                header = "C4Context",
                declaration = "System(x, \"L\", %s)",
                fields = listOf("descr", "tags", "sprite", "link"),
            ),
            ShapeNamedAttributeCase(
                header = "C4Context",
                declaration = "Person(x, \"L\", %s)",
                fields = listOf("descr", "tags", "sprite", "link"),
            ),
            ShapeNamedAttributeCase(
                header = "C4Container",
                declaration = "Container(x, \"L\", %s)",
                fields = listOf("descr", "techn", "tags", "sprite", "link"),
            ),
            ShapeNamedAttributeCase(
                header = "C4Component",
                declaration = "Component(x, \"L\", %s)",
                fields = listOf("descr", "techn", "tags", "sprite", "link"),
            ),
        )
        shapeCases.forEach { case ->
            case.fields.forEach { field ->
                val db = parse(
                    "${case.header}\n${case.declaration.replace("%s", "${'$'}$field=\"V\"")}",
                )

                assertField(db.getC4ShapeArray().single(), field)
            }
        }

        val boundaryCases = listOf(
            BoundaryNamedAttributeCase(
                header = "C4Context",
                declaration = "System_Boundary(x, \"L\", %s)",
                fields = listOf("type", "tags", "link"),
            ),
            BoundaryNamedAttributeCase(
                header = "C4Container",
                declaration = "Container_Boundary(x, \"L\", %s)",
                fields = listOf("type", "tags", "link"),
            ),
            BoundaryNamedAttributeCase(
                header = "C4Deployment",
                declaration = "Node(x, \"L\", %s)",
                fields = listOf("type", "descr", "tags", "sprite", "link"),
            ),
        )
        boundaryCases.forEach { case ->
            case.fields.forEach { field ->
                val db = parse(
                    """
                        ${case.header}
                        ${case.declaration.replace("%s", "${'$'}$field=\"V\"")} {
                          Container(i, "I")
                        }
                    """.trimIndent(),
                )

                assertField(
                    db.getBoundaries().single { boundary -> boundary.alias == "x" },
                    field,
                )
            }
        }

        listOf("techn", "descr", "tags", "sprite", "link").forEach { field ->
            val db = parse(
                """
                    C4Context
                    Person(a, "A")
                    Person(b, "B")
                    Rel(a, b, "uses", ${'$'}$field="V")
                """.trimIndent(),
            )

            assertField(db.getRelations().single(), field)
        }
    }

    @Test
    fun parsesMetadataCommentsAndMultilineAccessibilityDescription() {
        val db = parse(
            """
                C4Context
                %% comment
                title System context
                accTitle: Accessible title
                accDescr {
                  First line
                  Second line
                }
                Person(user, "User")
            """.trimIndent(),
        )

        assertEquals("Accessible title", db.diagramTitle)
        assertEquals(null, db.accessibilityTitle)
        assertEquals("First line\n  Second line", db.accessibilityDescription)
    }

    @Test
    fun rejectsMalformedInputAndUnclosedBoundaries() {
        val unknown = parseError("C4Context\nUnknown(a, \"A\")")
        val unclosed = parseError(
            """
                C4Context
                Boundary(b, "Boundary") {
                  System(s, "System")
            """.trimIndent(),
        )

        assertTrue(unknown.message.contains("Unknown C4 declaration"))
        assertTrue(unclosed.message.contains("Unclosed C4 boundary"))
    }

    private fun parse(source: String): C4Db {
        val result = parser().parse(source)
        return assertIs<GMResult.Ok<C4Db>>(result, result.toString()).value
    }

    private fun parseError(source: String): MermaidError {
        val result = parser().parse(source)
        return assertIs<GMResult.Err<MermaidError>>(result, result.toString()).error
    }

    private fun parser(): C4Parser = C4Parser(
        options = MermaidRenderOptions(),
        frontmatterTitle = null,
        lineOffset = 0,
    )

    private fun assertField(
        shape: C4Shape,
        field: String,
    ) {
        val value = when (field) {
            "descr" -> shape.descr.text
            "techn" -> shape.techn.text
            "tags" -> shape.tags
            "sprite" -> shape.sprite
            "link" -> shape.link
            else -> shape.attributes[field]
        }
        assertEquals("V", value, field)
    }

    private fun assertField(
        boundary: C4Boundary,
        field: String,
    ) {
        val value = when (field) {
            "type" -> boundary.type.text
            "descr" -> boundary.descr.text
            "tags" -> boundary.tags
            "sprite" -> boundary.sprite
            "link" -> boundary.link
            else -> boundary.attributes[field]
        }
        assertEquals("V", value, field)
    }

    private fun assertField(
        relation: C4Relation,
        field: String,
    ) {
        val value = when (field) {
            "techn" -> relation.techn.text
            "descr" -> relation.descr.text
            "tags" -> relation.tags
            "sprite" -> relation.sprite
            "link" -> relation.link
            else -> relation.attributes[field]
        }
        assertEquals("V", value, field)
    }

    private data class ShapeNamedAttributeCase(
        val header: String,
        val declaration: String,
        val fields: List<String>,
    )

    private data class BoundaryNamedAttributeCase(
        val header: String,
        val declaration: String,
        val fields: List<String>,
    )
}
