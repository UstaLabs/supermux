package dev.supermux.webview.runtime

import dev.supermux.webview.WebViewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * Pages being saved now: a view's engine is going (the view left, scrolled away or was evicted), and the page's state
 * is on its way into its [WebViewState] and the page memory. A new view of the same state, or of the same content,
 * waits for them briefly before its first document, so it starts from what they save. Main thread only.
 */
internal class PendingSaves {
    /** One save, of [state]'s page showing [contentKey]. */
    class Save(val state: WebViewState, val contentKey: String) {
        val done: CompletableDeferred<Unit> = CompletableDeferred()
    }

    private val saves = ArrayList<Save>()

    fun start(state: WebViewState, contentKey: String): Save = Save(state, contentKey).also { saves += it }

    fun finish(save: Save) {
        saves.remove(save)
        save.done.complete(Unit)
    }

    /** The saves that concern a view of [state] showing [contentKey]: those of that state, or of that content. */
    fun concerning(state: WebViewState, contentKey: String): List<Job> =
        saves.filter { it.state === state || it.contentKey == contentKey }.map { it.done }
}
