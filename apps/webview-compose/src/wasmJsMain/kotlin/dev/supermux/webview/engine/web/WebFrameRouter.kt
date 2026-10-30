package dev.supermux.webview.engine.web

import org.w3c.dom.HTMLElement

/** What [WebFrameRouter] hands its messages to: an engine. */
internal interface FrameRoutee {
    /** Whether [source] (a message's `event.source`) is the window of this engine's current iframe. */
    fun owns(source: JsAny?): Boolean

    /** A handshake from this engine's current iframe. [origin] is the browser's `event.origin` for it. */
    fun onHandshake(origin: String, secret: String, text: String, port: JsAny)

    /** Keyboard focus may have moved: check whether it is in this engine's page now. */
    fun checkFocus()
}

/**
 * The page's one `message` listener for handshakes, and its focus and input listeners, shared by every engine (contract
 * **Messages**).
 * - A handshake goes to the engine whose current iframe's window sent it. The window identifies the iframe, the iframe
 *   identifies the main frame, so a subframe's messages, which come from other windows, never get in.
 * - A handshake from any other window is left alone: no engine uses its port. The listener is the page's, shared with
 *   other listeners that may be that port's recipient, so closing it would break them.
 * - It notes the user's own input in the app's window ([ownInputAt], [tabAt]), and the app's element that last lost
 *   focus, so an engine can tell a focus the user gave its page from one the page took ([giveFocusBack]).
 */
internal object WebFrameRouter {
    private val routees = ArrayList<FrameRoutee>()
    private var listening = false

    /** When the app's own window last saw a trusted key, pointer or touch press, on the page's clock (ms). */
    var ownInputAt: Double = Double.NEGATIVE_INFINITY
        private set

    /** When the app's own window last saw a trusted Tab key press, which may move focus into a frame. */
    var tabAt: Double = Double.NEGATIVE_INFINITY
        private set

    private var left: HTMLElement? = null
    private var leftAt = Double.NEGATIVE_INFINITY

    fun register(routee: FrameRoutee) {
        if (!listening) {
            listening = true
            listenForHandshakes(::route)
            listenForOwnInput(::ownInput)
            listenForFocusChanges(::focusMoved, ::focusLeft)
        }
        routees += routee
    }

    private fun ownInput(tab: Boolean) {
        val now = nowMillis()
        ownInputAt = now
        if (tab) tabAt = now
    }

    private fun focusLeft(element: HTMLElement) {
        left = element
        leftAt = nowMillis()
    }

    /**
     * Takes the keyboard back from [iframe], whose page took it uninvited: to the app's element it left just now, or
     * else out of the frame, to the app's document.
     */
    fun giveFocusBack(iframe: HTMLElement) {
        val element = left
        if (element != null && nowMillis() - leftAt < FOCUS_LEFT_MILLIS && connected(element)) focusElement(element) else blurElement(iframe)
    }

    /** How recently the app's element must have lost focus to be the one the page took it from. */
    private const val FOCUS_LEFT_MILLIS = 500.0

    fun unregister(routee: FrameRoutee) {
        routees -= routee
    }

    private fun route(handshake: JsAny) {
        val port = handshakePort(handshake)
        val source = handshakeSource(handshake)
        val routee = routees.firstOrNull { it.owns(source) } ?: return
        routee.onHandshake(
            handshakeText(handshake, "origin"),
            handshakeText(handshake, "secret"),
            handshakeText(handshake, "text"),
            port,
        )
    }

    private fun focusMoved() {
        routees.toList().forEach { it.checkFocus() }
    }
}
