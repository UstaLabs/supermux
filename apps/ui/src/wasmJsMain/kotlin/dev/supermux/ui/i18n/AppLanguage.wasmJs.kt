package dev.supermux.ui.i18n

/**
 * Compose reads `navigator.languages`: put the chosen tag in front of the browser's own list (JetBrains' documented
 * route), installed once, so null gives the browser's list back.
 */
internal actual fun applyPlatformLanguage(tag: String?) {
    installLanguagesOverride()
    setCustomLanguage(tag)
}

private fun installLanguagesOverride(): Unit = js(
    """{
    if (!window.__supermuxLanguages) {
        window.__supermuxLanguages = true;
        var own = Object.getOwnPropertyDescriptor(Navigator.prototype, "languages");
        Object.defineProperty(Navigator.prototype, "languages", Object.assign({}, own, {
            get: function () { return window.__customLocale ? [window.__customLocale] : own.get.apply(this); }
        }));
    }
    }""",
)

private fun setCustomLanguage(tag: String?): Unit = js("{ window.__customLocale = tag; }")
