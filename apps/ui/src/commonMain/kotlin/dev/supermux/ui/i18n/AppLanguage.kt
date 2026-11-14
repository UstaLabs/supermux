// #123: the app's language. Strings live in apps/ui/i18n/values (English, the source) and values-<tag>, compiled to
// compact catalogs (Catalog.kt); the system's language picks among them unless this device chose one in Settings › General.
package dev.supermux.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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

/** The language in force below [ProvideAppLanguage]; text in capitals and formatting read it. */
val LocalAppLanguage = compositionLocalOf { AppLanguage.SYSTEM }

/** The catalogs [localized] reads: the language in force (a shipped tag) and English, the fallback. */
class LoadedLanguage(val language: String, val chosen: Catalog?, val english: Catalog?) {
    fun sameAs(other: LoadedLanguage?) =
        other != null && other.language == language && other.chosen === chosen && other.english === english
}

/**
 * The catalogs the app is showing, readable ANYWHERE — a composable, a plain helper, a click handler. It is snapshot
 * state: a composition that reads it (through [localized]) recomposes when the language changes, so helpers need no
 * composable twin. [ProvideAppLanguage] keeps it current; unset (a test, a preview) means English. One implementation:
 * tests read English through the same path.
 */
private val shown = mutableStateOf<LoadedLanguage?>(null)

/** Tests only: show [language] as if a provider did (null = back to English). */
internal fun showForTest(language: LoadedLanguage?) {
    shown.value = language
}

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
        onDispose {
            if (--providers == 0) {
                applyPlatformLanguage(null)
                shown.value = null
            }
        }
    }
    val tag = shippedLanguage(language.tag ?: systemLanguage())
    val english by catalogState("en")
    val chosen by catalogState(tag)
    // Written during composition, before the content below reads it, so even the first frame is in the right language.
    val loaded = LoadedLanguage(tag, chosen, english)
    if (!loaded.sameAs(shown.value)) shown.value = loaded
    CompositionLocalProvider(LocalAppLanguage provides language, content = content)
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
private fun loadedLanguage(): LoadedLanguage = shown.value ?: LoadedLanguage("en", null, Catalogs.now("en"))

/**
 * A string in the app's language, English where the language lacks it. Use this, never `stringResource`. A plain
 * function, not a composable: callable from helpers and handlers, and no composer bookkeeping at ~2,000 call sites
 * (measurable in the web bundle). Read in a composition, it recomposes on a language change ([shown] is state). The
 * no-argument overload exists for size too: a vararg call builds an array at every call site.
 */
fun localized(key: StringKey): String {
    val l = loadedLanguage()
    return lookup(key, l.chosen, l.english)
}

/** [localized] with one placeholder: the commonest shape, kept off the vararg so no call site builds an array. */
fun localized(key: StringKey, arg: Any): String {
    val l = loadedLanguage()
    return formatCatalogText(lookup(key, l.chosen, l.english), arrayOf(arg))
}

/** [localized] with `%1$s` / `%1$d` placeholders filled from [args]. */
fun localized(key: StringKey, vararg args: Any): String {
    val l = loadedLanguage()
    return formatCatalogText(lookup(key, l.chosen, l.english), args)
}

/** A count's string whose only placeholder is the count itself (the commonest shape: `localizedPlural(k, n, n)`). */
fun localizedPlural(key: PluralKey, count: Int, arg: Any): String {
    val l = loadedLanguage()
    return formatCatalogText(lookupPlural(key, l.language, count, l.chosen, l.english), arrayOf(arg))
}

/** A count's string in the app's language, with that language's plural rules. */
fun localizedPlural(key: PluralKey, count: Int, vararg args: Any): String {
    val l = loadedLanguage()
    return formatCatalogText(lookupPlural(key, l.language, count, l.chosen, l.english), args)
}

/** Each option's label in the app's language, looked up once so a plain `(T) -> String` (a kit's optionLabel) can read it. */
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
