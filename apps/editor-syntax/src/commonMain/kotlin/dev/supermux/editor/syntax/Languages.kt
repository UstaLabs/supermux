package dev.supermux.editor.syntax

/** The query files every backend uses (src/commonMain/resources/queries/<lang>/<file>.scm). */
enum class QueryKind(val file: String) { HIGHLIGHTS("highlights"), INJECTIONS("injections"), FOLDS("folds") }

/**
 * Languages as the editor sees them: which grammar a file uses, what an injection's language name
 * means, and each language's queries.
 *
 * Language ids are the grammar names (`javascript`, `tsx`, `kotlin`, `bash`, `markdown_inline`, ...),
 * the same on every backend. [queries] defaults to the query files bundled into this library
 * (generated from src/commonMain/resources/queries by the build).
 */
class LanguageRegistry(private val queries: (language: String, kind: QueryKind) -> String? = BundledQueries::get) {
    /** Every language id a grammar exists for (native: compiled in; web: a .wasm). */
    val languages: Set<String> get() = LANGUAGE_IDS

    /** The language of a file, from its name (a path's last segment is used); null = plain text. */
    fun forFile(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val lower = base.lowercase()
        SPECIAL_NAMES[lower]?.let { return it.ifEmpty { null } }
        if (lower.startsWith("dockerfile.") || lower.endsWith(".dockerfile")) return null
        if (lower.startsWith(".") && lower.indexOf('.', 1) < 0) return DOTFILES[lower]
        val ext = lower.substringAfterLast('.', "")
        if (ext.isEmpty()) return null
        return EXTENSIONS[ext]
    }

    /**
     * An injection's language name (`#set! injection.language`, a Markdown fence's info string, a
     * Vue `lang` attribute) as a language id, or null when no grammar has it.
     */
    fun aliasFor(name: String): String? {
        // A fence info string may carry more: "kotlin title=x", "{r}", "js,runnable", ".py".
        val n = name.trim().lowercase().split(' ', '\t', ',').first().trim('{', '}', '.')
        if (n.isEmpty()) return null
        ALIASES[n]?.let { return it.ifEmpty { null } }
        if (n in LANGUAGE_IDS) return n
        return EXTENSIONS[n]
    }

    /** The text of [language]'s [kind] query, or null when it has none. */
    fun query(language: String, kind: QueryKind): String? = queries(language, kind)

    /**
     * [language]'s comment syntax (CM6's `commentTokens` language data, what `Mod-/` toggles), or
     * null for a language without comments (JSON).
     */
    fun commentTokens(language: String): dev.supermux.editor.core.CommentTokens? = COMMENT_TOKENS[language]

    companion object {
        val default: LanguageRegistry by lazy { LanguageRegistry() }

        /** Where a code-only grammar's tables blob lives among the app's resources. */
        fun tablesResource(language: String) = "editor-syntax/tables/$language.sesz"

        private fun tokens(line: String?, open: String? = null, close: String? = null) =
            dev.supermux.editor.core.CommentTokens(line, if (open != null && close != null) dev.supermux.editor.core.CommentTokens.BlockComment(open, close) else null)

        /** Comment tokens per language (CM6's language packages' `commentTokens`, and each language's own syntax). */
        internal val COMMENT_TOKENS: Map<String, dev.supermux.editor.core.CommentTokens> = run {
            val cLike = tokens("//", "/*", "*/")
            val hash = tokens("#")
            val markup = tokens(null, "<!--", "-->")
            mapOf(
                "c" to cLike, "cpp" to cLike, "c_sharp" to cLike, "java" to cLike, "javascript" to cLike, "typescript" to cLike,
                "tsx" to cLike, "kotlin" to cLike, "scala" to cLike, "swift" to cLike, "go" to cLike, "rust" to cLike,
                "dart" to cLike, "groovy" to cLike, "haxe" to cLike, "objc" to cLike, "glsl" to cLike, "php" to cLike,
                "php_only" to cLike,
                "css" to tokens(null, "/*", "*/"),
                "bash" to hash, "python" to hash, "ruby" to tokens("#", "=begin", "=end"), "perl" to hash, "r" to hash,
                "toml" to hash, "yaml" to hash, "nginx" to hash,
                "julia" to tokens("#", "#=", "=#"),
                "lua" to tokens("--", "--[[", "]]"),
                "haskell" to tokens("--", "{-", "-}"),
                "elm" to tokens("--", "{-", "-}"),
                "sql" to tokens("--", "/*", "*/"),
                "fsharp" to tokens("//", "(*", "*)"), "fsharp_signature" to tokens("//", "(*", "*)"),
                "ocaml" to tokens(null, "(*", "*)"), "ocaml_interface" to tokens(null, "(*", "*)"), "ocaml_type" to tokens(null, "(*", "*)"),
                "pascal" to tokens("//", "{", "}"),
                "clojure" to tokens(";"),
                "wat" to tokens(";;", "(;", ";)"),
                "vb_dotnet" to tokens("'"),
                "html" to markup, "xml" to markup, "dtd" to markup, "markdown" to markup, "markdown_inline" to markup, "vue" to markup,
            )
        }

        val LANGUAGE_IDS: Set<String> = setOf(
            "bash", "c", "c_sharp", "clojure", "cpp", "css", "dart", "dtd", "elm", "fsharp", "fsharp_signature",
            "glsl", "go", "groovy", "haskell", "haxe", "html", "java", "javascript", "json", "julia", "kotlin", "lua",
            "markdown", "markdown_inline", "nginx", "objc", "ocaml", "ocaml_interface", "ocaml_type", "pascal", "perl",
            "php", "php_only", "python", "r", "ruby", "rust", "scala", "sql", "swift", "toml", "tsx", "typescript",
            "vb_dotnet", "vue", "wat", "xml", "yaml",
        )

        /**
         * Extension -> language id. Every extension today's cm6 bundle recognises
         * (apps/android/codemirror/cm6-entry.mjs, langFor) is here; "" marks the ones tree-sitter has
         * no grammar for here, which stay plain text. A few common extras follow.
         */
        val CM6_EXTENSIONS: Map<String, String> = linkedMapOf(
            "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript",
            "ts" to "typescript", "mts" to "typescript", "cts" to "typescript", "tsx" to "tsx",
            "py" to "python", "pyi" to "python",
            "java" to "java",
            "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "hpp" to "cpp", "cxx" to "cpp", "hxx" to "cpp",
            "cs" to "c_sharp", "csx" to "c_sharp",
            "m" to "objc", "mm" to "objc",
            "rs" to "rust", "go" to "go", "php" to "php", "swift" to "swift",
            "kt" to "kotlin", "kts" to "kotlin",
            "dart" to "dart", "scala" to "scala", "sc" to "scala", "rb" to "ruby",
            "groovy" to "groovy", "gradle" to "groovy",
            "lua" to "lua", "pl" to "perl", "pm" to "perl", "r" to "r", "jl" to "julia", "hs" to "haskell",
            "erl" to "", "hrl" to "",
            "fs" to "fsharp", "fsx" to "fsharp", "fsi" to "fsharp_signature",
            "ml" to "ocaml", "mli" to "ocaml_interface",
            "clj" to "clojure", "cljs" to "clojure", "cljc" to "clojure",
            "elm" to "elm", "cr" to "", "coffee" to "",
            "sql" to "sql", "json" to "json", "jsonc" to "json",
            "md" to "markdown", "markdown" to "markdown", "mdx" to "markdown",
            "html" to "html", "htm" to "html", "vue" to "vue",
            "css" to "css", "scss" to "css", "sass" to "css", "less" to "css",
            "xml" to "xml", "svg" to "xml", "yaml" to "yaml", "yml" to "yaml", "toml" to "toml",
            "ini" to "", "properties" to "",
            "sh" to "bash", "bash" to "bash", "zsh" to "bash",
            "ps1" to "", "psm1" to "", "psd1" to "",
            "proto" to "", "tex" to "", "latex" to "", "diff" to "", "patch" to "",
            "wat" to "wat", "wast" to "wat", "pug" to "", "jade" to "",
            "f" to "", "for" to "", "f90" to "", "f95" to "",
            "pas" to "pascal", "vb" to "vb_dotnet", "vbs" to "", "hx" to "haxe",
            "glsl" to "glsl", "frag" to "glsl", "vert" to "glsl", "geom" to "glsl",
            "cmake" to "",
        )

        private val EXTENSIONS: Map<String, String?> = (
            CM6_EXTENSIONS + mapOf(
                "hh" to "cpp", "h++" to "cpp", "c++" to "cpp", "ipp" to "cpp", "inl" to "cpp",
                "json5" to "json", "geojson" to "json", "webmanifest" to "json",
                "xhtml" to "html", "xsd" to "xml", "xsl" to "xml", "xslt" to "xml", "plist" to "xml", "dtd" to "dtd",
                "ksh" to "bash", "ebuild" to "bash", "rake" to "ruby", "gemspec" to "ruby",
                "mdown" to "markdown", "mkd" to "markdown", "pyw" to "python", "gd" to "",
                "lhs" to "", "dpr" to "pascal", "pp" to "pascal", "comp" to "glsl", "tesc" to "glsl", "tese" to "glsl",
            )
        ).mapValues { (_, v) -> v.ifEmpty { null } }

        /** Whole file names (lower case); "" = plain text (no grammar). */
        private val SPECIAL_NAMES = mapOf(
            "dockerfile" to "", "containerfile" to "", "makefile" to "", "gnumakefile" to "", "cmakelists.txt" to "",
            "nginx.conf" to "nginx", "build.gradle" to "groovy", "settings.gradle" to "groovy", "jenkinsfile" to "groovy",
            "gemfile" to "ruby", "rakefile" to "ruby", "podfile" to "ruby", "vagrantfile" to "ruby",
            "pkgbuild" to "bash", "apkbuild" to "bash", "cargo.lock" to "toml", "pipfile" to "toml",
        )

        /** Extension-less dotfiles (`.bashrc`), by name. */
        private val DOTFILES = mapOf(
            ".bashrc" to "bash", ".bash_profile" to "bash", ".bash_aliases" to "bash", ".bash_logout" to "bash",
            ".profile" to "bash", ".zshrc" to "bash", ".zshenv" to "bash", ".zprofile" to "bash", ".zlogin" to "bash",
            ".envrc" to "bash", ".xprofile" to "bash",
        )

        /** Injection / fence names that are neither an id nor an extension; "" = known, no grammar. */
        private val ALIASES = mapOf(
            "js" to "javascript", "node" to "javascript", "ecmascript" to "javascript",
            "ts" to "typescript", "py" to "python", "python3" to "python", "py3" to "python",
            "sh" to "bash", "shell" to "bash", "zsh" to "bash", "console" to "bash", "shellsession" to "bash",
            "yml" to "yaml", "md" to "markdown",
            "markdown.inline" to "markdown_inline", "markdown-inline" to "markdown_inline",
            "kt" to "kotlin", "rs" to "rust", "rb" to "ruby", "golang" to "go",
            "cs" to "c_sharp", "csharp" to "c_sharp", "c#" to "c_sharp", "c-sharp" to "c_sharp",
            "c++" to "cpp", "objective-c" to "objc", "objectivec" to "objc", "obj-c" to "objc",
            "php-only" to "php_only", "ocaml-interface" to "ocaml_interface", "f#" to "fsharp",
            "vbnet" to "vb_dotnet", "vb.net" to "vb_dotnet", "delphi" to "pascal", "wasm" to "wat",
            "jsonc" to "json", "json5" to "json", "svg" to "xml", "htm" to "html", "gradle" to "groovy",
            // named by shipped injections or common in fences, but no grammar here
            "comment" to "", "regex" to "", "jsdoc" to "", "phpdoc" to "", "go-format-string" to "", "pod" to "",
            "latex" to "", "tex" to "", "jq" to "", "haskell_persistent" to "", "graphql" to "", "erb" to "", "awk" to "",
            "text" to "", "plaintext" to "", "txt" to "", "diff" to "", "dockerfile" to "", "make" to "", "cmake" to "",
        )
    }
}
