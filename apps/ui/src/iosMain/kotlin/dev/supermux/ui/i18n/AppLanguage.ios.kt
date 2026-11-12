package dev.supermux.ui.i18n

import platform.Foundation.NSUserDefaults

private const val APPLE_LANGUAGES = "AppleLanguages"

/** Compose reads the preferred languages, which the app's own AppleLanguages override (JetBrains' documented route). */
internal actual fun applyPlatformLanguage(tag: String?) {
    val defaults = NSUserDefaults.standardUserDefaults
    if (tag == null) defaults.removeObjectForKey(APPLE_LANGUAGES) else defaults.setObject(listOf(tag), APPLE_LANGUAGES)
}
