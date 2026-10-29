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

    /** Whether the current epoch's marker has run: callbacks from now on were posted after the boundary. */
    val afterMarker: Boolean get() = !draining

    /** Whether what documents do (titles, errors, console, paints, messages) is heard now. */
    val acceptsDocumentEvents: Boolean get() = !draining && committed

    /** A boundary, whose shim signs with [newToken]. Returns the epoch whose marker the engine must post now. */
    fun boundary(newToken: String): Int {
        epoch++
        draining = true
        ours = null
        committed = false
        // The document shown now signs with the token it began with: one a fragment-only load carried, or the last epoch's.
        previousToken = carriedToken ?: token
        carriedToken = null
        token = newToken
        return epoch
    }

    /** The marker posted for [markerEpoch] ran. One of an earlier epoch changes nothing. */
    fun markerRan(markerEpoch: Int) {
        if (markerEpoch == epoch) draining = false
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
