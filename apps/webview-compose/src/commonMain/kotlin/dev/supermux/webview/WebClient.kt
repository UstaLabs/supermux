package dev.supermux.webview

// The requests below are plain classes, not data classes, so they can grow without breaking code built against them: a
// new property comes as a new constructor parameter at the end, with a default, and the signature before it stays,
// hidden. Compare them with `==`; there are no `componentN` functions.

/** A navigation the page started, for [WebClient.onNavigation]. */
public class NavigationRequest(
    public val url: String,
    public val isMainFrame: Boolean,
    public val isUserGesture: Boolean,
) {
    override fun equals(other: Any?): Boolean = this === other || other is NavigationRequest &&
        url == other.url && isMainFrame == other.isMainFrame && isUserGesture == other.isUserGesture

    override fun hashCode(): Int = 31 * (31 * url.hashCode() + isMainFrame.hashCode()) + isUserGesture.hashCode()

    override fun toString(): String =
        "NavigationRequest(url=$url, isMainFrame=$isMainFrame, isUserGesture=$isUserGesture)"
}

public enum class NavigationDecision { Allow, Block, OpenExternally }

/**
 * What to do with a page's request for a new window (`window.open`, `target=_blank`).
 * - [OpenHere] loads the URL in this view, but only if [WebClient.onNavigation] would allow it as a main-frame
 *   navigation.
 * - [OpenExternally] hands it to the OS, subject to the same scheme rules.
 */
public enum class NewWindowDecision { OpenHere, OpenExternally, Block }

/** A dialog the page raised, for [WebClient.onDialog]. Plain classes, as the requests are. */
public sealed interface WebDialog {
    public val message: String

    /** The URL of the document that raised the dialog, so the app can say who is asking. */
    public val url: String?

    /** `alert()`. */
    public class Alert(override val message: String, override val url: String? = null) : WebDialog {
        override fun equals(other: Any?): Boolean =
            this === other || other is Alert && message == other.message && url == other.url

        override fun hashCode(): Int = 31 * message.hashCode() + (url?.hashCode() ?: 0)

        override fun toString(): String = "Alert(message=$message, url=$url)"
    }

    /** `confirm()`. */
    public class Confirm(override val message: String, override val url: String? = null) : WebDialog {
        override fun equals(other: Any?): Boolean =
            this === other || other is Confirm && message == other.message && url == other.url

        override fun hashCode(): Int = 31 * message.hashCode() + (url?.hashCode() ?: 0)

        override fun toString(): String = "Confirm(message=$message, url=$url)"
    }

    /** `prompt()`, with the text field's [defaultValue]. */
    public class Prompt(
        override val message: String,
        public val defaultValue: String,
        override val url: String? = null,
    ) : WebDialog {
        override fun equals(other: Any?): Boolean = this === other || other is Prompt &&
            message == other.message && defaultValue == other.defaultValue && url == other.url

        override fun hashCode(): Int = 31 * (31 * message.hashCode() + defaultValue.hashCode()) + (url?.hashCode() ?: 0)

        override fun toString(): String = "Prompt(message=$message, defaultValue=$defaultValue, url=$url)"
    }

    /** `beforeunload`: whether to leave the page. */
    public class BeforeUnload(override val message: String, override val url: String? = null) : WebDialog {
        override fun equals(other: Any?): Boolean =
            this === other || other is BeforeUnload && message == other.message && url == other.url

        override fun hashCode(): Int = 31 * message.hashCode() + (url?.hashCode() ?: 0)

        override fun toString(): String = "BeforeUnload(message=$message, url=$url)"
    }
}

public sealed interface DialogResult {
    /** Alert OK, Confirm/Prompt cancel, BeforeUnload "stay on the page". */
    public data object Dismissed : DialogResult
    /** Confirm OK, BeforeUnload "leave the page". */
    public data object Confirmed : DialogResult
    public data class Text(val value: String) : DialogResult
}

/** A file chooser the page opened (`<input type=file>`), for [WebClient.onFileChooser]. */
public class FileChooserRequest(public val acceptTypes: List<String>, public val allowMultiple: Boolean) {
    override fun equals(other: Any?): Boolean = this === other || other is FileChooserRequest &&
        acceptTypes == other.acceptTypes && allowMultiple == other.allowMultiple

    override fun hashCode(): Int = 31 * acceptTypes.hashCode() + allowMultiple.hashCode()

    override fun toString(): String = "FileChooserRequest(acceptTypes=$acceptTypes, allowMultiple=$allowMultiple)"
}

public enum class PermissionKind { Camera, Microphone, Geolocation, Notifications, ClipboardRead, Other }

/** Permissions the page of [origin] asks for, for [WebClient.onPermission]. */
public class PermissionRequest(public val origin: String, public val kinds: Set<PermissionKind>) {
    override fun equals(other: Any?): Boolean =
        this === other || other is PermissionRequest && origin == other.origin && kinds == other.kinds

    override fun hashCode(): Int = 31 * origin.hashCode() + kinds.hashCode()

    override fun toString(): String = "PermissionRequest(origin=$origin, kinds=$kinds)"
}

/** A download the page started, for [WebClient.onDownload]. */
public class DownloadRequest(
    public val url: String,
    public val suggestedFileName: String?,
    public val mimeType: String?,
    public val contentLength: Long?,
) {
    override fun equals(other: Any?): Boolean = this === other || other is DownloadRequest &&
        url == other.url && suggestedFileName == other.suggestedFileName && mimeType == other.mimeType &&
        contentLength == other.contentLength

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (suggestedFileName?.hashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + (contentLength?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "DownloadRequest(url=$url, suggestedFileName=$suggestedFileName, mimeType=$mimeType, contentLength=$contentLength)"
}

/** A new window the page asked for (`window.open`, `target=_blank`), for [WebClient.onNewWindow]. */
public class NewWindowRequest(public val url: String, public val isUserGesture: Boolean) {
    override fun equals(other: Any?): Boolean =
        this === other || other is NewWindowRequest && url == other.url && isUserGesture == other.isUserGesture

    override fun hashCode(): Int = 31 * url.hashCode() + isUserGesture.hashCode()

    override fun toString(): String = "NewWindowRequest(url=$url, isUserGesture=$isUserGesture)"
}

/**
 * Answers the page's requests. Every default is the safe answer.
 *
 * [onNavigation] and [onNewWindow] are plain functions, because Android and WKWebView's window creation need the
 * answer synchronously. They may run off the main thread (on desktop, on the engine's own UI thread), so keep them
 * fast, pure and thread-safe. The others may suspend (to show a Compose dialog, say), because every engine can answer
 * them later.
 */
public interface WebClient {
    /**
     * A navigation the page started. [content] is what the view is showing. May run off the main thread: keep it fast,
     * pure and thread-safe.
     */
    public fun onNavigation(request: NavigationRequest, content: WebContent): NavigationDecision =
        defaultNavigationDecision(request, content)

    /** Alert, Confirm and Prompt are dismissed. BeforeUnload leaves the page, so a page can't trap the user. */
    public suspend fun onDialog(dialog: WebDialog): DialogResult =
        if (dialog is WebDialog.BeforeUnload) DialogResult.Confirmed else DialogResult.Dismissed

    /** The chosen files as platform URI strings, or null to cancel. */
    public suspend fun onFileChooser(request: FileChooserRequest): List<String>? = null

    public suspend fun onPermission(request: PermissionRequest): Boolean = false

    public suspend fun onDownload(request: DownloadRequest): Boolean = false

    /** A request for a new window. May run off the main thread: keep it fast, pure and thread-safe. */
    public fun onNewWindow(request: NewWindowRequest): NewWindowDecision = NewWindowDecision.Block

    public companion object {
        public val Default: WebClient = object : WebClient {}
    }
}

/** Schemes the default policy hands to the OS, and only on a user gesture. Every other non-web scheme is blocked. */
internal val EXTERNAL_SCHEMES: Set<String> = setOf("mailto", "tel", "sms")

/**
 * The default navigation policy (spec §9). It parses URLs the way a browser does.
 * - Subframes are the document's business.
 * - Leaving the app (OpenExternally) needs a user gesture, and only http(s), mailto, tel and sms ever leave.
 * - `javascript:`, `data:`, `file:`, scheme-less URLs and unknown schemes are blocked.
 * - Local content (Html, Bundle) stays on its own origin, its own blob: URLs and about:blank/srcdoc.
 * - A network page may not navigate into a local origin, and local content never hops to another.
 */
public fun defaultNavigationDecision(request: NavigationRequest, content: WebContent): NavigationDecision {
    if (!request.isMainFrame) return NavigationDecision.Allow
    val url = normalizeUrl(request.url)
    val leave = if (request.isUserGesture) NavigationDecision.OpenExternally else NavigationDecision.Block
    val local = content.localOrigin
    return when (schemeOf(url)) {
        "http", "https" -> {
            val host = hostOf(url)
            when {
                host == null -> NavigationDecision.Block
                isReservedOrNonCanonical(host) -> if (local != null && local.owns(url)) NavigationDecision.Allow else NavigationDecision.Block
                local != null -> leave
                else -> NavigationDecision.Allow
            }
        }
        "about" -> {
            val page = url.substringAfter(':').substringBefore('?').substringBefore('#').lowercase()
            if (page == "blank" || page == "srcdoc") NavigationDecision.Allow else NavigationDecision.Block
        }
        "blob" -> if (local == null || local.owns(url.substringAfter(':'))) NavigationDecision.Allow else NavigationDecision.Block
        in EXTERNAL_SCHEMES -> leave
        else -> NavigationDecision.Block
    }
}

/**
 * A host on the reserved `.invalid` TLD, or one no engine hands over for a real site: percent-escapes or non-ASCII.
 * Engines canonicalize hosts to ASCII/punycode, so this only ever catches tricks.
 */
private fun isReservedOrNonCanonical(host: String): Boolean {
    if (host.any { it == '%' || it.code > 0x7e }) return true
    val trimmed = host.trimEnd('.')
    return trimmed == WebOrigin.SUFFIX || trimmed.endsWith(".${WebOrigin.SUFFIX}")
}
