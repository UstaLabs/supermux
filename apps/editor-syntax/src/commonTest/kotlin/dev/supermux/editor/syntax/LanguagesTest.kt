package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagesTest {
    private val registry = LanguageRegistry.default

    /**
     * Transcribed from apps/android/codemirror/cm6-entry.mjs, langFor's `switch (ext)`: every
     * extension today's bundle recognises, with the grammar it gets here (null: no tree-sitter
     * grammar, plain text; listed in native/README.md).
     */
    private val cm6 = listOf(
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript",
        "ts" to "typescript", "mts" to "typescript", "cts" to "typescript", "tsx" to "tsx",
        "py" to "python", "pyi" to "python", "java" to "java",
        "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "hpp" to "cpp", "cxx" to "cpp", "hxx" to "cpp",
        "cs" to "c_sharp", "csx" to "c_sharp", "m" to "objc", "mm" to "objc",
        "rs" to "rust", "go" to "go", "php" to "php", "swift" to "swift", "kt" to "kotlin", "kts" to "kotlin",
        "dart" to "dart", "scala" to "scala", "sc" to "scala", "rb" to "ruby", "groovy" to "groovy", "gradle" to "groovy",
        "lua" to "lua", "pl" to "perl", "pm" to "perl", "r" to "r", "jl" to "julia", "hs" to "haskell",
        "erl" to null, "hrl" to null, "fs" to "fsharp", "fsx" to "fsharp", "fsi" to "fsharp_signature",
        "ml" to "ocaml", "mli" to "ocaml_interface", "clj" to "clojure", "cljs" to "clojure", "cljc" to "clojure",
        "elm" to "elm", "cr" to null, "coffee" to null, "sql" to "sql", "json" to "json", "jsonc" to "json",
        "md" to "markdown", "markdown" to "markdown", "mdx" to "markdown", "html" to "html", "htm" to "html",
        "vue" to "vue", "css" to "css", "scss" to "css", "sass" to "css", "less" to "css", "xml" to "xml", "svg" to "xml",
        "yaml" to "yaml", "yml" to "yaml", "toml" to "toml", "ini" to null, "properties" to null,
        "sh" to "bash", "bash" to "bash", "zsh" to "bash", "ps1" to null, "psm1" to null, "psd1" to null,
        "proto" to null, "tex" to null, "latex" to null, "diff" to null, "patch" to null, "wat" to "wat", "wast" to "wat",
        "pug" to null, "jade" to null, "f" to null, "for" to null, "f90" to null, "f95" to null, "pas" to "pascal",
        "vb" to "vb_dotnet", "vbs" to null, "hx" to "haxe", "glsl" to "glsl", "frag" to "glsl", "vert" to "glsl",
        "geom" to "glsl", "cmake" to null,
    )

    @Test fun forFileCoversTheCm6Extensions() {
        assertEquals(cm6.map { it.first }.toSet(), LanguageRegistry.CM6_EXTENSIONS.keys, "the registry's cm6 table")
        for ((ext, lang) in cm6) {
            assertEquals(lang, registry.forFile("src/Main.$ext"), ".$ext")
            assertEquals(lang, registry.forFile("DIR\\FILE.${ext.uppercase()}"), ".${ext.uppercase()}")
            if (lang != null) assertTrue(lang in registry.languages, lang)
        }
    }

    @Test fun specialFileNames() {
        assertNull(registry.forFile("Dockerfile"))
        assertNull(registry.forFile("dockerfile.dev"))
        assertNull(registry.forFile("CMakeLists.txt"))
        assertNull(registry.forFile("Makefile"))
        assertEquals("nginx", registry.forFile("/etc/nginx/nginx.conf"))
        assertEquals("bash", registry.forFile(".bashrc"))
        assertEquals("bash", registry.forFile("/home/u/.zshrc"))
        assertEquals("groovy", registry.forFile("build.gradle"))
        assertEquals("kotlin", registry.forFile("build.gradle.kts"))
        assertNull(registry.forFile(".gitignore"))
        assertNull(registry.forFile("README"))
    }

    @Test fun injectionNamesResolve() {
        assertEquals("javascript", registry.aliasFor("js"))
        assertEquals("typescript", registry.aliasFor("ts"))
        assertEquals("bash", registry.aliasFor("sh"))
        assertEquals("python", registry.aliasFor("py"))
        assertEquals("yaml", registry.aliasFor("yml"))
        assertEquals("markdown_inline", registry.aliasFor("markdown.inline"))
        assertEquals("kotlin", registry.aliasFor("kotlin"))
        assertEquals("kotlin", registry.aliasFor("Kotlin title=\"Main.kt\""))
        assertEquals("r", registry.aliasFor("{r}"))
        assertEquals("kotlin", registry.aliasFor("kts"))
        assertEquals("c_sharp", registry.aliasFor("c#"))
        assertNull(registry.aliasFor("foobar"))
        assertNull(registry.aliasFor("comment"))
        assertNull(registry.aliasFor(""))
    }
}
