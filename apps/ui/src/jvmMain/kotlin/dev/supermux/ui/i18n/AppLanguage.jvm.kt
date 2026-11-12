package dev.supermux.ui.i18n

import java.util.Locale

private var system: Locale? = null

/** Formatting and capitals read `Locale.getDefault()`; the system's is kept to return to. */
internal actual fun applyPlatformLanguage(tag: String?) {
    val home = system ?: Locale.getDefault().also { system = it }
    val next = tag?.let(Locale::forLanguageTag) ?: home
    if (Locale.getDefault() != next) Locale.setDefault(next)
}

internal actual fun systemLanguage(): String = (system ?: Locale.getDefault()).language
