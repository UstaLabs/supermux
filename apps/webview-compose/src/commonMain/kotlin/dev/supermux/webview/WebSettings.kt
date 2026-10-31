package dev.supermux.webview

import androidx.compose.runtime.Immutable

public enum class DarkMode { Auto, Light, Dark }

/** Where cookies and storage live. */
@Immutable
public sealed interface WebProfile {
    public data object Default : WebProfile

    /** A named, persistent profile. [id] is 1–64 of `A-Z a-z 0-9 _ -`, which every engine accepts as a store name. */
    public data class Named(val id: String) : WebProfile {
        init {
            require(ID.matches(id)) { "WebProfile.Named id must be 1-64 of [A-Za-z0-9_-]: '$id'" }
        }

        private companion object {
            val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        }
    }

    /**
     * Nothing outlives the engine's page: no cookies, no storage once its engine is reset for another. A page a view
     * leaves live, parked, and the next view of it adopts, is still that page, and keeps its storage across the
     * adoption.
     */
    public data object Ephemeral : WebProfile
}

/**
 * How a [WebView] shows its page. Build it with named arguments, `WebSettings(bridgeOrigins = setOf(…))`, and change
 * one with [copy]; compare with `==`.
 *
 * A plain class, not a data class, so it can grow without breaking code built against it: a new setting comes as a new
 * parameter at the end of the constructor and of [copy], each with a default, and the signatures before it stay, hidden.
 * There are no `componentN` functions: positions mean nothing.
 */
@Immutable
public class WebSettings(
    public val javaScriptEnabled: Boolean = true,
    /** Exposes the page to the platform inspector (Safari, chrome://inspect, Edge DevTools). */
    public val inspectable: Boolean = false,
    public val darkMode: DarkMode = DarkMode.Auto,
    public val transparentBackground: Boolean = false,
    public val userAgentSuffix: String? = null,
    /** Null means the content's default: [WebProfile.Ephemeral] for [WebContent.Html], [WebProfile.Default] otherwise. */
    public val profile: WebProfile? = null,
    public val mediaPlaybackRequiresGesture: Boolean = true,
    /**
     * Origins whose documents get the bridge, besides the content's own local origin. Each is an origin as browsers
     * write it (`self.origin`): `scheme://host[:port]`, lowercase, without the scheme's default port, with no path and
     * no trailing slash, such as `https://app.example.com`. The bridge compares them exactly, so anything else would
     * never match: it is refused.
     */
    public val bridgeOrigins: Set<String> = emptySet(),
) {
    init {
        for (origin in bridgeOrigins) {
            require(isOrigin(origin)) {
                val canonical = originOf(origin).takeIf { it != "null" }
                "WebSettings.bridgeOrigins takes origins as browsers write them, scheme://host[:port] with no path, " +
                    "trailing slash or default port, lowercase: '$origin' is not one" +
                    (canonical?.let { ". Did you mean '$it'?" } ?: ".")
            }
        }
    }

    /** These settings, with the ones named changed. */
    public fun copy(
        javaScriptEnabled: Boolean = this.javaScriptEnabled,
        inspectable: Boolean = this.inspectable,
        darkMode: DarkMode = this.darkMode,
        transparentBackground: Boolean = this.transparentBackground,
        userAgentSuffix: String? = this.userAgentSuffix,
        profile: WebProfile? = this.profile,
        mediaPlaybackRequiresGesture: Boolean = this.mediaPlaybackRequiresGesture,
        bridgeOrigins: Set<String> = this.bridgeOrigins,
    ): WebSettings = WebSettings(
        javaScriptEnabled = javaScriptEnabled,
        inspectable = inspectable,
        darkMode = darkMode,
        transparentBackground = transparentBackground,
        userAgentSuffix = userAgentSuffix,
        profile = profile,
        mediaPlaybackRequiresGesture = mediaPlaybackRequiresGesture,
        bridgeOrigins = bridgeOrigins,
    )

    override fun equals(other: Any?): Boolean = this === other || other is WebSettings &&
        javaScriptEnabled == other.javaScriptEnabled &&
        inspectable == other.inspectable &&
        darkMode == other.darkMode &&
        transparentBackground == other.transparentBackground &&
        userAgentSuffix == other.userAgentSuffix &&
        profile == other.profile &&
        mediaPlaybackRequiresGesture == other.mediaPlaybackRequiresGesture &&
        bridgeOrigins == other.bridgeOrigins

    override fun hashCode(): Int {
        var result = javaScriptEnabled.hashCode()
        result = 31 * result + inspectable.hashCode()
        result = 31 * result + darkMode.hashCode()
        result = 31 * result + transparentBackground.hashCode()
        result = 31 * result + (userAgentSuffix?.hashCode() ?: 0)
        result = 31 * result + (profile?.hashCode() ?: 0)
        result = 31 * result + mediaPlaybackRequiresGesture.hashCode()
        result = 31 * result + bridgeOrigins.hashCode()
        return result
    }

    override fun toString(): String =
        "WebSettings(javaScriptEnabled=$javaScriptEnabled, inspectable=$inspectable, darkMode=$darkMode, " +
            "transparentBackground=$transparentBackground, userAgentSuffix=$userAgentSuffix, profile=$profile, " +
            "mediaPlaybackRequiresGesture=$mediaPlaybackRequiresGesture, bridgeOrigins=$bridgeOrigins)"

    public companion object {
        public val Default: WebSettings = WebSettings()
    }
}

/**
 * Whether [text] is an origin exactly as browsers serialize one: what [originOf] gives back unchanged, with a host, and
 * a port only if it is a number.
 */
private fun isOrigin(text: String): Boolean {
    if (text == "null" || originOf(text) != text) return false
    val host = hostOf(text) ?: return false
    val port = text.substringAfter("://").removePrefix(host)
    return port.isEmpty() || PORT.matches(port)
}

private val PORT = Regex("^:[0-9]{1,5}$")

internal fun WebSettings.profileFor(content: WebContent): WebProfile =
    profile ?: if (content is WebContent.Html) WebProfile.Ephemeral else WebProfile.Default
