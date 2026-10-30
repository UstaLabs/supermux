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
 * - It notes the user's own input in the app's window ([ownInputAt], [tabAt]), and what had the keyboard before it last
 *   moved, so an engine can tell a focus the user gave its page from one the page took, and give a taken one back
 *   ([giveFocusBack]).
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

    /** The page's focused element as last seen, and the one before it changed: an app element, an iframe, or null. */
    private var focusedNow: HTMLElement? = null
    private var focusedBefore: HTMLElement? = null

    /** Where [giveFocusBack] last put the keyboard, and when: the engine of that iframe takes it as its own again. */
    private var givenBackTo: HTMLElement? = null
    private var givenBackAt = Double.NEGATIVE_INFINITY

    /**
     * Whether the app's own window has the keyboard, as its `focus` and `blur` events say. While it doesn't, its
     * `activeElement` can be stale: it names what had the keyboard in the app's document, not the frame that has it now.
     */
    private var windowFocused = false

    /** A give-back that waits for the app's window to hear it lost the keyboard: to [waitingTarget], from [waitingFrame]. */
    private var waiting = false
    private var waitingTarget: HTMLElement? = null
    private var waitingFrame: HTMLElement? = null
    private var waitingSince = Double.NEGATIVE_INFINITY

    fun register(routee: FrameRoutee) {
        if (!listening) {
            listening = true
            listenForHandshakes(::route)
            listenForOwnInput(::ownInput)
            listenForFocusChanges(::focusMoved, ::windowBlurred, ::windowFocusedAgain)
            focusedNow = focusedElement()
            windowFocused = windowHasOwnFocus()
        }
        routees += routee
    }

    private fun windowFocusedAgain() {
        windowFocused = true
    }

    /**
     * The app's window lost the keyboard, to a frame or to another app. A give-back that waited for this happens now,
     * when a `focus()` asks the browser for the keyboard back; unless another frame than the one refused has it, which
     * the user may have clicked into meanwhile.
     */
    private fun windowBlurred() {
        windowFocused = false
        if (!waiting) return
        waiting = false
        val took = focusedIframe()
        if (nowMillis() - waitingSince < WAIT_FOR_BLUR_MILLIS && (took == null || waitingFrame?.let { same(it, took) } == true)) {
            giveTo(waitingTarget)
        }
    }

    private fun ownInput(tab: Boolean) {
        val now = nowMillis()
        ownInputAt = now
        if (tab) tabAt = now
    }

    /**
     * Takes the keyboard back from [iframe], whose page took it uninvited, to what had it before: the app's element,
     * another page the user gave it to, or the app's document. What had it is the last thing seen with it other than
     * [iframe]: the app's `activeElement` may not show the move yet, or may already.
     *
     * The page can tell of its focus before the app's window hears it lost the keyboard (the two come by different
     * routes). A `focus()` then only moves the app's own active element, and the browser leaves the keyboard in the
     * frame (web-check's focus mode, with real typing: every key went to the page). So while the window still has the
     * keyboard, the give-back waits for its `blur`, and happens in it.
     */
    fun giveFocusBack(iframe: HTMLElement) {
        val target = (focusedNow?.takeIf { !same(it, iframe) } ?: focusedBefore)?.takeIf { !same(it, iframe) }
        if (windowFocused) {
            waiting = true
            waitingTarget = target
            waitingFrame = iframe
            waitingSince = nowMillis()
            return
        }
        giveTo(target)
    }

    /**
     * Gives the keyboard to [target], or to the app's document, through the engine's focus sink: a `focus()` on what the
     * app's document already names as active asks the browser for nothing, and the app's own field may have taken
     * itself back already, on its blur (Compose's text field does, with its backing textarea), while the frame still has
     * the keyboard. Moving to the sink first always asks.
     */
    private fun giveTo(target: HTMLElement?) {
        val frame = target != null && connected(target) && isIframe(target)
        // Another page the user gave the keyboard to takes it straight back: through the sink, the app's document would
        // have it, and the frame's own `focus()` after that isn't always acted on.
        if (!frame) focusSink()
        if (target != null && connected(target)) {
            givenBackTo = target
            givenBackAt = nowMillis()
            focusElement(target)
        }
    }

    /**
     * A page says the keyboard left it, maybe for another frame. The app's document says nothing when it moves between
     * frames, and shows where it went only a little later: every engine looks now, and again over the next
     * [LOOK_AGAIN_MILLIS], until it does.
     */
    fun lookAgainSoon() {
        focusMoved()
        for (delay in LOOK_AGAIN_MILLIS) afterMillis(delay) { focusMoved() }
    }

    /** When to look again after a page lost the keyboard (ms): the app's `activeElement` follows within a few frames. */
    private val LOOK_AGAIN_MILLIS = intArrayOf(16, 50, 150, 400)

    /** An engine took the keyboard into [iframe], the user's to give: it is what a later theft gives it back to. */
    fun focusedIn(iframe: HTMLElement) {
        val last = focusedNow
        if (last != null && same(last, iframe)) return
        focusedBefore = last
        focusedNow = iframe
    }

    /** Whether [iframe] has the keyboard because [giveFocusBack] just gave it back: its page had it, the user's. */
    fun gaveFocusBackTo(iframe: HTMLElement): Boolean =
        givenBackTo?.let { same(it, iframe) } == true && nowMillis() - givenBackAt < GIVE_BACK_MILLIS

    /** How long a focus given back counts as given back: it lands at once, or within a task. */
    private const val GIVE_BACK_MILLIS = 300.0

    /** How long a give-back waits for the app's window to hear it lost the keyboard: that comes within a few ms. */
    private const val WAIT_FOR_BLUR_MILLIS = 300.0

    fun unregister(routee: FrameRoutee) {
        routees -= routee
    }

    /** How many engines are registered: one each until released. For tests. */
    val registered: Int get() = routees.size

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
        val now = focusedElement()
        val last = focusedNow
        // While the app's window doesn't have the keyboard, only a frame named as its active element is news: anything
        // else there is what had the keyboard before, not what has it.
        val known = windowFocused || (now != null && isIframe(now))
        if (known && (if (now == null || last == null) now !== last else !same(now, last))) {
            focusedBefore = last
            focusedNow = now
        }
        routees.toList().forEach { it.checkFocus() }
    }
}
