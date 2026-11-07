package dev.supermux.webview.engine.android

/**
 * The page's requests the engine asked its client about and hasn't answered yet: JS dialogs, file choosers,
 * permission prompts. Each has its safe default (see [dev.supermux.webview.engine.WebEngine.client]), which
 * [answerAll] gives when the document goes, the lease ends, or the engine resets or dies, so WebView never waits for an
 * answer that won't come, and a late answer reaches nothing. A request closed without the client's answer also stops
 * the asking ([asking]): the app's dialog or prompt for it goes too. Answers that throw are swallowed: nothing an
 * engine callback throws may reach the app. Main thread only.
 */
internal class PendingRequests {
    private class Request(val safeDefault: () -> Unit) {
        var stopAsking: (() -> Unit)? = null
    }

    private val open = LinkedHashMap<Any, Request>()

    /** How many requests are open. */
    val size: Int get() = open.size

    /** Opens [key], which [answerAll] answers with [safeDefault] unless [answer] or [withdraw] closes it first. */
    fun open(key: Any, safeDefault: () -> Unit) {
        open[key] = Request(safeDefault)
    }

    /** While [key] is open, [stop] ends the asking (the client's coroutine): run if it closes without an answer. */
    fun asking(key: Any, stop: () -> Unit) {
        open[key]?.stopAsking = stop
    }

    /** Answers [key] with [answer] if it is still open. False, and [answer] doesn't run, if it isn't. */
    fun answer(key: Any, answer: () -> Unit): Boolean {
        if (open.remove(key) == null) return false
        runCatching(answer)
        return true
    }

    /** The page withdrew [key] (WebView's `onPermissionRequestCanceled`): it wants no answer any more. */
    fun withdraw(key: Any) {
        open.remove(key)?.stopAsking?.let { runCatching(it) }
    }

    /** Answers every open request with its safe default, then stops the asking. */
    fun answerAll() {
        val requests = open.values.toList()
        open.clear()
        requests.forEach { request ->
            runCatching(request.safeDefault)
            request.stopAsking?.let { runCatching(it) }
        }
    }
}
