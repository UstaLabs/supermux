package dev.supermux.ui.browser

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.WebProfile
import dev.supermux.webview.runtime.WebViewRuntime

/**
 * The app's web views (#105): one runtime for the process, made the first time a Browser tab shows a page, so an app
 * that never opens one loads no web engine. Tests provide their own, on the fake engine, through [LocalAppWebViews].
 */
class AppWebViews(val runtime: WebViewRuntime) {
    /** The hosts (by the cookie's URL) whose preview cookie this run has put in [BROWSER_PROFILE]. */
    internal val previewCookies: MutableSet<String> = mutableSetOf()

    companion object {
        val app: AppWebViews by lazy { AppWebViews(WebViewRuntime(RuntimeConfig.platformDefault().copy(maxLive = 4))) }
    }
}

/** The web views a Browser pane uses: the app's, unless a test provides its own. */
val LocalAppWebViews = staticCompositionLocalOf<AppWebViews?> { null }

/** The Browser tabs' own profile: their cookies and logins, kept apart from every other web view of the app's. */
val BROWSER_PROFILE: WebProfile = WebProfile.Named("supermux-browser")
