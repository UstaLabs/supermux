package dev.supermux.webview.engine.android

/**
 * Which WebView callbacks speak for a document the runtime may still hear: the [dev.supermux.webview.engine.WebEngine]
 * contract's **Documents** ("once load, reload or restoreState returns, no event caused by an earlier document is
 * delivered"). Pure and main-thread-only: the engine feeds it what WebView reports, in the order the main looper
 * delivers it.
 *
 * Why the order is enough: WebView posts every callback the engine listens to (its WebViewClient's, WebChromeClient's
 * and WebMessageListener's, and the NavigationListener's through the engine's posting executor) to the main looper as
 * ordinary messages, in the order things happened; on a commit it reports the navigation before anything the new
 * document does (chromium `AwWebContentsObserver`, `AwContentsClientCallbackHelper`, `WebMessageListenerHolder`).
 * - A **boundary** (load, reload, restore, reset) starts an epoch, and the engine posts a marker behind everything
 *   already queued. Until the marker runs, every callback was posted before the boundary: nothing is heard.
 * - The first app-initiated navigation that starts after the marker is **ours**: loadUrl, reload and restoreState start
 *   theirs synchronously, so its start is queued behind the marker. If none starts (WebView folds a load of the URL
 *   already loading into that one), the first app-initiated navigation to commit is ours. One that ends without a
 *   commit then is an earlier load this one replaced (WebView reports the aborted load right after the marker), never
 *   ours.
 * - Nothing a document does is heard until ours commits. After that, every commit in the epoch is the page's own (a
 *   link it follows, a history navigation) and is heard: those documents all began after the boundary.
 * - Page messages also carry the epoch's **token** ([bridgeScript]), which only documents that began loading after the
 *   boundary have. A same-document "ours" (a load that only changed the fragment) keeps its document, so the token that
 *   document signs with stays good with it, however many fragment-only loads follow. A document the page navigates to
 *   itself began after the boundary, and signs with the epoch's token.
 *
 * Not every callback is posted, though. WebView calls some from the browser's own task, at once (measured on WebView
 * 156): console output, permission and geolocation prompts, file choosers, new windows and page-started navigations.
 * One of those may come before the posted completion of our commit runs, so the posted order would wrongly take it for
 * an earlier document's. It isn't one: called at once, it comes from the browser's current document. So a second
 * listener, on a direct executor, tells the gate at once when our navigation commits in the browser
 * ([liveNavigationStarted], [liveNavigationCompleted]), and those callbacks are heard from then on
 * ([acceptsSynchronousCallbacks]): before it, the current document is still an earlier one. Posted callbacks (JS dialogs,
 * downloads, titles, messages) keep the posted order, since an earlier document may have posted them just before.
 */
internal class DocumentGate {
    /** What a completed main-frame navigation means for the engine. */
    sealed interface Completion {
        /** Nothing to hear: from before the boundary, or the page's own before the boundary's load committed. */
        data object Ignore : Completion

        /** The boundary's own navigation committed; [sameDocument] if it kept the document (a fragment). */
        data class Ours(val sameDocument: Boolean) : Completion

        /** The boundary's own navigation ended without a commit: a download, a 204, a cancelled load. */
        data object OursFailed : Completion

        /** A later commit in the same epoch: the page navigated, or went through its history. */
        data class Later(val sameDocument: Boolean) : Completion
    }

    /** Counts boundaries. */
    var epoch: Int = 0
        private set

    private var draining = false
    private var ours: Any? = null
    private var committed = false
    private var token: String? = null

    /** The token the document shown at the last boundary signs with. */
    private var previousToken: String? = null

    /** The token of a document a fragment-only "ours" kept, which still signs with it; null when the document is new. */
    private var carriedToken: String? = null

    /** Our navigation, and whether it committed, as WebView reports them at once rather than posted. */
    private var liveOurs: Any? = null
    private var liveCommitted = false

    /** Whether ours, committed in the browser, kept the document: a load that only changed the fragment. */
    private var liveKept = false

    /** The main-frame navigation loading a new document now, as WebView reports it at once, and who started it. */
    private var loading: Any? = null
    private var loadingByPage = false

    /**
     * Whether the main-frame navigation loading a new document now is one the app started (load, reload, restore, back,
     * forward), whatever the epoch. WebView offers its server redirects to `shouldOverrideUrlLoading` too, but no
     * navigation the app starts is the client's to decide: they are followed, to the web only.
     */
    val appNavigationLoading: Boolean get() = loading != null && !loadingByPage

    /** Whether the current epoch's marker has run: callbacks from now on were posted after the boundary. */
    val afterMarker: Boolean get() = !draining

    /**
     * Whether this epoch's own navigation has committed, as the browser or the posted order reports it: the document
     * shown is ours from then on, or one the page went to after it. Until then (ours still loads, or failed) it is the
     * one shown at the boundary, so a reload or a step through history would be that document's, which the runtime may
     * not hear.
     */
    val oursCommitted: Boolean get() = liveCommitted || committed

    /** Whether what documents do (titles, errors, console, paints, messages) is heard now. */
    val acceptsDocumentEvents: Boolean get() = !draining && committed

    /**
     * Whether a callback WebView makes at once, not posted, speaks for a document the runtime may hear: ours has committed
     * in the browser, so the browser's current document is ours or one the page went to after it. Whatever the posted
     * order hears, this hears too.
     */
    val acceptsSynchronousCallbacks: Boolean get() = liveCommitted || acceptsDocumentEvents

    /** A boundary, whose shim signs with [newToken]. Returns the epoch whose marker the engine must post now. */
    fun boundary(newToken: String): Int {
        epoch++
        // The document shown now signs with the token it began with. Once ours committed: one a fragment-only load
        // carried, or the last epoch's. If ours never did (it failed, or still loads), the document is the one shown at
        // the last boundary, still signing with what it signed with then: no document has the last epoch's token.
        previousToken = when {
            committed -> carriedToken ?: token
            liveCommitted -> if (liveKept) previousToken else token
            else -> previousToken
        }
        draining = true
        ours = null
        committed = false
        liveOurs = null
        liveCommitted = false
        liveKept = false
        carriedToken = null
        token = newToken
        return epoch
    }

    /** The marker posted for [markerEpoch] ran. One of an earlier epoch changes nothing. */
    fun markerRan(markerEpoch: Int) {
        if (markerEpoch == epoch) draining = false
    }

    /**
     * A main-frame navigation started, as WebView reports it at once. Nothing is queued in front of a callback made at
     * once, so no marker is needed: the first app-initiated navigation to start after the boundary is ours, the one
     * loadUrl, reload or restoreState started inside the call that made the boundary. One that loads a new document
     * ([sameDocument] false) is the one [appNavigationLoading] is about until it completes: a frame loads one at a time.
     */
    fun liveNavigationStarted(navigation: Any, byPage: Boolean, sameDocument: Boolean = false) {
        if (!sameDocument) {
            loading = navigation
            loadingByPage = byPage
        }
        if (liveCommitted || liveOurs != null || byPage) return
        liveOurs = navigation
    }

    /**
     * A main-frame navigation completed, as WebView reports it at once. Ours committing opens [acceptsSynchronousCallbacks];
     * so does the first app-initiated commit when none started (a load folded into one already under way). An earlier
     * document's own navigation, a load ours replaced, or ours ending without a commit never does. [sameDocument]: it
     * kept the document.
     */
    fun liveNavigationCompleted(navigation: Any, byPage: Boolean, didCommit: Boolean, sameDocument: Boolean = false) {
        if (navigation === loading) loading = null
        if (liveCommitted || !didCommit) return
        if (navigation === liveOurs || (liveOurs == null && !byPage)) {
            liveCommitted = true
            liveKept = sameDocument
        }
    }

    /** A main-frame navigation started. True if it is ours. */
    fun navigationStarted(navigation: Any, byPage: Boolean): Boolean {
        if (draining || committed || ours != null || byPage) return false
        ours = navigation
        return true
    }

    /** A main-frame navigation completed, committed or not. */
    fun navigationCompleted(navigation: Any, byPage: Boolean, didCommit: Boolean, sameDocument: Boolean): Completion {
        if (draining) return Completion.Ignore
        if (committed) {
            if (!didCommit) return Completion.Ignore
            // A new document the page navigated to: it began after the boundary, and signs with this epoch's token.
            if (!sameDocument) carriedToken = null
            return Completion.Later(sameDocument)
        }
        val isOurs = navigation === ours || (ours == null && !byPage && didCommit)
        if (!isOurs) return Completion.Ignore
        ours = navigation
        if (!didCommit) return Completion.OursFailed
        committed = true
        if (sameDocument) carriedToken = previousToken
        return Completion.Ours(sameDocument)
    }

    /** Whether a page message signed with [messageToken] comes from a document the runtime may hear. */
    fun acceptsMessage(messageToken: String): Boolean =
        acceptsDocumentEvents && (messageToken == token || messageToken == carriedToken)
}
