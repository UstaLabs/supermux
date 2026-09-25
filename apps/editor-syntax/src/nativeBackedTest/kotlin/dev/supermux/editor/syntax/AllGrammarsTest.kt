package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every compiled-in grammar loads (bundled tables, or code-only reports NO_TABLES) and parses "". */
class AllGrammarsTest {
    @Test fun everyGrammarLoadsOrReportsNoTables() {
        val names = SyntaxLanguages.names()
        assertTrue(names.size >= 40, "only ${names.size} grammars compiled in: $names")
        for (n in names) {
            if (SyntaxLanguages.hasTables(n)) {
                SyntaxParser(n).use { p -> p.parse("").close() }
            } else {
                val e = runCatching { SyntaxLanguages.load(n) }.exceptionOrNull() as SyntaxException
                assertEquals(SyntaxStatus.NO_TABLES, e.status, n)
            }
        }
    }
}
