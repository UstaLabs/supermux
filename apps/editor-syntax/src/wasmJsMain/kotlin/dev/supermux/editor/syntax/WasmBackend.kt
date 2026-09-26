package dev.supermux.editor.syntax

/**
 * The web's [SyntaxBackend]: [NativeBackend] itself (the same Kotlin, the same query cache) over the
 * ses_* binding compiled to wasm ([Ses] here is supermux-syntax.wasm through syntax-loader.mjs).
 * What differs is loading: the module is fetched and instantiated by [load], and a code-only
 * grammar's tables are fetched on the first [ensureLanguage] (`<tablesUrl>/<lang>.sesz`).
 * [isReady] never blocks. Everything runs on the one thread there is: the syntax worker yields
 * between parse slices ([platformSliceYield]).
 */
class WasmBackend private constructor(private val native: NativeBackend) : SyntaxBackend by native {
    override suspend fun ensureLanguage(language: String) {
        if (language !in languages) throw SyntaxException("unknown language $language", SyntaxStatus.UNKNOWN_LANGUAGE)
        if (!SyntaxLanguages.hasTables(language)) {
            val path = NativeBackend.tablesPath(language)
            if (!loaderHasResource(path)) {
                val url = loaderTablesUrl(language)
                loaderFetchResource(path, url).await()?.let {
                    throw SyntaxException("no tables for $language: fetching $it", SyntaxStatus.NO_TABLES)
                }
            }
            // straight from the fetched bytes into the module: no copy through Kotlin
            check(loaderProvideTablesFromResource(language, path), "provideTables($language)")
        }
        SyntaxLanguages.load(language)
    }

    companion object {
        /**
         * Fetch and instantiate supermux-syntax.wasm once per page. [wasmUrl]: null for the module
         * next to syntax-loader.mjs (a bundler emits it as an asset); [tablesUrl]: the directory
         * serving the code-only grammars' `<lang>.sesz`, null for `editor-syntax/tables/` next to the
         * module. A second load with another module URL fails.
         */
        suspend fun load(wasmUrl: String? = null, tablesUrl: String? = null): WasmBackend {
            loaderInitialize(wasmUrl, tablesUrl).await()
            return WasmBackend(NativeBackend())
        }
    }
}
