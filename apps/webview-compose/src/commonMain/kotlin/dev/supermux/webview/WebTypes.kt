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

/**
 * Why the main frame failed to load ([WebViewState.error]). Plain classes, not data classes, so they can grow without
 * breaking code built against them: a new property comes as a new constructor parameter at the end, with a default, and
 * the signature before it stays, hidden. Compare them with `==`.
 */
public sealed interface WebError {
    public val url: String?

    /** The network failed: no connection, a name that doesn't resolve, a reset. */
    public class Network(override val url: String?, public val description: String) : WebError {
        override fun equals(other: Any?): Boolean =
            this === other || other is Network && url == other.url && description == other.description

        override fun hashCode(): Int = 31 * (url?.hashCode() ?: 0) + description.hashCode()

        override fun toString(): String = "Network(url=$url, description=$description)"
    }

    /** The server's certificate was refused. Engines never bypass it. */
    public class Certificate(override val url: String?, public val description: String) : WebError {
        override fun equals(other: Any?): Boolean =
            this === other || other is Certificate && url == other.url && description == other.description

        override fun hashCode(): Int = 31 * (url?.hashCode() ?: 0) + description.hashCode()

        override fun toString(): String = "Certificate(url=$url, description=$description)"
    }

    /** The server answered with an error [status]. */
    public class Http(override val url: String?, public val status: Int) : WebError {
        override fun equals(other: Any?): Boolean =
            this === other || other is Http && url == other.url && status == other.status

        override fun hashCode(): Int = 31 * (url?.hashCode() ?: 0) + status

        override fun toString(): String = "Http(url=$url, status=$status)"
    }

    /** The navigation policy blocked the load. */
    public class BlockedByPolicy(override val url: String?) : WebError {
        override fun equals(other: Any?): Boolean = this === other || other is BlockedByPolicy && url == other.url

        override fun hashCode(): Int = url?.hashCode() ?: 0

        override fun toString(): String = "BlockedByPolicy(url=$url)"
    }

    /** This platform's engine can't show it. */
    public class UnsupportedOnPlatform(override val url: String?) : WebError {
        override fun equals(other: Any?): Boolean = this === other || other is UnsupportedOnPlatform && url == other.url

        override fun hashCode(): Int = url?.hashCode() ?: 0

        override fun toString(): String = "UnsupportedOnPlatform(url=$url)"
    }
}

public enum class ConsoleLevel { Debug, Log, Info, Warn, Error }

/**
 * A line of the page's console output, or an uncaught error ([uncaught]), from [WebViewState.console]. A plain class, not
 * a data class, so it can grow as the requests can; compare with `==`.
 */
public class ConsoleMessage(
    public val level: ConsoleLevel,
    public val message: String,
    public val source: String? = null,
    public val line: Int? = null,
    public val uncaught: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = this === other || other is ConsoleMessage &&
        level == other.level && message == other.message && source == other.source && line == other.line &&
        uncaught == other.uncaught

    override fun hashCode(): Int {
        var result = level.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (source?.hashCode() ?: 0)
        result = 31 * result + (line ?: 0)
        result = 31 * result + uncaught.hashCode()
        return result
    }

    override fun toString(): String =
        "ConsoleMessage(level=$level, message=$message, source=$source, line=$line, uncaught=$uncaught)"
}

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
