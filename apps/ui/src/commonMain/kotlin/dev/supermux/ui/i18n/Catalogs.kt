// #123: loading the catalogs. Each language is the files I18nFiles.NAMES under files/i18n/values[-tag]/, read
// once and kept. Desktop, Android and iOS read English synchronously the first time a string is asked for (bundled
// files, a few ms); the web reads everything it needs before its first frame (Main.kt), in parallel with the probe.
package dev.supermux.ui.i18n

import dev.supermux.ui.resources.Res
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.concurrent.Volatile

object Catalogs {
    @Volatile
    private var loaded: Map<String, Catalog> = emptyMap()

    /** The languages the app ships ("en" first), from the catalog folders. */
    val languages: List<String> get() = I18nFiles.LANGUAGES

    /** [language]'s catalog if it has been read. */
    fun cached(language: String): Catalog? = loaded[language]

    /** Reads [language]'s files (in parallel) once; a file that fails to read is left out, so English fills in. */
    suspend fun load(language: String): Catalog {
        loaded[language]?.let { return it }
        val dir = if (language == "en") "values" else "values-$language"
        val parts = coroutineScope {
            I18nFiles.NAMES.map { name ->
                async { runCatching { Res.readBytes("files/i18n/$dir/$name.xml").decodeToString() }.getOrNull() }
            }.awaitAll()
        }
        val catalog = parts.filterNotNull().map(::parseCatalog).fold(Catalog.EMPTY) { a, b -> a + b }
        loaded = loaded + (language to catalog)
        return catalog
    }

    /** [language]'s catalog now: read synchronously where the platform can (bundled files); null on the web until loaded. */
    fun now(language: String): Catalog? = loaded[language] ?: readBlocking { load(language) }
}

/** Runs [block] to completion on this thread where the platform allows it (JVM, Android, iOS); null on the web. */
internal expect fun <T> readBlocking(block: suspend () -> T): T?

/** The language a shipped catalog exists for: [tag] when shipped, else English. */
fun shippedLanguage(tag: String?): String = tag?.takeIf { it in Catalogs.languages } ?: "en"

/**
 * Reads English and [language]'s catalog in parallel: the web calls it beside its first network probe, so the first
 * frame never waits for a catalog after the probe.
 */
suspend fun preloadCatalogs(language: AppLanguage) {
    val tag = shippedLanguage(language.tag ?: systemLanguage())
    coroutineScope { listOf("en", tag).distinct().map { async { Catalogs.load(it) } }.awaitAll() }
}

/**
 * A catalog file's web path with the catalogs' content version, so the broker's year-long `/assets/` cache can never
 * serve one release's strings to another; any other path is left alone.
 */
fun versionedResourcePath(path: String): String =
    if (path.startsWith("files/i18n/")) "$path?v=${I18nFiles.VERSION}" else path
