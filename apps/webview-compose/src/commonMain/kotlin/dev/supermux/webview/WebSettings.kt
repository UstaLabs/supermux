package dev.supermux.webview

enum class DarkMode { Auto, Light, Dark }

/** Where cookies and storage live. */
sealed interface WebProfile {
    data object Default : WebProfile
    data class Named(val id: String) : WebProfile
    /** Nothing outlives the engine lease: no cookies, no storage. */
    data object Ephemeral : WebProfile
}

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
    /** Origins whose bridge messages are accepted, besides the content's own local origin. */
    val bridgeOrigins: Set<String> = emptySet(),
) {
    companion object {
        val Default: WebSettings = WebSettings()
    }
}

internal fun WebSettings.profileFor(content: WebContent): WebProfile =
    profile ?: if (content is WebContent.Html) WebProfile.Ephemeral else WebProfile.Default
