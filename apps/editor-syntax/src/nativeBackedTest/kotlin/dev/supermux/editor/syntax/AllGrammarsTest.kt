package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every compiled-in grammar is usable: bundled tables, or tables provided from the app's resources. */
class AllGrammarsTest {
    @Test fun everyGrammarParsesAfterEnsureLanguage() {
        val backend = testBackend()
        val names = SyntaxLanguages.names()
        assertTrue(names.size >= 40, "only ${names.size} grammars compiled in: $names")
        val codeOnly = names.filter { !SyntaxLanguages.hasTables(it) }
        for (n in names) {
            backend.ensureLanguage(n)
            assertTrue(SyntaxLanguages.hasTables(n), n)
            backend.newParser(n).use { p -> p.parse(ChunkedSource("x = 1\n"), null).close() }
        }
        println("AllGrammars: ${names.size} grammars, ${codeOnly.size} provided from resources here: $codeOnly")
    }

    @Test fun aMissingTablesResourceIsNamed() {
        // Only meaningful while some code-only grammar is still unloaded in this process (jvmTest
        // forks per class; on a device another test class may have loaded them all already).
        val unloaded = SyntaxLanguages.names().firstOrNull { !SyntaxLanguages.hasTables(it) } ?: return
        val e = runCatching { NativeBackend(tables = { null }).ensureLanguage(unloaded) }.exceptionOrNull() as SyntaxException
        assertEquals(SyntaxStatus.NO_TABLES, e.status)
        assertTrue("editor-syntax/tables/$unloaded.sesz" in e.message!!, e.message)
    }

    @Test fun anUnknownLanguageIsRefused() {
        val e = runCatching { testBackend().ensureLanguage("cobol") }.exceptionOrNull() as SyntaxException
        assertEquals(SyntaxStatus.UNKNOWN_LANGUAGE, e.status)
    }
}
