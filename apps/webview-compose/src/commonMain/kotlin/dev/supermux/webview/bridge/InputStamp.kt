package dev.supermux.webview.bridge

import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.monotonicClock
import kotlin.concurrent.Volatile

/**
 * When the user last gave one view real input, and the rule that a page's "send" must come right after it.
 *
 * The host marks the time of every real pointer press or key delivered to a view ([mark]). A page's send is accepted
 * only within [windowMillis] of that input, and takes it ([consume]): one input, one send. Nothing the page does can
 * mark it: scripts, timers, promises, a script-made `click()` or `dispatchEvent`, and a page that is still loading make
 * no input. Each view has its own stamp, so input in one card never allows a send from another.
 *
 * Pure: the caller passes the time, from one monotonic clock ([inputClock]). The one time is volatile, so an engine may
 * mark it from its own UI thread while the bridge consumes it on main. A mark that lands between a consume's read and its
 * clear is lost, which only ever refuses a send.
 */
internal class InputStamp(val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
    @Volatile
    private var markedAt = NONE

    /** Whether input is waiting to be consumed, expired or not. */
    val isMarked: Boolean get() = markedAt != NONE

    /** Real user input was delivered at [nowMillis]. The newest input is the one that counts. */
    fun mark(nowMillis: Long) {
        markedAt = nowMillis
    }

    /**
     * Whether a send at [nowMillis] follows the last input closely enough, and takes that input: a second call with no
     * input between them is refused. An input that has expired is gone too. A clock that went backwards refuses.
     */
    fun consume(nowMillis: Long): Boolean {
        val at = markedAt
        markedAt = NONE
        if (at == NONE) return false
        val age = nowMillis - at
        return age in 0..windowMillis
    }

    /** Forgets the input: the user touched something else since (the web engine's rule), or the document is gone. */
    fun clear() {
        markedAt = NONE
    }

    companion object {
        /** The spec's window: "within 1 s of that input". */
        const val DEFAULT_WINDOW_MILLIS: Long = 1000

        private const val NONE = Long.MIN_VALUE
    }
}

/** The one clock that stamps and their consumers read, in milliseconds. */
internal val inputClock: () -> Long = monotonicClock()

/**
 * An engine that tells the host of the user's real input to its view. The host asks it, at each page call that
 * [WebBridge.handleWithUserInput] serves, whether the call follows input. An engine that doesn't implement it, or can't
 * tell, never allows one.
 */
internal interface UserInputSource {
    /** Takes the user's last input if it is recent enough: true at most once per input. Main thread. */
    fun consumeUserInput(): Boolean

    /**
     * Whether a page's click verdict ([WebBridge.handleAfterButtonClick]) is also re-checked against [consumeUserInput]:
     * the web, whose host keeps its own checks (the user's press reached this frame, nothing in the app since, and the
     * frame's transient activation) on top of the page's (spec, Click-to-send). Native engines take the verdict alone,
     * so a screen reader's activation, which delivers no input to the view, still counts (open decision 4, option c).
     */
    val rechecksClicks: Boolean get() = false
}

/** What a bridge asks of [engine] for [WebBridge.handleWithUserInput], or never. */
internal fun userInputOf(engine: WebEngine): () -> Boolean {
    val source = engine as? UserInputSource ?: return NO_USER_INPUT
    return source::consumeUserInput
}

private val NO_USER_INPUT: () -> Boolean = { false }

/** What a bridge asks of [engine] on top of a page's click verdict: its own input check if it re-checks clicks. */
internal fun clickRecheckOf(engine: Any): () -> Boolean {
    val source = engine as? UserInputSource
    return if (source != null && source.rechecksClicks) source::consumeUserInput else NO_RECHECK
}

private val NO_RECHECK: () -> Boolean = { true }
