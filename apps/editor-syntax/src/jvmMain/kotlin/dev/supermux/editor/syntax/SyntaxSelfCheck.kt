package dev.supermux.editor.syntax

import kotlin.system.exitProcess

/**
 * `java -cp "<app dir>\app\*" dev.supermux.editor.syntax.SyntaxSelfCheck` (a Windows image): the editor's syntax highlighting, proven
 * inside a PACKAGED desktop app with its own runtime and jars (#138). The library loads from the jar through
 * [SesNativeLoader], a compiled-in grammar and a tables-only one parse, and their highlight queries capture.
 * release.yml runs it on the built Windows image; in the app, the same failure only turns every editor into
 * plain text. Prints one line and exits 0, or prints the reason and exits 1.
 */
object SyntaxSelfCheck {
    @JvmStatic fun main(args: Array<String>) {
        try {
            println("editor-syntax self-check OK (${SesNativeLoader.platformKey()}): ${run()}")
        } catch (t: Throwable) {
            System.err.println("editor-syntax self-check FAILED (${SesNativeLoader.platformKey()}): $t")
            t.printStackTrace()
            exitProcess(1)
        }
    }

    /** What was highlighted, per language. Throws on any failure. */
    fun run(): String {
        val backend = NativeBackend()
        return SAMPLES.joinToString("; ") { (language, text) ->
            backend.ensureLanguageNow(language)
            val source = LanguageRegistry.default.query(language, QueryKind.HIGHLIGHTS)
                ?: throw IllegalStateException("$language has no highlights query")
            SyntaxParser(language).use { parser ->
                parser.parse(text).use { tree ->
                    check(!tree.hasError) { "$language: the sample parsed with errors: ${tree.sexp()}" }
                    SyntaxQuery(language, source).use { query ->
                        val n = query.captures(tree, 0, text.length, TextSource { i -> if (i >= text.length) "" else text.substring(i) }).size
                        check(n > 0) { "$language: the highlights query captured nothing" }
                        "$language $n captures"
                    }
                }
            }
        }
    }

    /** kotlin is compiled into the library; haskell's tables come from the jar's resources. */
    private val SAMPLES = listOf(
        "kotlin" to "fun main() {\n    val greeting = \"hi\" // a comment\n    println(greeting)\n}\n",
        "haskell" to "main = print 1\n",
    )
}
