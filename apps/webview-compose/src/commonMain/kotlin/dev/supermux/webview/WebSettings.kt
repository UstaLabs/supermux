package dev.supermux.webview

import androidx.compose.runtime.Immutable

enum class DarkMode { Auto, Light, Dark }

/** Where cookies and storage live. */
@Immutable
sealed interface WebProfile {
    data object Default : WebProfile

    /** A named, persistent profile. [id] is 1–64 of `A-Z a-z 0-9 _ -`, which every engine accepts as a store name. */
    data class Named(val id: String) : WebProfile {
        init {
            require(ID.matches(id)) { "WebProfile.Named id must be 1-64 of [A-Za-z0-9_-]: '$id'" }
        }

        private companion object {
            val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        }
    }

    /** Nothing outlives the engine lease: no cookies, no storage. */
    data object Ephemeral : WebProfile
}

@Immutable
data class WebSettings(
    val javaScriptEnabled: Boolean = true,
    /** Exposes the page to the platform inspector (Safari, chrome://inspect, Edge DevTools). */
    val inspectable: Boolean = false,
    val darkMode: DarkMode = DarkMode.Auto,
    val transparentBackground: Boolean = false,
    val userAgentSuffix: String? = null,
    /** Null means the content's default: [WebProfile.Ephemeral] for [WebContent.Html], [WebProfile.Default] otherwise. */
    val profile: WebProfile? = null,
    val mediaPlaybackRequiresGesture: Boolean = true,
    /**
     * Origins whose documents get the bridge, besides the content's own local origin. Each is an origin as browsers
     * write it (`self.origin`): `scheme://host[:port]`, lowercase, without the scheme's default port, with no path and
     * no trailing slash, such as `https://app.example.com`. The bridge compares them exactly, so anything else would
     * never match: it is refused.
     */
    val bridgeOrigins: Set<String> = emptySet(),
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

    companion object {
        val Default: WebSettings = WebSettings()
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
