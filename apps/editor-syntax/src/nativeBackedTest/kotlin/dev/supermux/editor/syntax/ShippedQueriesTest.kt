package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The query files this library ships (tools/fetch-queries.py, native/queries.lock.json). */
class ShippedQueriesTest {
    private val backend = testBackend()
    private val registry = LanguageRegistry.default

    private val supported = setOf(
        "eq?", "not-eq?", "any-eq?", "any-not-eq?", "any-of?", "not-any-of?",
        "match?", "not-match?", "any-match?", "any-not-match?", "set!",
    )

    @Test fun everyShippedQueryCompiles() {
        assertTrue(BundledQueries.keys.size > 100, "${BundledQueries.keys.size} query files")
        assertEquals(registry.languages, SyntaxLanguages.names().toSet(), "registry ids == compiled-in grammars")
        for (key in BundledQueries.keys) {
            val (lang, kind) = key.split('/')
            val text = registry.query(lang, QueryKind.entries.first { it.file == kind })!!
            backend.ensureLanguage(lang)
            SyntaxQuery(lang, text).use { q ->
                assertEquals(0, q.flags and 1, "$key uses #lua-match?")
                assertTrue(q.patternCount > 0 || text.lines().none { it.isNotBlank() && !it.startsWith(";") }, key)
            }
            val unknown = QueryText.predicates(text).map { it.first }.toSet() - supported
            assertTrue(unknown.isEmpty(), "$key uses predicates the backend does not evaluate: $unknown")
        }
    }

    @Test fun staticInjectionLanguagesResolve() {
        // tools/fetch-queries.py drops injections into languages with no grammar here (the lock's
        // unavailableInjectionLanguages): every one that ships must resolve.
        val unavailable = emptySet<String>()
        for (key in BundledQueries.keys.filter { it.endsWith("/injections") }) {
            val (lang, _) = key.split('/')
            for ((name, args) in QueryText.predicates(registry.query(lang, QueryKind.INJECTIONS)!!)) {
                if (name != "set!") continue
                // (#set! [@capture] key value): the key is a bare word or a string
                val rest = args.filter { it !is QueryText.Tok.Capture }
                val k = when (val t = rest.getOrNull(0)) { is QueryText.Tok.Word -> t.text; is QueryText.Tok.Str -> t.value; else -> null }
                val v = when (val t = rest.getOrNull(1)) { is QueryText.Tok.Word -> t.text; is QueryText.Tok.Str -> t.value; else -> null }
                if (k != "injection.language" || v == null) continue
                assertTrue(registry.aliasFor(v) != null || v in unavailable, "$key: injection.language \"$v\" resolves to nothing")
            }
        }
    }

    @Test fun kotlinHasHighlights() {
        val src = "package demo\n\nfun main(args: Array<String>) {\n    val greeting = \"hello\"\n    println(greeting)\n}\n"
        backend.ensureLanguage("kotlin")
        SyntaxParser("kotlin").use { p ->
            p.parse(src).use { t ->
                SyntaxQuery("kotlin", registry.query("kotlin", QueryKind.HIGHLIGHTS)!!).use { q ->
                    val c = q.captures(t, 0, src.length, ChunkedSource(src))
                    val names = List(c.size) { q.captureNames[c.capture(it)] to src.substring(c.start(it), c.end(it)) }
                    assertTrue(names.any { it.first.startsWith("keyword") && it.second == "fun" }, "$names")
                    assertTrue(names.any { it.first.startsWith("function") && it.second == "main" }, "$names")
                    assertTrue(names.any { it.first.startsWith("string") && it.second == "\"hello\"" }, "$names")
                }
            }
        }
    }

    /**
     * Query regexes are written for Rust's regex crate; the backend runs them with Kotlin's Regex
     * (java.util.regex on the JVM and Android, Kotlin/Native's engine on iOS). Every shipped regex
     * must compile, and match these samples exactly as on the JVM (golden/regexes.txt).
     */
    @Test fun everyMatchRegexBehavesTheSameEverywhere() {
        val regexes = BundledQueries.keys.flatMap { key ->
            val (lang, kind) = key.split('/')
            QueryText.regexes(registry.query(lang, QueryKind.entries.first { it.file == kind })!!)
        }.toSet().sorted()
        assertTrue(regexes.size > 50, "${regexes.size} regexes")
        val failed = ArrayList<String>()
        val table = regexes.joinToString("\n", postfix = "\n") { r ->
            val bits = try {
                val re = Regex(r)
                REGEX_SAMPLES.joinToString("") { if (re.containsMatchIn(it)) "1" else "0" }
            } catch (e: IllegalArgumentException) {
                failed += "/$r/: ${e.message}"
                "does not compile"
            }
            r.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t") + "\t" + bits
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
        // Name every regex and sample that differs from the JVM before the whole-file comparison.
        if (goldenUpdateDir() == null) {
            val golden = testResource("golden/regexes.txt").decodeToString().lines().filter { it.isNotEmpty() }
                .associate { it.substringBeforeLast('\t') to it.substringAfterLast('\t') }
            val diffs = table.lines().filter { it.isNotEmpty() }.mapNotNull { line ->
                val key = line.substringBeforeLast('\t')
                val bits = line.substringAfterLast('\t')
                val want = golden[key] ?: return@mapNotNull "/$key/: not in the golden file"
                if (want == bits) null else "/$key/: " + bits.indices.filter { bits[it] != want[it] }
                    .joinToString { "\"${REGEX_SAMPLES[it]}\" JVM=${want[it]} here=${bits[it]}" }
            }
            assertTrue(diffs.isEmpty(), "regexes that behave differently here than on the JVM:\n" + diffs.joinToString("\n"))
        }
        assertGolden("regexes.txt", table)
    }
}

/** Strings the shipped regexes are tried on: identifiers, cases, digits, non-ASCII, markers. */
private val REGEX_SAMPLES = listOf(
    "", "a", "A", "_", "_x", "__init__", "foo", "Foo", "FOO", "FOO_BAR", "fooBar", "foo_bar", "foo123", "Foo2",
    "123", "1.5", "0x1F", "1e10", "ağ", "Ğüç", "ÉCOLE", "é", "😀", "x😀", "self", "this", "super", "true", "None",
    "nil", "print", "println", "console", "require", "module", "exports", "String", "Int", "arrayOf", "TODO",
    "FIXME: x", "NOTE", "// c", "/* c */", "# c", "#!/bin/sh", "@param", "\$var", "\${x}", "x-y", "a.b", "a::b",
    " ", "\t", "a b", "\n", "defn", "def", "let", "fn", "->", "=>", "::", "<T>", "\"s\"", "'c'", "`t`", "\\d",
)
