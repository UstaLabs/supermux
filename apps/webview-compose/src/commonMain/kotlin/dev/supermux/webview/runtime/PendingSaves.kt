package dev.supermux.webview.runtime

import dev.supermux.webview.WebViewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * Pages being saved now: a view's engine is going (the view left, scrolled away or was evicted), and the page's state
 * is on its way into its [WebViewState] and the page memory. A new view of the same state, or of the same page, waits
 * for them briefly before its first document, so it starts from what they save. Main thread only.
 */
internal class PendingSaves {
    /** One save, of [state]'s view showing [page]. */
    class Save(val state: WebViewState, val page: PageKey) {
        val done: CompletableDeferred<Unit> = CompletableDeferred()
    }

    private val saves = ArrayList<Save>()

    fun start(state: WebViewState, page: PageKey): Save = Save(state, page).also { saves += it }

    fun finish(save: Save) {
        saves.remove(save)
        save.done.complete(Unit)
    }

    /** The saves that concern a view of [state] showing [page]: those of that state, or of that page. */
    fun concerning(state: WebViewState, page: PageKey): List<Job> =
        saves.filter { it.state === state || it.page == page }.map { it.done }
}
