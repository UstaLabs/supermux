package com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidSecurityLevel
import com.swithun.cmpmermaid.core.officialClassDocumentationCases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClassJisonParserTest {
    @Test
    fun parsesClassesMembersAndRelations() {
        val db = parse(
            """
            classDiagram
                class Animal {
                    <<interface>>
                    +String name
                    +move(distance) bool*
                }
                Animal "1" <|.. "*" Duck : implements
            """.trimIndent(),
        )

        val animal = db.getClasses().getValue("Animal")
        assertEquals(listOf("interface"), animal.annotations)
        assertEquals("+String name", animal.members.single().displayText)
        assertEquals("+move(distance) : bool", animal.methods.single().displayText)
        assertTrue(animal.methods.single().italic)
        val relation = db.getRelations().single()
        assertEquals(ClassRelationType.EXTENSION, relation.relation.type1)
        assertEquals(ClassLineType.DOTTED_LINE, relation.relation.lineType)
        assertEquals("1", relation.relationTitle1)
        assertEquals("*", relation.relationTitle2)
        assertEquals("implements", relation.title)
    }

    @Test
    fun parsesNestedNamespacesAndNotes() {
        val db = parse(
            """
            classDiagram
                namespace Outer {
                    namespace Inner {
                        class Service
                        note for Service "boundary"
                    }
                }
            """.trimIndent(),
        )

        assertEquals("Outer.Inner", db.getClasses().getValue("Service").parent)
        assertEquals("Outer.Inner", db.getNotes().getValue("note0").parent)
        assertEquals("Outer", db.getNamespaces().getValue("Outer.Inner").parent)
    }

    @Test
    fun retainsSanitizedLinksAndLooseCallbacks() {
        val strict = parse(
            """
            classDiagram
                class Service
                click Service href "javascript:alert(1)" "_blank"
                click Service call run("a,b")
            """.trimIndent(),
            MermaidSecurityLevel.Strict,
        )
        assertEquals("about:blank", strict.getClasses().getValue("Service").link)
        assertEquals(null, strict.getClasses().getValue("Service").callbackName)

        val loose = parse(
            """
            classDiagram
                class Service
                click Service call run("a,b")
            """.trimIndent(),
            MermaidSecurityLevel.Loose,
        )
        assertEquals("run", loose.getClasses().getValue("Service").callbackName)
        assertEquals("\"a,b\"", loose.getClasses().getValue("Service").callbackArgs)
    }

    @Test
    fun parsesEveryOfficialClassDocumentationExample() {
        assertEquals(38, officialClassDocumentationCases.size)
        val failures = officialClassDocumentationCases.mapNotNull { case ->
            val preprocessed = when (val result = MermaidPreprocessor.preprocess(case.source)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return@mapNotNull "${case.id}: ${result.error.message}"
            }
            when (
                val result = ClassJisonParser(MermaidSecurityLevel.Strict)
                    .parse(preprocessed.code.cleaned + "\n")
            ) {
                is GMResult.Ok -> null
                is GMResult.Err -> "${case.id}: ${result.error.message}"
            }
        }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Class parser cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun parse(
        source: String,
        securityLevel: MermaidSecurityLevel = MermaidSecurityLevel.Strict,
    ): ClassDb {
        val result = ClassJisonParser(securityLevel).parse(source + "\n")
        return assertIs<GMResult.Ok<ClassDb>>(result).value
    }
}
