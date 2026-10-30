package dev.supermux.webview.engine.webkit

/**
 * What the relay (see [WebKitScripts.relay]) tells the engine, one string per message:
 * `<document> <kind>[ <payload>]`, where `<document>` is the 16 lowercase hex digits of the document's token, which the
 * relay keeps in the isolated world and renews when the page comes back from the back-forward cache.
 */
internal sealed interface RelayMessage {
    val document: String

    /** A document started (or came back from the back-forward cache) under this token. */
    data class Start(override val document: String) : RelayMessage

    /**
     * The document is on screen, painted: two animation frames after it had content, its first contentful paint or its
     * load. Windows' script ([presentedAgoMillis]) also says how long ago, when it sent this, the paint it waited for was
     * presented: WebView2's capture can be a composition or two behind the page.
     */
    data class Paint(override val document: String, val presentedAgoMillis: Double? = null) : RelayMessage

    /** The window took ([focused]) or lost keyboard focus. */
    data class Focus(override val document: String, val focused: Boolean) : RelayMessage

    /**
     * The document's title changed. Only a hint: the engine reads the title from WebKit, so a page can make it look
     * again, never make it read something else.
     */
    data class Title(override val document: String) : RelayMessage

    /** The document's URL changed without a new document (`pushState`, a fragment). A hint, like [Title]. */
    data class Url(override val document: String) : RelayMessage

    /** A bridge message from the shim, as the shim sent it. */
    data class Page(override val document: String, val json: String) : RelayMessage

    /**
     * The user used the page, as the relay saw it in trusted events only, which no page script can make: a [gesture]
     * (a tap, a click or a key), or a scroll of the page under the user's own finger or wheel. Only a gesture can make
     * a navigation the user's. Reported by relays built to (`WebKitScripts.relay(input = true)`), and from subframes,
     * whose token isn't the page's.
     */
    data class Input(override val document: String, val gesture: Boolean) : RelayMessage

    companion object {
        const val TOKEN_LENGTH: Int = 16

        /** [body] as a relay message, or null when it isn't one: anything else is dropped unread. */
        fun parse(body: String): RelayMessage? {
            if (body.length < TOKEN_LENGTH + 2 || body[TOKEN_LENGTH] != ' ') return null
            val document = body.substring(0, TOKEN_LENGTH)
            if (!document.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val rest = body.substring(TOKEN_LENGTH + 1)
            return when {
                rest == "start" -> Start(document)
                rest == "paint" -> Paint(document)
                // An age it can't read is a paint all the same: the signal still means the document has painted.
                rest.startsWith("paint ") -> Paint(document, rest.substring(6).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 })
                rest == "focus" -> Focus(document, focused = true)
                rest == "blur" -> Focus(document, focused = false)
                rest == "title" -> Title(document)
                rest == "url" -> Url(document)
                rest == "gesture" -> Input(document, gesture = true)
                rest == "use" -> Input(document, gesture = false)
                rest.startsWith("msg ") -> Page(document, rest.substring(4))
                else -> null
            }
        }
    }
}
