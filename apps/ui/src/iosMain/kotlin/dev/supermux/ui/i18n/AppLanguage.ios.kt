package dev.supermux.ui.i18n

import platform.Foundation.NSGlobalDomain
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

private const val APPLE_LANGUAGES = "AppleLanguages"

/** Formatting reads the preferred languages, which the app's own AppleLanguages override (JetBrains' documented route). */
internal actual fun applyPlatformLanguage(tag: String?) {
    val defaults = NSUserDefaults.standardUserDefaults
    if (tag == null) defaults.removeObjectForKey(APPLE_LANGUAGES) else defaults.setObject(listOf(tag), APPLE_LANGUAGES)
}

/** The device's own list (the global domain), not this app's override, which persists across launches. */
internal actual fun systemLanguage(): String = runCatching {
    // Not NSUserDefaults(suiteName = NSGlobalDomain): Apple documents that as returning nil, and it crashed the app
    // at launch (an NPE in ios-tests). The global domain's own list is read through persistentDomainForName.
    val global = (NSUserDefaults.standardUserDefaults.persistentDomainForName(NSGlobalDomain)?.get(APPLE_LANGUAGES) as? List<*>)
        ?.firstOrNull() as? String
    val tag = global ?: NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
    tag.substringBefore('-').substringBefore('_')
}.getOrDefault("en")
