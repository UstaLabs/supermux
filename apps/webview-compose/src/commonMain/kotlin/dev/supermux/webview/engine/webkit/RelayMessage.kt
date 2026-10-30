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
     * load.
     */
    data class Paint(override val document: String) : RelayMessage

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
                rest == "focus" -> Focus(document, focused = true)
                rest == "blur" -> Focus(document, focused = false)
                rest == "title" -> Title(document)
                rest == "url" -> Url(document)
                rest.startsWith("msg ") -> Page(document, rest.substring(4))
                else -> null
            }
        }
    }
}
