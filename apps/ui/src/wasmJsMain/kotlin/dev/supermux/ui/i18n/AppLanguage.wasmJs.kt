package dev.supermux.ui.i18n

/**
 * Formatting reads `navigator.languages`: put the chosen tag in front of the browser's own list (JetBrains' documented
 * route), installed once, so null gives the browser's list back.
 */
internal actual fun applyPlatformLanguage(tag: String?) {
    installLanguagesOverride()
    setCustomLanguage(tag)
}

private fun installLanguagesOverride(): Unit = js(
    """{
    if (!window.__supermuxLanguages) {
        var own = Object.getOwnPropertyDescriptor(Navigator.prototype, "languages");
        window.__supermuxLanguages = own;
        Object.defineProperty(Navigator.prototype, "languages", Object.assign({}, own, {
            get: function () { return window.__customLocale ? [window.__customLocale] : own.get.apply(this); }
        }));
    }
    }""",
)

private fun setCustomLanguage(tag: String?): Unit = js("{ window.__customLocale = tag; }")

/** The browser's own first language, read past the override above. */
internal actual fun systemLanguage(): String = browserLanguage().substringBefore('-')

private fun browserLanguage(): String = js(
    "((window.__supermuxLanguages ? window.__supermuxLanguages.get.call(navigator) : navigator.languages) || [])[0] || navigator.language || 'en'",
)
