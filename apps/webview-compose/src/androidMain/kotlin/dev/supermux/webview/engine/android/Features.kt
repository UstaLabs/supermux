package dev.supermux.webview.engine.android

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.supermux.webview.WebProfile

/**
 * The WebView features the engine needs. Without one of them it can't keep the engine contract, so the factory reports
 * `Unavailable(NoWebViewProvider)` and the app falls back as it would with no WebView. Ask only after
 * [WebViewStartup] is done.
 */
internal object Features {
    private val REQUIRED = listOf(
        WebViewFeature.WEB_MESSAGE_LISTENER,
        WebViewFeature.DOCUMENT_START_SCRIPT,
        WebViewFeature.VISUAL_STATE_CALLBACK,
        WebViewFeature.NAVIGATION_LISTENER,
    )

    /** What [profile] needs that this WebView lacks: the required features, and profiles unless it is the default one. */
    fun missing(profile: WebProfile): List<String> {
        val needed = if (profile == WebProfile.Default) REQUIRED else REQUIRED + WebViewFeature.MULTI_PROFILE
        return needed.filterNot(WebViewFeature::isFeatureSupported)
    }

    /** The WebView package's version, for messages. */
    fun version(context: Context): String = WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "(none)"
}
