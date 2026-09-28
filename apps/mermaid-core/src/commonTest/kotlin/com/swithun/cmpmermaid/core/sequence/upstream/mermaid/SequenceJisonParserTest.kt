package com.swithun.cmpmermaid.core.sequence.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SequenceJisonParserTest {
    @Test
    fun parsesParticipantsAndMessageThroughUpstreamTables() {
        val db = parse(
            """
            sequenceDiagram
                participant A as Alice
                actor B as Bob
                A->>B:Hello
            """.trimIndent(),
        )

        assertEquals(listOf("A", "B"), db.getActors().keys.toList())
        assertEquals("Alice", db.getActors().getValue("A").description)
        assertEquals("participant", db.getActors().getValue("A").type)
        assertEquals("Bob", db.getActors().getValue("B").description)
        assertEquals("actor", db.getActors().getValue("B").type)
        assertEquals(1, db.getMessages().size)
        assertEquals("A", db.getMessages().single().from)
        assertEquals("B", db.getMessages().single().to)
        assertEquals("Hello", db.getMessages().single().message)
        assertEquals(SequenceLineType.SOLID, db.getMessages().single().type)
    }

    @Test
    fun keepsAllOfficialArrowTypesDistinct() {
        val arrows = listOf(
            "->" to SequenceLineType.SOLID_OPEN,
            "-->" to SequenceLineType.DOTTED_OPEN,
            "->>" to SequenceLineType.SOLID,
            "-->>" to SequenceLineType.DOTTED,
            "<<->>" to SequenceLineType.BIDIRECTIONAL_SOLID,
            "<<-->>" to SequenceLineType.BIDIRECTIONAL_DOTTED,
            "-x" to SequenceLineType.SOLID_CROSS,
            "--x" to SequenceLineType.DOTTED_CROSS,
            "-)" to SequenceLineType.SOLID_POINT,
            "--)" to SequenceLineType.DOTTED_POINT,
            "-|\\" to SequenceLineType.SOLID_TOP,
            "-|/" to SequenceLineType.SOLID_BOTTOM,
            "-\\\\" to SequenceLineType.STICK_TOP,
            "-//" to SequenceLineType.STICK_BOTTOM,
            "--|\\" to SequenceLineType.SOLID_TOP_DOTTED,
            "--|/" to SequenceLineType.SOLID_BOTTOM_DOTTED,
            "--\\\\" to SequenceLineType.STICK_TOP_DOTTED,
            "--//" to SequenceLineType.STICK_BOTTOM_DOTTED,
            "/|-" to SequenceLineType.SOLID_ARROW_TOP_REVERSE,
            "\\|-" to SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE,
            "//-" to SequenceLineType.STICK_ARROW_TOP_REVERSE,
            "\\\\-" to SequenceLineType.STICK_ARROW_BOTTOM_REVERSE,
            "/|--" to SequenceLineType.SOLID_ARROW_TOP_REVERSE_DOTTED,
            "\\|--" to SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE_DOTTED,
            "//--" to SequenceLineType.STICK_ARROW_TOP_REVERSE_DOTTED,
            "\\\\--" to SequenceLineType.STICK_ARROW_BOTTOM_REVERSE_DOTTED,
        )
        val source = buildString {
            appendLine("sequenceDiagram")
            arrows.forEachIndexed { index, (arrow, _) ->
                appendLine("A${arrow}B:m$index")
            }
        }

        val db = parse(source)

        assertEquals(arrows.map(Pair<String, Int>::second), db.getMessages().map { it.type })
    }

    @Test
    fun parsesActivationShortcutMinusAsModifierInsteadOfActorPrefix() {
        val db = parse(
            """
            sequenceDiagram
                Client->>+Service:Open
                Service->>+Database:Query
                Database-->>-Service:Rows
                Service-->>-Client:Result
            """.trimIndent(),
        )

        assertEquals(listOf("Client", "Service", "Database"), db.getActors().keys.toList())
        assertEquals(
            listOf(
                SequenceLineType.SOLID,
                SequenceLineType.ACTIVE_START,
                SequenceLineType.SOLID,
                SequenceLineType.ACTIVE_START,
                SequenceLineType.DOTTED,
                SequenceLineType.ACTIVE_END,
                SequenceLineType.DOTTED,
                SequenceLineType.ACTIVE_END,
            ),
            db.getMessages().map { it.type },
        )
    }

    @Test
    fun parsesNotesActivationsAndControlSections() {
        val db = parse(
            """
            sequenceDiagram
                participant A
                participant B
                activate A
                loop Retry
                    A->>B:Request
                    alt Accepted
                        B-->>A:Done
                    else Rejected
                        B--xA:Failed
                    end
                end
                deactivate A
                Note over A,B:Shared note
            """.trimIndent(),
        )

        assertTrue(db.getMessages().any { it.type == SequenceLineType.ACTIVE_START })
        assertTrue(db.getMessages().any { it.type == SequenceLineType.LOOP_START })
        assertTrue(db.getMessages().any { it.type == SequenceLineType.ALT_ELSE })
        assertTrue(db.getMessages().any { it.type == SequenceLineType.DOTTED_CROSS })
        assertTrue(db.getMessages().any { it.type == SequenceLineType.ACTIVE_END })
        val note = db.getMessages().single { it.type == SequenceLineType.NOTE }
        assertEquals("A", note.from)
        assertEquals("B", note.to)
        assertEquals("Shared note", note.message)
    }

    @Test
    fun parsesBoxesMetadataAutonumberAndCreateDestroy() {
        val db = parse(
            """
            sequenceDiagram
                autonumber 3 2
                box rgb(240,240,255) Services
                    participant API@{ type: "control", alias: "Gateway" }
                end
                create participant Worker
                API->>Worker:Start
                destroy Worker
                Worker-->>API:Done
            """.trimIndent(),
        )

        assertTrue(db.sequenceNumbersEnabled)
        assertEquals(3.0, db.getMessages().first().sequenceStart)
        assertEquals(2.0, db.getMessages().first().sequenceStep)
        assertEquals("control", db.getActors().getValue("API").type)
        assertEquals("Gateway", db.getActors().getValue("API").description)
        assertEquals(listOf("API"), db.getBoxes().single().actorKeys)
        assertEquals(1, db.getCreatedActors()["Worker"])
        assertEquals(2, db.getDestroyedActors()["Worker"])
    }

    @Test
    fun returnsStructuredErrorForInvalidSequenceSyntax() {
        val result = SequenceJisonParser().parse(
            """
            sequenceDiagram
                Alice->>:missing participant
            """.trimIndent(),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.Parse>(error)
        assertTrue(error.line >= 1)
        assertTrue(error.column >= 1)
    }

    private fun parse(source: String): SequenceDb {
        val result = SequenceJisonParser().parse("$source\n")
        return assertIs<GMResult.Ok<SequenceDb>>(result).value
    }
}
