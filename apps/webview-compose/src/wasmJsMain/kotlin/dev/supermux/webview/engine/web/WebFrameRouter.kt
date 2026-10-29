package dev.supermux.webview.engine.web

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
 * The page's one `message` listener for handshakes, and its focus listener, shared by every engine (contract
 * **Messages**).
 * - A handshake goes to the engine whose current iframe's window sent it. The window identifies the iframe, the iframe
 *   identifies the main frame, so a subframe's messages, which come from other windows, never get in.
 * - A handshake from any other window is left alone: no engine uses its port. The listener is the page's, shared with
 *   other listeners that may be that port's recipient, so closing it would break them.
 */
internal object WebFrameRouter {
    private val routees = ArrayList<FrameRoutee>()
    private var listening = false

    fun register(routee: FrameRoutee) {
        if (!listening) {
            listening = true
            listenForHandshakes(::route)
            listenForFocusChanges(::focusMoved)
        }
        routees += routee
    }

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
