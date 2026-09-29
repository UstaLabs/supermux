package com.swithun.cmpmermaid.core.architecture.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArchitectureParserTest {
    @Test
    fun parsesMetadataServicesGroupsJunctionsEdgesAndAlignments() {
        val db = parse(
            """
                architecture-beta title Simple Architecture
                  accTitle: Accessible architecture
                  accDescr {
                    A service routes through a junction.
                  }
                  group api(cloud)["API Group"]
                  group data(database)[Data Group]
                  service app(server)[Application] in api
                  service db "SQL"[Database] in data
                  junction route in api
                  app{group}:R <--> L:db{group}
                  app:B -[routes]- T:route
                  align row app db route
            """.trimIndent(),
        )

        assertEquals("Simple Architecture", db.diagramTitle)
        assertEquals("Accessible architecture", db.accessibilityTitle)
        assertEquals(
            "A service routes through a junction.",
            db.accessibilityDescription,
        )
        assertEquals(2, db.getGroups().size)
        assertEquals(2, db.getServices().size)
        assertEquals("SQL", db.getServices()[1].iconText)
        assertEquals(1, db.getJunctions().size)
        assertEquals(2, db.getEdges().size)
        assertTrue(db.getEdges().first().lhsInto)
        assertTrue(db.getEdges().first().rhsInto)
        assertTrue(db.getEdges().first().lhsGroup)
        assertTrue(db.getEdges().first().rhsGroup)
        assertEquals("routes", db.getEdges()[1].title)
        assertEquals(
            ArchitectureAlignmentDirection.Row,
            db.getLayoutHints().single().direction,
        )
    }

    @Test
    fun populatesAllGroupsBeforeServicesLikeUpstreamAstAdapter() {
        val db = parse(
            """
                architecture-beta
                  service app(server)[Application] in runtime
                  group runtime(cloud)[Runtime]
            """.trimIndent(),
        )

        assertEquals("runtime", db.getServices().single().parent)
    }

    @Test
    fun preservesInsertionOrderForNumericServiceIds() {
        val db = parse(
            """
                architecture-beta
                  service a(server)[A]
                  service 20(server)[B]
                  service 10(server)[C]
            """.trimIndent(),
        )

        assertEquals(listOf("a", "20", "10"), db.getServices().map { it.id })
    }

    @Test
    fun buildsSpatialMapsAndCrossGroupAlignments() {
        val db = parse(
            """
                architecture-beta
                  group left(cloud)[Left]
                  group right(cloud)[Right]
                  service a(server)[A] in left
                  service b(server)[B] in right
                  a:R -- L:b
            """.trimIndent(),
        )
        val structures = assertIs<GMResult.Ok<ArchitectureDataStructures>>(
            db.getDataStructures(),
        ).value

        assertEquals(1, structures.spatialMaps.size)
        assertEquals(ArchitecturePosition(0, 0), structures.spatialMaps[0]["a"])
        assertEquals(ArchitecturePosition(1, 0), structures.spatialMaps[0]["b"])
        assertEquals(
            ArchitectureAlignment.Horizontal,
            structures.groupAlignments["\"left\"-\"right\""],
        )
    }

    @Test
    fun createsOneSpatialMapPerDisconnectedComponent() {
        val db = parse(
            """
                architecture-beta
                  service a(server)[A]
                  service b(server)[B]
                  service c(server)[C]
                  a:R -- L:b
            """.trimIndent(),
        )
        val structures = assertIs<GMResult.Ok<ArchitectureDataStructures>>(
            db.getDataStructures(),
        ).value

        assertEquals(2, structures.spatialMaps.size)
        assertEquals(setOf("a", "b"), structures.spatialMaps[0].keys)
        assertEquals(setOf("c"), structures.spatialMaps[1].keys)
    }

    @Test
    fun rejectsMissingParentWithUpstreamMessage() {
        val error = parseError(
            """
                architecture-beta
                  service app(server)[Application] in missing
            """.trimIndent(),
        )

        assertTrue(error.message.contains("parent does not exist"))
    }

    @Test
    fun rejectsDuplicateIdsAcrossElementKinds() {
        val error = parseError(
            """
                architecture-beta
                  group duplicate(cloud)[Group]
                  service duplicate(server)[Service]
            """.trimIndent(),
        )

        assertTrue(error.message.contains("already in use by another group"))
    }

    @Test
    fun rejectsInvalidAndDuplicateAlignmentMembers() {
        val missing = parseError(
            """
                architecture-beta
                  service a(server)[A]
                  service b(server)[B]
                  align row a b ghost
            """.trimIndent(),
        )
        val duplicate = parseError(
            """
                architecture-beta
                  service a(server)[A]
                  align column a a
            """.trimIndent(),
        )

        assertTrue(missing.message.contains("ghost"))
        assertTrue(duplicate.message.contains("more than once"))
    }

    @Test
    fun rejectsExactAlignmentKeywordsAsIdsButAllowsPrefixes() {
        assertTrue(
            parseError(
                """
                    architecture-beta
                      service row(database)[Reserved]
                """.trimIndent(),
            ).message.contains("reserved"),
        )
        val db = parse(
            """
                architecture-beta
                  service rowspan(server)[Rowspan]
                  service columnar(server)[Columnar]
                  align row rowspan columnar
            """.trimIndent(),
        )
        assertEquals(listOf("rowspan", "columnar"), db.getServices().map { it.id })
    }

    @Test
    fun rejectsGroupBoundaryModifierInsideOneGroup() {
        val error = parseError(
            """
                architecture-beta
                  group api(cloud)[API]
                  service a(server)[A] in api
                  service b(server)[B] in api
                  a{group}:R -- L:b
            """.trimIndent(),
        )

        assertTrue(error.message.contains("does not pass through two groups"))
    }

    private fun parse(source: String): ArchitectureDb {
        val result = parser().parse(source)
        return assertIs<GMResult.Ok<ArchitectureDb>>(result, result.toString()).value
    }

    private fun parseError(source: String): MermaidError {
        val result = parser().parse(source)
        return assertIs<GMResult.Err<MermaidError>>(result, result.toString()).error
    }

    private fun parser(): ArchitectureParser = ArchitectureParser(
        options = MermaidRenderOptions(),
        frontmatterTitle = null,
        lineOffset = 0,
    )
}
