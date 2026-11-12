// #123: the app's language. Strings live in apps/ui/i18n/values (English, the source) and values-<tag>, compiled to
// compact catalogs (Catalog.kt); the system's language picks among them unless this device chose one in Settings › General.
package dev.supermux.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toUpperCase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * A language the app ships. [tag] is the stored value and the resource qualifier (null = follow the system);
 * [endonym] is the language's own name, shown untranslated so a reader can find theirs in any language.
 */
enum class AppLanguage(val tag: String?, val endonym: String) {
    SYSTEM(null, ""),
    ENGLISH("en", "English"),
    TURKISH("tr", "Türkçe"),
    ;

    companion object {
        /** An unknown or absent tag follows the system: a language dropped from a later build cannot strand anyone. */
        fun parse(raw: String?): AppLanguage = entries.firstOrNull { it.tag != null && it.tag == raw } ?: SYSTEM
    }
}

/** The language in force below [ProvideAppLanguage]; [localized] reads it so a change recomposes every text. */
/** The language in force below [ProvideAppLanguage]; text in capitals and formatting read it. */
val LocalAppLanguage = compositionLocalOf { AppLanguage.SYSTEM }

/** The catalogs [localized] reads: the language in force (a shipped tag) and English, the fallback. */
class LoadedLanguage(val language: String, val chosen: Catalog?, val english: Catalog?)

/** Unprovided (a test or preview outside the theme): English, read when first asked for. */
val LocalCatalogs = compositionLocalOf<LoadedLanguage?> { null }

/**
 * Points the platform's locale at [language] (null tag = back to the system's): the JVM's and Android's default
 * locale, iOS's AppleLanguages, the browser's navigator.languages. Formatting and capitals read it.
 */
internal expect fun applyPlatformLanguage(tag: String?)

/** The system's own language (two letters), as it was before [applyPlatformLanguage] changed anything. */
internal expect fun systemLanguage(): String

private var providers = 0

/**
 * Makes [language] the one every [localized] text below reads, without a restart: its catalog and English are
 * provided together, so a change recomposes each text in place and keeps every screen's state.
 */
@Composable
fun ProvideAppLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    applyPlatformLanguage(language.tag)
    // Desktop has a provider per window: the system's language comes back only once the last one is gone.
    DisposableEffect(Unit) {
        providers++
        onDispose { if (--providers == 0) applyPlatformLanguage(null) }
    }
    val tag = shippedLanguage(language.tag ?: systemLanguage())
    val english by catalogState("en")
    val chosen by catalogState(tag)
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalCatalogs provides LoadedLanguage(tag, chosen, english),
        content = content,
    )
}

/** [language]'s catalog: at once where it can be read synchronously or was preloaded, else when its read finishes. */
// Not produceState: its state is remembered without the key, so a new language would keep the old catalog.
@Composable
private fun catalogState(language: String): State<Catalog?> {
    val state = remember(language) { mutableStateOf(Catalogs.now(language)) }
    if (state.value == null) LaunchedEffect(language) { state.value = Catalogs.load(language) }
    return state
}

/** Outside any provider (a test, a preview): English, read once and kept by [Catalogs]. */
@Composable
@ReadOnlyComposable
private fun loadedLanguage(): LoadedLanguage =
    LocalCatalogs.current ?: LoadedLanguage("en", null, Catalogs.now("en"))

/**
 * A string in the app's language, English where the language lacks it. Use this, never `stringResource`. The
 * plain overload exists for size: most calls have no arguments, and a vararg call builds an array at every one of
 * them (~850 call sites, measurable in the web bundle).
 */
@Composable
@ReadOnlyComposable
fun localized(key: StringKey): String {
    val l = loadedLanguage()
    return lookup(key, l.chosen, l.english)
}

/** [localized] with `%1$s` / `%1$d` placeholders filled from [args]. */
@Composable
@ReadOnlyComposable
fun localized(key: StringKey, vararg args: Any): String {
    val l = loadedLanguage()
    return formatCatalogText(lookup(key, l.chosen, l.english), args)
}

/** A count's string in the app's language, with that language's plural rules. */
@Composable
@ReadOnlyComposable
fun localizedPlural(key: PluralKey, count: Int, vararg args: Any): String {
    val l = loadedLanguage()
    return formatCatalogText(lookupPlural(key, l.language, count, l.chosen, l.english), args)
}

/** A Settings search label (a `search_` key) looked up by name, or null when there is no such key. */
@Composable
@ReadOnlyComposable
fun localizedOrNull(name: String): String? {
    val id = I18nFiles.searchKey(name)
    if (id < 0) return null
    val l = loadedLanguage()
    return lookup(StringKey(id), l.chosen, l.english)
}

/** Each option's label in the app's language, looked up once so a plain `(T) -> String` (a kit's optionLabel) can read it. */
@Composable
fun <T> localizedLabels(options: List<T>, key: (T) -> StringKey): Map<T, String> =
    options.associateWith { localized(key(it)) }

/**
 * This text in capitals by the app language's rules: Turkish "i" becomes "İ" (Kotlin's `uppercase()` knows no
 * language and gives "I"). Use it for every translated text shown in capitals.
 */
@Composable
fun String.uppercaseInAppLanguage(): String {
    LocalAppLanguage.current
    return toUpperCase(Locale.current)
}
