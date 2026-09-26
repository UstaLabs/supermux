package dev.supermux.editor.syntax

/**
 * The web's [SyntaxBackend]: [NativeBackend] itself (the same Kotlin, the same query cache) over the
 * ses_* binding compiled to wasm ([Ses] here is supermux-syntax.wasm through syntax-loader.mjs).
 * What differs is loading: the module is fetched and instantiated by [load], and a code-only
 * grammar's tables are fetched on the first [ensureLanguage] (`<tablesUrl>/<lang>.sesz`, 30 s
 * timeout). [isReady] never blocks. Everything runs on the one thread there is: the syntax worker
 * yields between parse slices ([platformSliceYield]).
 *
 * If the runtime dies (a trap: tree-sitter aborts on out of memory), every call fails with
 * SyntaxException(RUNTIME_DEAD) and the syntax worker turns syntax off for its documents; a new
 * runtime needs a page load (the loader keeps one per page).
 */
class WasmBackend internal constructor(
    private val native: NativeBackend,
    private val tablesUrlFor: (String) -> String = { loaderTablesUrl(it) },
) : SyntaxBackend by native {
    override suspend fun ensureLanguage(language: String) {
        if (language !in languages) throw SyntaxException("unknown language $language", SyntaxStatus.UNKNOWN_LANGUAGE)
        if (!SyntaxLanguages.hasTables(language)) {
            val path = "wasm-backend/$language.sesz"
            loaderFetchResource(path, tablesUrlFor(language)).await()?.let {
                throw SyntaxException("no tables for $language: fetching $it", SyntaxStatus.NO_TABLES)
            }
            // Straight from the fetched bytes into the module (no copy through Kotlin), then dropped
            // whatever the answer: the module keeps its own copy of good tables, and refused ones
            // (BAD_TABLES) are fetched again next time.
            val st = try { loaderProvideTablesFromResource(language, path) } finally { loaderDropResource(path) }
            check(st, "provideTables($language)")
        }
        SyntaxLanguages.load(language)
    }

    companion object {
        /**
         * Fetch and instantiate supermux-syntax.wasm once per page. [wasmUrl]: null for the module
         * next to syntax-loader.mjs (a bundler emits it as an asset); [tablesUrl]: the directory
         * serving the code-only grammars' `<lang>.sesz`, null for `editor-syntax/tables/` next to the
         * module. A later load with another module or tables URL fails and changes nothing.
         */
        suspend fun load(wasmUrl: String? = null, tablesUrl: String? = null): WasmBackend {
            loaderInitialize(wasmUrl, tablesUrl).await()
            return WasmBackend(NativeBackend())
        }
    }
}
