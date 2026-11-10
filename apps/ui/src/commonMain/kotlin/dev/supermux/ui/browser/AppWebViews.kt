package dev.supermux.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.supermux.proto.ViewDto
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.browser.BrowserTabSync
import dev.supermux.webview.WebViewState
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.WebProfile
import dev.supermux.webview.runtime.WebViewRuntime

/**
 * The app's web views (#105): one runtime for the process, made the first time a Browser tab shows a page, so an app
 * that never opens one loads no web engine. Tests provide their own, on the fake engine, through [LocalAppWebViews].
 */
class AppWebViews(val runtime: WebViewRuntime) {
    /**
     * Each Browser tab's page while the tab exists, by view id: its [WebViewState] and what the pane asked of it. A tab
     * that leaves the composition (another tab in front) keeps them, so coming back is the same state and the pool
     * hands it its parked page, live: no reload, its scroll, forms and scripts as they were. Past the pool's own limits
     * (time parked, memory) the page comes back from its saved state instead. The oldest beyond [MAX_TABS] go.
     */
    private val tabs = LinkedHashMap<String, BrowserTabPage>()

    internal fun tab(viewId: String): BrowserTabPage {
        val page = tabs.remove(viewId) ?: BrowserTabPage()
        tabs[viewId] = page
        while (tabs.size > MAX_TABS) tabs.remove(tabs.keys.first())
        return page
    }

    /** The tab [viewId] was closed: its page goes with it. */
    fun closeTab(viewId: String) {
        tabs.remove(viewId)
    }

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
        private const val MAX_TABS = 32

        private val made = lazy { AppWebViews(WebViewRuntime(RuntimeConfig.platformDefault().copy(maxLive = 4))) }
        val app: AppWebViews get() = made.value

        /** The app's web views if a Browser tab has made them, without making them. */
        internal val appIfMade: AppWebViews? get() = if (made.isInitialized()) made.value else null
    }
}

/** The web views a Browser pane uses: the app's, unless a test provides its own. */
val LocalAppWebViews = staticCompositionLocalOf<AppWebViews?> { null }

/** The Browser tabs' own profile: their cookies and logins, kept apart from every other web view of the app's. */
val BROWSER_PROFILE: WebProfile = WebProfile.Named("supermux-browser")

/** Each Browser tab's page icon, by view id: the workspace's, so its tab strip can draw them (#105). Not synced. */
val LocalBrowserIcons = staticCompositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>?> { null }

/** A Browser tab's page and the pane's hold on it, kept while the tab exists ([AppWebViews.tab]). */
internal class BrowserTabPage {
    val state: WebViewState = WebViewState(BLANK)
    val sync: BrowserTabSync = BrowserTabSync()
    var body: BrowserBody by mutableStateOf(BrowserBody.NewTab)
    var requested: String? by mutableStateOf(null)
    var zoom: Float by mutableFloatStateOf(1f)
}

/**
 * While a workspace shows, a Browser tab that leaves its [views] (closed, here or on another device) lets its page go
 * ([AppWebViews.closeTab]). Tabs of workspaces not shown go when the app's tab memory is full, oldest first.
 */
@Composable
fun ReleaseClosedBrowserTabs(views: Collection<ViewDto>) {
    val provided = LocalAppWebViews.current
    val ids = views.filter { it.kind == "browser" }.map { it.id }.toSet()
    val seen = remember { mutableSetOf<String>() }
    LaunchedEffect(ids) {
        val webViews = provided ?: AppWebViews.appIfMade
        if (webViews != null) (seen - ids).forEach(webViews::closeTab)
        seen.clear()
        seen += ids
    }
}
