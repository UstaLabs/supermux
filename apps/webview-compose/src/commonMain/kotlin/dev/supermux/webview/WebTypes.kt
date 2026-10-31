package dev.supermux.webview

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

public sealed interface LoadingState {
    public data object Idle : LoadingState
    /** [progress] in 0..1. */
    public data class Loading(val progress: Float) : LoadingState
}

/** Where a logical web view is in its life (spec §4). */
public sealed interface WebViewPhase {
    /** No engine. The last snapshot, or the placeholder, is shown. */
    public data object Cold : WebViewPhase
    /** An engine is leased and loading; the snapshot still covers it. */
    public data object Activating : WebViewPhase
    /** The engine has painted and is on screen. */
    public data object Live : WebViewPhase
    /**
     * The renderer died, or the engine broke. A view on screen goes live again by itself a second later, at most once a
     * minute; one off screen waits until it is shown. `navigator.reload()` still brings it back at any time.
     */
    public data object Crashed : WebViewPhase
    public data class Unavailable(val reason: UnavailableReason, val detail: String? = null) : WebViewPhase
}

public enum class UnavailableReason {
    NotImplemented,
    NoWebViewProvider,
    NoWebView2Runtime,
    NoWpe,
    NativeLibraryFailed,
    UnsupportedContent,

    /** The engine factory failed to create an engine; the detail says why. */
    CreationFailed,
}

public sealed interface WebError {
    public val url: String?

    public data class Network(override val url: String?, val description: String) : WebError
    public data class Certificate(override val url: String?, val description: String) : WebError
    public data class Http(override val url: String?, val status: Int) : WebError
    public data class BlockedByPolicy(override val url: String?) : WebError
    public data class UnsupportedOnPlatform(override val url: String?) : WebError
}

public enum class ConsoleLevel { Debug, Log, Info, Warn, Error }

public data class ConsoleMessage(
    val level: ConsoleLevel,
    val message: String,
    val source: String? = null,
    val line: Int? = null,
    val uncaught: Boolean = false,
)

/** When a logical web view leases a live engine (spec §4). */
@Immutable
public sealed interface Activation {
    /**
     * Live whenever it is visible, even while it moves. For panes only: in a scrolling list it would activate
     * mid-fling, so use [WhenVisible] there.
     */
    public data object Eager : Activation

    /** Live once visible and still for [settleMillis]. For embeds in scrolling lists, and the WebView's default. */
    public data class WhenVisible(val settleMillis: Long = 150) : Activation {
        init {
            require(settleMillis >= 0) { "settleMillis must be >= 0" }
        }
    }

    /** A snapshot until tapped. For very long lists. */
    public data object OnInteraction : Activation
}

@Immutable
public sealed interface WebSizing {
    /** Takes the size its modifier gives it. */
    public data object Fill : WebSizing

    /**
     * Full width. The height follows the page's content height, capped at [max]; [initial] is used until the
     * page reports.
     */
    public data class WrapContentHeight(val max: Dp, val initial: Dp = 160.dp) : WebSizing {
        init {
            require(max > 0.dp && initial >= 0.dp) { "WrapContentHeight needs max > 0 and initial >= 0" }
        }
    }
}
