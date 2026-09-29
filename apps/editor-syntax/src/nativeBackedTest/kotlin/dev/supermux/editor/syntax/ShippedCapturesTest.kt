package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertTrue

/** A new query can't silently lose colour: every capture it uses maps to a class or is ignored on purpose. */
class ShippedCapturesTest {
    @Test fun everyShippedCaptureMapsOrIsIgnoredOnPurpose() {
        val backend = testBackend()
        val registry = LanguageRegistry.default
        val names = HashMap<String, MutableSet<String>>()
        for (lang in registry.languages) {
            val text = registry.query(lang, QueryKind.HIGHLIGHTS) ?: continue
            backend.ensureLanguageNow(lang)
            backend.newQuery(lang, text).use { q -> q.captureNames.forEach { names.getOrPut(it) { HashSet() } += lang } }
        }
        val unmapped = names.filterKeys { tokenClassFor(it) == null && !it.startsWith("_") && it !in IGNORED }
        assertTrue(unmapped.isEmpty(), "captures with no token class and not in IGNORED:\n" +
            unmapped.entries.sortedBy { it.key }.joinToString("\n") { (n, l) -> "  $n  (${l.sorted()})" })
        println("ShippedCaptures: ${names.size} capture names, ${names.keys.count { tokenClassFor(it) != null }} drawn")
    }

    companion object {
        /**
         * Reviewed: shipped capture names that are deliberately not drawn (no class fits, or the
         * name marks structure rather than a token).
         */
        val IGNORED = setOf<String>(
            "none", "spell", "nospell", "conceal", "embedded", "text", "error",
            "local.function.elm", // elm marks a local function definition, which it also captures as @function
            "markup", // xml: character data between tags, plain text
            "meta.import.elm", "source.glsl", // elm: a whole import line, an embedded GLSL block
        )
    }
}
