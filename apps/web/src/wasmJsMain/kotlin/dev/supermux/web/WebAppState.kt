package dev.supermux.web

import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget

/**
 * Signals that flow from the browser INTO the Compose root, as state (a tap can arrive before
 * the root exists). [foreground] follows `document.visibilityState` — VISIBLE, not focused, the
 * same choice iOS makes, so a chat readable behind another window keeps suppressing pushes.
 */
object WebAppState {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _pendingPushSessionId = MutableStateFlow<String?>(null)
    val pendingPushSessionId: StateFlow<String?> = _pendingPushSessionId.asStateFlow()
    fun setPendingPushSessionId(id: String?) { _pendingPushSessionId.value = id }
    fun consumePendingPushSessionId() { _pendingPushSessionId.value = null }

    private val _pendingPairLink = MutableStateFlow<String?>(null)
    val pendingPairLink: StateFlow<String?> = _pendingPairLink.asStateFlow()
    fun setPendingPairLink(url: String?) { _pendingPairLink.value = url }
    fun consumePendingPairLink() { _pendingPairLink.value = null }

    private var installed = false

    /**
     * Install the DOM listeners once, from `main()`. Idempotent: a second call (a re-mount, a hot
     * reload) would otherwise stack a second visibility and message listener on the same targets.
     */
    fun install() {
        if (installed) return
        installed = true
        _foreground.value = !documentHidden()
        document.addEventListener("visibilitychange", { _foreground.value = !documentHidden() })
        // sw.js (plan 4) posts {type:"navigate", to:"/s/<id>"} on notification click. There may be
        // no service-worker container at all (an insecure origin, or a browser without one), which
        // is why this is a nullable lookup and not `navigator.serviceWorker`.
        serviceWorkerContainer()?.addEventListener("message", { ev ->
            val to = navigateTarget(ev)?.toString()
            if (to != null) setPendingPushSessionId(to.removePrefix("/s/"))
        })
        // REQUIRED, not belt-and-braces: a ServiceWorkerContainer's message queue starts DISABLED
        // and is only released by assigning `onmessage` or by calling `startMessages()`. With
        // `addEventListener` alone every notification-click message would sit in the queue
        // forever, and plan 4's push navigation would silently never arrive.
        startSwMessages()
    }
}

/**
 * `document.visibilityState` is not on kotlinx-browser 0.5.0's `Document`, so it is read through
 * JS directly rather than through a typed member that does not exist.
 */
private fun documentHidden(): Boolean = js("document.visibilityState === 'hidden'")

/** `navigator.serviceWorker` as a plain [EventTarget], or null where the browser has none. */
private fun serviceWorkerContainer(): EventTarget? = js("navigator.serviceWorker || null")

/** Release the container's queued messages — see [WebAppState.install]. */
private fun startSwMessages(): Unit = js("{ if (navigator.serviceWorker) navigator.serviceWorker.startMessages(); }")

/** The `/s/<id>` target of a service-worker navigate message, or null for anything else. */
@Suppress("UNUSED_PARAMETER")
private fun navigateTarget(ev: Event): JsString? =
    js("(ev && ev.data && ev.data.type === 'navigate' && typeof ev.data.to === 'string' && ev.data.to.startsWith('/s/')) ? ev.data.to : null")
