package dev.supermux.webview

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

sealed interface LoadingState {
    data object Idle : LoadingState
    /** [progress] in 0..1. */
    data class Loading(val progress: Float) : LoadingState
}

/** Where a logical web view is in its life (spec §4). */
sealed interface WebViewPhase {
    /** No engine. The last snapshot, or the placeholder, is shown. */
    data object Cold : WebViewPhase
    /** An engine is leased and loading; the snapshot still covers it. */
    data object Activating : WebViewPhase
    /** The engine has painted and is on screen. */
    data object Live : WebViewPhase
    /** The renderer died. `navigator.reload()` brings it back. */
    data object Crashed : WebViewPhase
    data class Unavailable(val reason: UnavailableReason, val detail: String? = null) : WebViewPhase
}

enum class UnavailableReason {
    NotImplemented,
    NoWebViewProvider,
    NoWebView2Runtime,
    NoWpe,
    NativeLibraryFailed,
    UnsupportedContent,

    /** The engine factory failed to create an engine; the detail says why. */
    CreationFailed,
}

sealed interface WebError {
    val url: String?

    data class Network(override val url: String?, val description: String) : WebError
    data class Certificate(override val url: String?, val description: String) : WebError
    data class Http(override val url: String?, val status: Int) : WebError
    data class BlockedByPolicy(override val url: String?) : WebError
    data class UnsupportedOnPlatform(override val url: String?) : WebError
}

enum class ConsoleLevel { Debug, Log, Info, Warn, Error }

data class ConsoleMessage(
    val level: ConsoleLevel,
    val message: String,
    val source: String? = null,
    val line: Int? = null,
    val uncaught: Boolean = false,
)

/** When a logical web view leases a live engine (spec §4). */
@Immutable
sealed interface Activation {
    /** Live whenever it is visible. For panes. */
    data object Eager : Activation

    /** Live once visible and still for [settleMillis]. For embeds in scrolling lists. */
    data class WhenVisible(val settleMillis: Long = 150) : Activation {
        init {
            require(settleMillis >= 0) { "settleMillis must be >= 0" }
        }
    }

    /** A snapshot until tapped. For very long lists. */
    data object OnInteraction : Activation
}

@Immutable
sealed interface WebSizing {
    /** Takes the size its modifier gives it. */
    data object Fill : WebSizing

    /**
     * Full width. The height follows the page's content height, capped at [max]; [initial] is used until the
     * page reports.
     */
    data class WrapContentHeight(val max: Dp, val initial: Dp = 160.dp) : WebSizing {
        init {
            require(max > 0.dp && initial >= 0.dp) { "WrapContentHeight needs max > 0 and initial >= 0" }
        }
    }
}
