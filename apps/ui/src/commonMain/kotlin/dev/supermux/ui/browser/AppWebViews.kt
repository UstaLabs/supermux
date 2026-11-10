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
    /** The preview token this run has put in [BROWSER_PROFILE] for each host (by the cookie's URL). */
    internal val previewCookies: MutableMap<String, String> = mutableMapOf()

    /**
     * Whether [cookie] still has to go into the store: not there yet, or a newer token. The broker keeps one preview token
     * per device and each mint replaces the last, so a store holding an older one gets a 401 (Ahmet's Fold, 2026-11-10).
     */
    internal fun needsPreviewCookie(cookie: dev.supermux.net.PreviewCookieDto): Boolean = previewCookies[cookie.url] != cookie.value

    internal fun previewCookieSet(cookie: dev.supermux.net.PreviewCookieDto) {
        previewCookies[cookie.url] = cookie.value
    }

    companion object {
        val app: AppWebViews by lazy { AppWebViews(WebViewRuntime(RuntimeConfig.platformDefault().copy(maxLive = 4))) }
    }
}

/** The web views a Browser pane uses: the app's, unless a test provides its own. */
val LocalAppWebViews = staticCompositionLocalOf<AppWebViews?> { null }

/** The Browser tabs' own profile: their cookies and logins, kept apart from every other web view of the app's. */
val BROWSER_PROFILE: WebProfile = WebProfile.Named("supermux-browser")

/** Each Browser tab's page icon, by view id: the workspace's, so its tab strip can draw them (#105). Not synced. */
val LocalBrowserIcons = staticCompositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>?> { null }
