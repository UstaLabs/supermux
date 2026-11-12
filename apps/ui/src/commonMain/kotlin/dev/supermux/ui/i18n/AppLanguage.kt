// #123: the app's language. Strings live in composeResources/values (English, the source) and values-<tag>; the
// system's language picks among them unless this device chose one in Settings › General.
package dev.supermux.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
val LocalAppLanguage = compositionLocalOf { AppLanguage.SYSTEM }

/**
 * Points the platform's locale at [language] (null tag = back to the system's), the one place the resource library
 * reads it: the JVM's and Android's default locale, iOS's AppleLanguages, the browser's navigator.languages.
 */
internal expect fun applyPlatformLanguage(tag: String?)

/**
 * Makes [language] the one every [localized] text below resolves in, without a restart. Compose's own resources read
 * the platform locale but don't observe it, so [localized] also reads [LocalAppLanguage]: a change recomposes each
 * text in place, keeping every screen's state (a `key()` around the app, the documented route, would reset it all).
 */
private var providers = 0

@Composable
fun ProvideAppLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    applyPlatformLanguage(language.tag)
    // Desktop has a provider per window: the system's language comes back only once the last one is gone.
    DisposableEffect(Unit) {
        providers++
        onDispose { if (--providers == 0) applyPlatformLanguage(null) }
    }
    CompositionLocalProvider(LocalAppLanguage provides language, content = content)
}

/** A string in the app's language. Use this, never `stringResource` directly (tests/i18n-strings.test.ts). */
@Composable
fun localized(resource: StringResource, vararg args: Any): String {
    LocalAppLanguage.current
    return stringResource(resource, *args)
}

/** A count's string in the app's language, with that language's plural rules. */
@Composable
fun localizedPlural(resource: PluralStringResource, count: Int, vararg args: Any): String {
    LocalAppLanguage.current
    return pluralStringResource(resource, count, *args)
}
