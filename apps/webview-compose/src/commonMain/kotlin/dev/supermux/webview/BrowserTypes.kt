package dev.supermux.webview

/**
 * A find-in-page answer. [count] and [current] are null where the engine only says found or not (Apple's WebKit answers
 * found / not found); [current] is 1-based.
 */
public class FindResult(
    public val found: Boolean,
    public val count: Int? = null,
    public val current: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is FindResult && found == other.found && count == other.count && current == other.current

    override fun hashCode(): Int {
        var result = found.hashCode()
        result = 31 * result + (count ?: -1)
        result = 31 * result + (current ?: -1)
        return result
    }

    override fun toString(): String = "FindResult(found=$found, count=$count, current=$current)"
}

/**
 * A cookie for the view's profile, set before a page needs it: #105's preview token (`cmux_preview`, from the broker's
 * `POST /devices/preview-token`). Host-only when [domain] is null: it belongs to [url]'s host. [sameSite] is `Lax`,
 * `Strict` or `None`; [maxAgeSeconds] null makes a session cookie.
 */
public class WebCookie(
    public val name: String,
    public val value: String,
    public val url: String,
    public val path: String = "/",
    public val domain: String? = null,
    public val secure: Boolean = true,
    public val httpOnly: Boolean = true,
    public val sameSite: String = "Lax",
    public val maxAgeSeconds: Long? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || other is WebCookie &&
        name == other.name && value == other.value && url == other.url && path == other.path && domain == other.domain &&
        secure == other.secure && httpOnly == other.httpOnly && sameSite == other.sameSite && maxAgeSeconds == other.maxAgeSeconds

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + (domain?.hashCode() ?: 0)
        result = 31 * result + secure.hashCode()
        result = 31 * result + httpOnly.hashCode()
        result = 31 * result + sameSite.hashCode()
        result = 31 * result + (maxAgeSeconds?.hashCode() ?: 0)
        return result
    }

    /** The value stays out: a cookie's value is a credential (the preview token is one). */
    override fun toString(): String =
        "WebCookie(name=$name, url=$url, path=$path, domain=$domain, secure=$secure, httpOnly=$httpOnly, sameSite=$sameSite, " +
            "maxAgeSeconds=$maxAgeSeconds)"
}

/** What the engine did when asked for its inspector. May gain kinds: a `when` over it needs an `else`. */
public sealed interface InspectorResult {
    public data object Opened : InspectorResult

    /** The engine can't open it from the app; [hint] says where the user can (Safari's Develop menu, for Apple's WebKit). */
    public class Elsewhere(public val hint: String) : InspectorResult {
        override fun equals(other: Any?): Boolean = this === other || other is Elsewhere && hint == other.hint
        override fun hashCode(): Int = hint.hashCode()
        override fun toString(): String = "Elsewhere(hint=$hint)"
    }

    public data object Unsupported : InspectorResult
}
